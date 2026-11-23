package com.duodeck.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

data class Track(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** Folder the file is in, like "Music/Rock/" (blank if unknown). */
    val folder: String = "",
    /** When the file was added to the phone, in seconds (0 if unknown). */
    val dateAdded: Long = 0,
) {
    val displayName: String
        get() = if (artist.isBlank() || artist == "<unknown>") title else "$artist - $title"

    /** An internet radio station (id "radio:..."). For these, [sizeBytes] holds the stream's kbps. */
    val isRadio: Boolean get() = id.startsWith("radio:")

    /** Average bitrate in kbps, estimated from file size and length. */
    val kbps: Int
        get() = if (isRadio) sizeBytes.toInt()
        else if (durationMs > 0 && sizeBytes > 0) ((sizeBytes * 8) / durationMs).toInt() else 0
}

object MusicRepository {

    fun loadLibrary(context: Context): List<Track> {
        val result = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE ASC, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE ASC, ${MediaStore.Audio.Media.TRACK} ASC"
        try {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val pathCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val addedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += Track(
                        id = "ms:$id",
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol) ?: "",
                        album = c.getString(albumCol) ?: "",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        folder = if (pathCol >= 0) c.getString(pathCol) ?: "" else "",
                        dateAdded = if (addedCol >= 0) c.getLong(addedCol) else 0L,
                    )
                }
            }
        } catch (e: SecurityException) {
            // permission not granted yet
        }
        return result
    }

    /** Build a Track from a file the user picked with the system file picker. */
    fun fromDocument(context: Context, uri: Uri): Track? {
        var name = "Unknown"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0) size = c.getLong(s)
                }
            }
        } catch (_: Exception) { }
        var title = name.substringBeforeLast('.')
        var artist = ""
        var album = ""
        var duration = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            return null
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
        return Track("doc:$uri", uri, title, artist, album, duration, size, folder = "Added files/", dateAdded = System.currentTimeMillis() / 1000)
    }
}
