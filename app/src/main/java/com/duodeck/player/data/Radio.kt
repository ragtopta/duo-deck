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
    /** People tuned in right now (SHOUTcast stations only; 0 = unknown). */
    val listeners: Int = 0,
) {
    /** True for stations found in the SHOUTcast directory (their ids start with "sc"). */
    val isShoutcast: Boolean get() = uuid.startsWith(ShoutcastApi.ID_PREFIX)

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
        .put("tags", tags).put("countrycode", country).put("codec", codec).put("bitrate", bitrate).put("listeners", listeners)

    /** Short info for the right side of a list row, like "US 128K MP3". */
    val info: String
        get() = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            bitrate.takeIf { it > 0 }?.let { "${it}K" },
            codec.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            listeners.takeIf { it > 0 }?.let { if (it >= 1000) "${it / 1000}.${(it % 1000) / 100}K ON" else "$it ON" },
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
                listeners = o.optInt("listeners"),
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
        if (uuid.startsWith(ShoutcastApi.ID_PREFIX)) return // not a Radio Browser station
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

/**
 * Looks up stations in the SHOUTcast directory (directory.shoutcast.com), the big list of
 * stations run on SHOUTcast servers. Uses the same lookups as the directory's own web page,
 * so no developer key is needed. A station is played through its "tune in" link
 * (yp.shoutcast.com/sbin/tunein-station.pls?id=...), which [StreamResolver] turns into the real stream.
 */
object ShoutcastApi {
    const val ID_PREFIX = "sc"
    private const val HOST = "https://directory.shoutcast.com"

    /** The directory's main genres (what its web page lists down the side). */
    val GENRES = listOf(
        "Alternative", "Blues", "Classical", "Country", "Decades", "Easy Listening", "Electronic",
        "Folk", "Inspirational", "International", "Jazz", "Latin", "Metal", "Misc", "New Age",
        "Pop", "Public Radio", "R&B and Urban", "Rap", "Reggae", "Rock", "Seasonal and Holiday",
        "Soundtracks", "Talk", "Themes",
    )

    suspend fun top(): List<RadioStation> = stations("/Home/Top", emptyMap())
    suspend fun byGenre(genre: String): List<RadioStation> = stations("/Home/BrowseByGenre", mapOf("genrename" to genre))
    suspend fun search(query: String): List<RadioStation> = stations("/Search/UpdateSearch", mapOf("query" to query))

    fun tuneInUrl(id: String) = "https://yp.shoutcast.com/sbin/tunein-station.pls?id=$id"

    private suspend fun stations(path: String, form: Map<String, String>): List<RadioStation> = withContext(Dispatchers.IO) {
        val conn = URL(HOST + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "DuoDeck/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = form.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            val arr = if (text.startsWith("{")) {
                // some lookups wrap the list: {"stations":[...]}
                val o = JSONObject(text)
                o.optJSONArray("stations") ?: o.optJSONArray("Stations") ?: JSONArray()
            } else JSONArray(text)
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
                .filter { seen.add(it.uuid) }
                .sortedByDescending { it.listeners }
                .take(150)
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.pick(vararg names: String): Any? {
        for (n in names) if (has(n) && !isNull(n)) return get(n)
        return null
    }

    private fun fromJson(o: JSONObject): RadioStation? {
        val id = o.pick("ID", "Id", "id")?.toString()?.takeIf { it.isNotBlank() && it != "0" } ?: return null
        val name = o.pick("Name", "name")?.toString()?.trim().orEmpty()
        val format = o.pick("Format", "MimeType", "mt")?.toString().orEmpty().lowercase()
        val codec = when {
            "aac" in format -> "AAC+"
            "mpeg" in format || "mp3" in format -> "MP3"
            "ogg" in format -> "OGG"
            else -> ""
        }
        return RadioStation(
            uuid = ID_PREFIX + id,
            name = name.ifBlank { "SHOUTcast station" },
            url = tuneInUrl(id),
            tags = o.pick("Genre", "genre")?.toString().orEmpty(),
            codec = codec,
            bitrate = (o.pick("Bitrate", "br") as? Number)?.toInt() ?: o.pick("Bitrate", "br")?.toString()?.toIntOrNull() ?: 0,
            listeners = (o.pick("Listeners", "lc") as? Number)?.toInt() ?: o.pick("Listeners", "lc")?.toString()?.toIntOrNull() ?: 0,
        )
    }
}

/**
 * Some stations (all SHOUTcast ones, and a few in the free list) give a small playlist file
 * (.pls or .m3u) instead of the stream itself. The player can't play those directly,
 * so this reads the playlist and hands back the first real stream address. Results are remembered.
 */
object StreamResolver {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun needsResolving(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        return "tunein-station" in url.lowercase() || u.endsWith(".pls") || u.endsWith(".m3u")
    }

    /** Blocking: call from a background thread. Returns the original address if it can't be resolved. */
    fun resolve(url: String): String {
        if (!needsResolving(url)) return url
        cache[url]?.let { return it }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            val text = try {
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "DuoDeck/1.0")
                conn.inputStream.bufferedReader().use { r -> CharArray(16_384).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
            } finally { conn.disconnect() }
            val found = text.lineSequence().map { it.trim() }.mapNotNull { line ->
                when {
                    line.startsWith("File", ignoreCase = true) && '=' in line -> line.substringAfter('=').trim()
                    line.startsWith("http://", true) || line.startsWith("https://", true) -> line
                    else -> null
                }
            }.firstOrNull { it.startsWith("http", ignoreCase = true) }
            if (found != null) { cache[url] = found; found } else url
        } catch (_: Exception) { url }
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
