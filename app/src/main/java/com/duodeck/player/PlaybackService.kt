package com.duodeck.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.duodeck.player.audio.DspAudioProcessor
import com.duodeck.player.data.MusicStats
import com.duodeck.player.data.RadioNow
import com.duodeck.player.widget.WidgetUpdater
import androidx.media3.common.Player
import android.net.Uri
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background playback. Media3 automatically shows the media notification
 * (with play/pause/skip) and lock-screen controls for this session.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countedId: String? = null
    private var saveQueueJob: Job? = null
    private var fading = false
    private var lastItemId: String? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(DspAudioProcessor()))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Media items sent from the UI arrive without their file URI, so restore it.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }

                // Play pressed on the widget / headset / system media controls while nothing is loaded:
                // bring back the last queue where it left off.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val saved = loadQueue()
                        ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
                    return Futures.immediateFuture(saved)
                }
            })
            .build()

        MusicStats.init(this)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != lastItemId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    // (a radio song-title update swaps in the same station, so don't treat that as a new item)
                    lastItemId = mediaItem?.mediaId
                    countedId = null
                    RadioNow.songTitle.value = null
                    RadioNow.error.value = null
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && SleepTimer.state.value.endOfSong) {
                    // "end of this song" sleep timer: the song just finished
                    player.pause()
                    SleepTimer.cancel()
                }
                if (player.isPlaying) countPlay(player)
                scheduleQueueSave(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) countPlay(player) else savePosition(player)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && SleepTimer.state.value.endOfSong) SleepTimer.cancel()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = scheduleQueueSave(player)
            override fun onEvents(p: Player, events: Player.Events) = updateWidget(p)

            // Internet radio: stations send the song that's playing as "ICY" info inside the stream.
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                val item = player.currentMediaItem ?: return
                if (!item.mediaId.startsWith("radio:")) return
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is androidx.media3.extractor.metadata.icy.IcyInfo) {
                        val song = e.title?.trim()?.trim('-', ' ')?.takeIf { it.isNotBlank() } ?: continue
                        if (song == RadioNow.songTitle.value) continue
                        RadioNow.songTitle.value = song
                        // Show the song on the notification, lock screen and widget too.
                        val md = item.mediaMetadata.buildUpon().setArtist(song).build()
                        try {
                            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(md).build())
                        } catch (_: Exception) { }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val item = player.currentMediaItem ?: return
                if (item.mediaId.startsWith("radio:")) {
                    RadioNow.error.value = "COULDN'T CONNECT TO ${item.mediaMetadata.title ?: "THIS STATION"}. IT MAY BE OFFLINE, TRY ANOTHER."
                }
            }
        })
        updateWidget(player)

        // Sleep timer: fade out over the last 10 seconds, then pause.
        scope.launch {
            SleepTimer.state.collectLatest { st ->
                if (st.endAt <= 0L) return@collectLatest
                val fadeMs = 10_000L
                val wait = st.endAt - System.currentTimeMillis() - fadeMs
                if (wait > 0) delay(wait)
                val startVol = player.volume
                fading = true
                try {
                    val steps = 40
                    for (i in 1..steps) {
                        player.volume = startVol * (1f - i / steps.toFloat())
                        delay(fadeMs / steps)
                    }
                    player.pause()
                } finally {
                    player.volume = startVol
                    fading = false
                }
                SleepTimer.cancel()
            }
        }
    }

    private fun countPlay(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.startsWith("radio:")) return // only songs count toward Most Played
        if (id == countedId) return
        countedId = id
        MusicStats.recordPlay(id)
    }

    private fun updateWidget(player: Player) {
        val md = player.currentMediaItem?.mediaMetadata
        WidgetUpdater.saveNowPlaying(
            this,
            md?.title?.toString(),
            md?.artist?.toString(),
            player.playWhenReady && player.mediaItemCount > 0,
            canResume = player.mediaItemCount > 0 || getSharedPreferences(RESUME, MODE_PRIVATE).contains("items"),
        )
    }

    // ---------------- remember the queue so it can be resumed later ----------------

    private fun scheduleQueueSave(player: Player) {
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            delay(1500)
            val arr = JSONArray()
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
                val md = item.mediaMetadata
                arr.put(
                    JSONObject().put("id", item.mediaId).put("uri", uri.toString())
                        .put("t", md.title?.toString() ?: "").put("a", md.artist?.toString() ?: "")
                        .put("al", md.albumTitle?.toString() ?: "")
                )
            }
            val json = arr.toString()
            val idx = player.currentMediaItemIndex
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                getSharedPreferences(RESUME, MODE_PRIVATE).edit()
                    .apply { if (arr.length() > 0) putString("items", json) }
                    .putInt("index", idx).apply()
            }
        }
    }

    private fun savePosition(player: Player) {
        if (player.mediaItemCount == 0) return
        getSharedPreferences(RESUME, MODE_PRIVATE).edit()
            .putInt("index", player.currentMediaItemIndex)
            .putLong("pos", player.currentPosition.coerceAtLeast(0)).apply()
    }

    @OptIn(UnstableApi::class)
    private fun loadQueue(): MediaSession.MediaItemsWithStartPosition? {
        val p = getSharedPreferences(RESUME, MODE_PRIVATE)
        val json = p.getString("items", null) ?: return null
        return try {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                MediaItem.Builder()
                    .setMediaId(o.getString("id"))
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(o.optString("t")).setArtist(o.optString("a"))
                            .setAlbumTitle(o.optString("al")).build()
                    )
                    .build()
            }
            if (items.isEmpty()) null
            else MediaSession.MediaItemsWithStartPosition(
                items, p.getInt("index", 0).coerceIn(0, items.size - 1), p.getLong("pos", 0L)
            )
        } catch (_: Exception) { null }
    }

    companion object { private const val RESUME = "resume" }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.player?.let { savePosition(it); WidgetUpdater.saveNowPlaying(this, it.currentMediaItem?.mediaMetadata?.title?.toString(), it.currentMediaItem?.mediaMetadata?.artist?.toString(), false, getSharedPreferences(RESUME, MODE_PRIVATE).contains("items")) }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
