package com.duodeck.player.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedPlaylist(val id: String, val name: String, val tracks: List<Track>)

/** User-built playlists, stored as one JSON file. Full track info is kept so picked files still work later. */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")

    @Synchronized
    fun load(): List<SavedPlaylist> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ta = o.getJSONArray("tracks")
                SavedPlaylist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    tracks = (0 until ta.length()).map { j -> trackFrom(ta.getJSONObject(j)) }
                )
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Writes to a temp file then swaps it in, one save at a time, so a half-written file can never wipe your playlists. */
    @Synchronized
    fun save(lists: List<SavedPlaylist>) {
        val arr = JSONArray()
        lists.forEach { p ->
            val ta = JSONArray()
            p.tracks.forEach { ta.put(trackTo(it)) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", ta))
        }
        val tmp = File(file.parentFile, "playlists.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }

    private fun trackTo(t: Track) = JSONObject()
        .put("id", t.id).put("uri", t.uri.toString()).put("title", t.title).put("artist", t.artist)
        .put("album", t.album).put("dur", t.durationMs).put("size", t.sizeBytes)
        .put("folder", t.folder).put("added", t.dateAdded)

    private fun trackFrom(o: JSONObject) = Track(
        id = o.getString("id"), uri = Uri.parse(o.getString("uri")), title = o.optString("title"),
        artist = o.optString("artist"), album = o.optString("album"), durationMs = o.optLong("dur"), sizeBytes = o.optLong("size"),
        folder = o.optString("folder"), dateAdded = o.optLong("added"),
    )

    companion object {
        fun newId() = UUID.randomUUID().toString()
    }
}
