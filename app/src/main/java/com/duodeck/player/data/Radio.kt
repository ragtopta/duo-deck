package com.duodeck.player.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One internet radio station from the free Radio Browser directory (radio-browser.info). */
data class RadioStation(
    val uuid: String,
    val name: String,
    val url: String,
    val tags: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
) {
    fun toTrack() = Track(
        id = "radio:$uuid",
        uri = Uri.parse(url),
        title = name.trim().ifBlank { "Radio station" },
        artist = "",
        album = "RADIO",
        durationMs = 0,
        sizeBytes = bitrate.toLong(), // for radio, holds kbps (see Track.isRadio)
        folder = "Radio/",
    )

    fun toJson(): JSONObject = JSONObject().put("stationuuid", uuid).put("name", name).put("url_resolved", url)
        .put("tags", tags).put("countrycode", country).put("codec", codec).put("bitrate", bitrate)

    /** Short info for the right side of a list row, like "US 128K MP3". */
    val info: String
        get() = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            bitrate.takeIf { it > 0 }?.let { "${it}K" },
            codec.takeIf { it.isNotBlank() && it != "UNKNOWN" },
        ).joinToString(" ")

    companion object {
        fun fromJson(o: JSONObject): RadioStation? {
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val uuid = o.optString("stationuuid")
            if (url.isBlank() || uuid.isBlank()) return null
            return RadioStation(
                uuid = uuid,
                name = o.optString("name").trim(),
                url = url.trim(),
                tags = o.optString("tags"),
                country = o.optString("countrycode"),
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
            )
        }
    }
}

/** Looks up stations in the Radio Browser directory. Free, no sign-up or key needed. */
object RadioApi {
    // The directory runs on several mirrors; if one is down we try the next.
    private val servers = listOf(
        "all.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "nl1.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "at1.api.radio-browser.info",
    )
    @Volatile private var good: String? = null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val COMMON = "hidebroken=true&order=clickcount&reverse=true&limit=80"

    suspend fun top(): List<RadioStation> = stations("/json/stations/search?$COMMON")
    suspend fun search(name: String): List<RadioStation> = stations("/json/stations/search?name=${enc(name)}&$COMMON")
    suspend fun byTag(tag: String): List<RadioStation> = stations("/json/stations/search?tag=${enc(tag)}&$COMMON")
    suspend fun byCountry(code: String): List<RadioStation> = stations("/json/stations/search?countrycode=${enc(code)}&$COMMON")

    /** Tells the directory a station was played (this is how its "popular" ranking works). Errors are ignored. */
    suspend fun click(uuid: String) {
        try { get("/json/url/${enc(uuid)}") } catch (_: Exception) { }
    }

    private suspend fun stations(path: String): List<RadioStation> {
        val body = get(path)
        val arr = JSONArray(body)
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { RadioStation.fromJson(arr.getJSONObject(it)) }
            // the directory often lists the same stream twice
            .filter { seen.add(it.url.lowercase()) }
    }

    @Volatile private var discovered: List<String>? = null

    /** The directory's official way to find live mirrors: look up all.api.radio-browser.info and name each address. */
    private fun discover(): List<String> {
        discovered?.let { return it }
        val found = try {
            java.net.InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { a -> a.canonicalHostName.takeIf { it.endsWith(".radio-browser.info") } }
                .distinct().shuffled()
        } catch (_: Exception) { emptyList() }
        if (found.isNotEmpty()) discovered = found
        return found
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        val order = (listOfNotNull(good) + discover() + servers).distinct()
        for (host in order) {
            try {
                val conn = URL("https://$host$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "DuoDeck/1.0")
                try {
                    if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    good = host
                    return@withContext text
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No radio directory reachable")
    }
}

/** Stations the user saved ("MY STATIONS"). Kept on the phone. */
object RadioStore {
    private val _saved = MutableStateFlow<List<RadioStation>>(emptyList())
    val saved: StateFlow<List<RadioStation>> = _saved
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        prefs = p
        _saved.value = try {
            val arr = JSONArray(p.getString("saved", "[]"))
            (0 until arr.length()).mapNotNull { RadioStation.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun isSaved(uuid: String) = _saved.value.any { it.uuid == uuid }

    fun save(s: RadioStation) {
        if (isSaved(s.uuid)) return
        write(_saved.value + s)
    }

    fun remove(uuid: String) = write(_saved.value.filter { it.uuid != uuid })

    private fun write(list: List<RadioStation>) {
        _saved.value = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString("saved", arr.toString())?.apply()
    }
}

/** What the radio station says is playing right now, and whether a station failed to load. */
object RadioNow {
    /** Song title sent by the station (null if it sends none). */
    val songTitle = MutableStateFlow<String?>(null)
    /** Set when a station could not be played. */
    val error = MutableStateFlow<String?>(null)
}
