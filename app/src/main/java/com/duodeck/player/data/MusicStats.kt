package com.duodeck.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Favorites, play counts and when each song was last played. Saved on the phone. */
object MusicStats {
    data class Stats(
        val favorites: Set<String> = emptySet(),
        val plays: Map<String, Int> = emptyMap(),
        val lastPlayed: Map<String, Long> = emptyMap(),
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)
        prefs = p
        _stats.value = Stats(
            favorites = p.getStringSet("favorites", emptySet())?.toSet() ?: emptySet(),
            plays = readMap(p.getString("plays", null)) { o, k -> o.optInt(k) },
            lastPlayed = readMap(p.getString("last", null)) { o, k -> o.optLong(k) },
        )
    }

    private fun <T> readMap(json: String?, get: (JSONObject, String) -> T): Map<String, T> = try {
        if (json == null) emptyMap() else JSONObject(json).let { o -> o.keys().asSequence().associateWith { get(o, it) } }
    } catch (_: Exception) { emptyMap() }

    fun isFavorite(id: String) = id in _stats.value.favorites

    @Synchronized
    fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        val s = _stats.value
        val favs = if (favorite) s.favorites + ids else s.favorites - ids.toSet()
        _stats.value = s.copy(favorites = favs)
        prefs?.edit()?.putStringSet("favorites", favs)?.apply()
    }

    @Synchronized
    fun recordPlay(id: String) {
        if (id.isBlank()) return
        val s = _stats.value
        val plays = s.plays + (id to (s.plays[id] ?: 0) + 1)
        // keep the "recently played" list from growing forever
        val last = (s.lastPlayed + (id to System.currentTimeMillis())).let { m ->
            if (m.size > 500) m.entries.sortedByDescending { it.value }.take(400).associate { it.key to it.value } else m
        }
        _stats.value = s.copy(plays = plays, lastPlayed = last)
        prefs?.edit()
            ?.putString("plays", JSONObject(plays as Map<*, *>).toString())
            ?.putString("last", JSONObject(last as Map<*, *>).toString())
            ?.apply()
    }
}
