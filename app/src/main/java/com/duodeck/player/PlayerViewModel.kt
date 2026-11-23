package com.duodeck.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.duodeck.player.audio.AudioBus
import com.duodeck.player.data.MusicRepository
import com.duodeck.player.data.PlaylistStore
import com.duodeck.player.data.SavedPlaylist
import com.duodeck.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ALL, ONE }
enum class SortMode(val label: String) { ARTIST("ARTIST"), TITLE("TITLE"), ALBUM("ALBUM"), SHUFFLED("RANDOM") }

data class PlayerUiState(
    val playlist: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 0.8f,
    val balance: Float = 0f,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val libraryLoaded: Boolean = false,
    val connected: Boolean = false,
) {
    val current: Track? get() = playlist.getOrNull(currentIndex)
}

/** Play position, updated several times a second. Kept apart so the whole screen doesn't redraw with it. */
data class Progress(val positionMs: Long = 0, val durationMs: Long = 0)

data class EqState(
    val enabled: Boolean = true,
    val preamp: Float = 0f,
    val bands: List<Float> = List(AudioBus.BANDS) { 0f },
    val presetName: String = "Flat",
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _eq = MutableStateFlow(loadEq())
    val eq: StateFlow<EqState> = _eq.asStateFlow()

    private var controller: MediaController? = null
    private var sortMode = SortMode.ARTIST

    /** Every song found on the device (plus files added with ADD). */
    private val _library = MutableStateFlow<List<Track>>(emptyList())
    val library: StateFlow<List<Track>> = _library.asStateFlow()

    private val playlistStore = PlaylistStore(app)
    private val _playlists = MutableStateFlow(playlistStore.load())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    init {
        pushEq(_eq.value)
        _state.update {
            it.copy(
                volume = prefs.getFloat("volume", 0.8f),
                balance = prefs.getFloat("balance", 0f),
            )
        }
        connect()
        viewModelScope.launch {
            AudioBus.format.collect { f ->
                _state.update { it.copy(sampleRate = f.sampleRate, channels = f.channels) }
            }
        }
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    _progress.value = Progress(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.takeIf { d -> d > 0 } ?: (_state.value.current?.durationMs ?: 0),
                    )
                }
                delay(200)
            }
        }
    }

    // ---------------- connection ----------------

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.volume = _state.value.volume
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = syncFromController()
            })
            _state.update { it.copy(connected = true) }
            if (c.mediaItemCount > 0) {
                // Music was already loaded/playing in the background (app reopened): keep it, don't restart.
                adoptControllerQueue()
            } else {
                sendQueueIfPlayerEmpty()
            }
            syncFromController()
        }, MoreExecutors.directExecutor())
    }

    private fun syncFromController() {
        val c = controller ?: return
        _state.update {
            it.copy(
                currentIndex = if (c.mediaItemCount > 0) c.currentMediaItemIndex else -1,
                isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
                isPaused = !c.playWhenReady && c.mediaItemCount > 0 && c.currentPosition > 0,
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
            )
        }
    }

    // ---------------- library ----------------

    fun loadLibrary() {
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                MusicRepository.loadLibrary(getApplication())
            }
            _library.value = tracks.sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title })
            val c = controller
            if (c != null && c.mediaItemCount > 0) {
                // Something is already queued: never replace it (that would stop the music).
                // Just fill in full song details for anything we restored from the player.
                val byId = tracks.associateBy { it.id }
                _state.update { s -> s.copy(playlist = s.playlist.map { byId[it.id] ?: it }, libraryLoaded = true) }
                return@launch
            }
            if (_state.value.playlist.isEmpty() || !_state.value.libraryLoaded) {
                _state.update { it.copy(playlist = sorted(tracks), libraryLoaded = true) }
            } else {
                _state.update { it.copy(libraryLoaded = true) }
            }
            sendQueueIfPlayerEmpty()
        }
    }

    /** Rebuilds our song list from what the background player already has queued. */
    private fun adoptControllerQueue() {
        val c = controller ?: return
        val known = (_library.value + _state.value.playlist + _playlists.value.flatMap { it.tracks }).associateBy { it.id }
        val restored = (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i)
            known[m.mediaId] ?: run {
                val uri = m.localConfiguration?.uri ?: m.requestMetadata.mediaUri ?: uriFromId(m.mediaId)
                Track(
                    id = m.mediaId, uri = uri,
                    title = m.mediaMetadata.title?.toString() ?: "Unknown",
                    artist = m.mediaMetadata.artist?.toString() ?: "",
                    album = m.mediaMetadata.albumTitle?.toString() ?: "",
                    durationMs = 0, sizeBytes = 0,
                )
            }
        }
        _state.update { it.copy(playlist = restored) }
    }

    private fun uriFromId(id: String): Uri = when {
        id.startsWith("ms:") -> id.removePrefix("ms:").toLongOrNull()?.let {
            android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
        } ?: Uri.EMPTY
        id.startsWith("doc:") -> Uri.parse(id.removePrefix("doc:"))
        else -> Uri.EMPTY
    }

    /** Hands our song list to the player, but only if the player has nothing queued yet. */
    private fun sendQueueIfPlayerEmpty() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val list = _state.value.playlist
        if (list.isEmpty()) return
        c.setMediaItems(list.map { it.toMediaItem() })
        c.prepare()
    }

    fun addFiles(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) { }
                    MusicRepository.fromDocument(ctx, uri)
                }
            }
            if (added.isEmpty()) return@launch
            _library.value = _library.value + added.filter { a -> _library.value.none { it.id == a.id } }
            _state.update { it.copy(playlist = it.playlist + added) }
            controller?.addMediaItems(added.map { it.toMediaItem() })
        }
    }

    fun removeAt(index: Int) {
        val list = _state.value.playlist
        if (index !in list.indices) return
        _state.update { it.copy(playlist = list.filterIndexed { i, _ -> i != index }) }
        controller?.removeMediaItem(index)
    }

    // ---------------- queue building ----------------

    /** Replace the queue with these songs and start playing at [start]. */
    fun playTracks(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        _state.update { it.copy(playlist = tracks) }
        val c = controller ?: return
        c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.size - 1), 0)
        c.prepare()
        c.play()
    }

    /** Add songs to the end of the queue, or right after the current song when [next] is true. */
    fun queueTracks(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        val c = controller
        val list = _state.value.playlist
        val at = if (next && _state.value.currentIndex >= 0) (_state.value.currentIndex + 1).coerceAtMost(list.size) else list.size
        _state.update { it.copy(playlist = list.take(at) + tracks + list.drop(at)) }
        if (c != null) {
            c.addMediaItems(at, tracks.map { it.toMediaItem() })
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        _state.update { it.copy(playlist = emptyList(), currentIndex = -1, isPlaying = false, isPaused = false) }
    }

    // ---------------- saved playlists ----------------

    private fun savePlaylists(lists: List<SavedPlaylist>) {
        _playlists.value = lists
        viewModelScope.launch(Dispatchers.IO) { playlistStore.save(lists) }
    }

    fun createPlaylist(name: String, tracks: List<Track>): SavedPlaylist {
        val p = SavedPlaylist(PlaylistStore.newId(), name.ifBlank { "Playlist ${_playlists.value.size + 1}" }, tracks)
        savePlaylists(_playlists.value + p)
        return p
    }

    fun addToPlaylist(id: String, tracks: List<Track>) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(tracks = it.tracks + tracks) else it })

    fun removeFromPlaylist(id: String, index: Int) =
        savePlaylists(_playlists.value.map { p -> if (p.id == id) p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) else p })

    fun renamePlaylist(id: String, name: String) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(name = name) else it })

    fun deletePlaylist(id: String) = savePlaylists(_playlists.value.filter { it.id != id })

    fun cycleSort(): SortMode {
        sortMode = SortMode.entries[(sortMode.ordinal + 1) % SortMode.entries.size]
        val current = _state.value.current
        val newList = sorted(_state.value.playlist)
        _state.update { it.copy(playlist = newList) }
        val c = controller ?: return sortMode
        val pos = c.currentPosition
        val wasPlaying = c.playWhenReady
        val newIndex = current?.let { t -> newList.indexOfFirst { it.id == t.id } }?.takeIf { it >= 0 } ?: 0
        c.setMediaItems(newList.map { it.toMediaItem() }, newIndex, pos)
        c.prepare()
        c.playWhenReady = wasPlaying
        return sortMode
    }

    private fun sorted(list: List<Track>): List<Track> = when (sortMode) {
        SortMode.ARTIST -> list.sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        SortMode.TITLE -> list.sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title })
        SortMode.ALBUM -> list.sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        SortMode.SHUFFLED -> list.shuffled()
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()

    // ---------------- transport ----------------

    fun playIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0)
        c.play()
    }

    fun play() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    fun pause() { controller?.pause() }
    fun togglePause() { controller?.let { if (it.playWhenReady) it.pause() else play() } }

    fun stop() {
        controller?.let {
            it.pause()
            it.seekTo(0)
        }
        _state.update { it.copy(isPaused = false) }
    }

    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else if (it.mediaItemCount > 0) it.seekTo(0, 0) } }
    fun previous() {
        controller?.let {
            if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
        }
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val d = _progress.value.durationMs
        if (d > 0) c.seekTo((d * fraction).toLong())
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        controller?.volume = vol
        _state.update { it.copy(volume = vol) }
        prefs.edit().putFloat("volume", vol).apply()
    }

    fun setBalance(b: Float) {
        val bal = if (kotlin.math.abs(b) < 0.06f) 0f else b.coerceIn(-1f, 1f) // snap to centre
        _state.update { it.copy(balance = bal) }
        prefs.edit().putFloat("balance", bal).apply()
        pushEq(_eq.value)
    }

    // ---------------- equalizer ----------------

    fun setEqEnabled(on: Boolean) = setEq(_eq.value.copy(enabled = on))
    fun setPreamp(db: Float) = setEq(_eq.value.copy(preamp = db))
    fun setBand(i: Int, db: Float) = setEq(_eq.value.copy(bands = _eq.value.bands.toMutableList().also { it[i] = db }, presetName = "Custom"))
    fun applyPreset(p: EqPreset) = setEq(_eq.value.copy(bands = p.bands.toList(), preamp = p.preamp, presetName = p.name))

    private fun setEq(e: EqState) {
        _eq.value = e
        pushEq(e)
        prefs.edit()
            .putBoolean("eq_on", e.enabled)
            .putFloat("eq_pre", e.preamp)
            .putString("eq_bands", e.bands.joinToString(","))
            .putString("eq_name", e.presetName)
            .apply()
    }

    private fun pushEq(e: EqState) {
        AudioBus.updateEq(e.enabled, e.preamp, e.bands.toFloatArray(), _state.value.balance)
    }

    private fun loadEq(): EqState {
        val bands = prefs.getString("eq_bands", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == AudioBus.BANDS } ?: List(AudioBus.BANDS) { 0f }
        return EqState(
            enabled = prefs.getBoolean("eq_on", true),
            preamp = prefs.getFloat("eq_pre", 0f),
            bands = bands,
            presetName = prefs.getString("eq_name", "Flat") ?: "Flat",
        )
    }

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}

class EqPreset(val name: String, val preamp: Float, vararg val bands: Float)

val EQ_PRESETS = listOf(
    EqPreset("Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    EqPreset("Rock", -2f, 5f, 3f, -2f, -4f, -1f, 2f, 5f, 6f, 6f, 6f),
    EqPreset("Pop", -1f, -1f, 3f, 5f, 5f, 3f, 0f, -1f, -1f, -1f, -1f),
    EqPreset("Dance", -3f, 7f, 5f, 2f, 0f, 0f, -3f, -4f, -4f, 0f, 0f),
    EqPreset("Jazz", 0f, 3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
    EqPreset("Classical", 0f, 0f, 0f, 0f, 0f, 0f, 0f, -4f, -4f, -4f, -6f),
    EqPreset("Bass Boost", -4f, 8f, 6f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
    EqPreset("Treble Boost", -3f, 0f, 0f, 0f, 0f, 0f, 2f, 5f, 7f, 8f, 8f),
    EqPreset("Vocal", -2f, -3f, -2f, 0f, 3f, 5f, 5f, 3f, 0f, -1f, -2f),
    EqPreset("Loudness", -4f, 6f, 4f, 0f, 0f, -2f, 0f, -1f, 4f, 6f, 6f),
    EqPreset("Shop Floor", -3f, -2f, 0f, 2f, 4f, 6f, 6f, 4f, 2f, 0f, -2f),
    EqPreset("Headphones", -1f, 4f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 3f, 2f),
)
