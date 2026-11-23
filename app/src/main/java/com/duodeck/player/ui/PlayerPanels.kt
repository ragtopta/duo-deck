package com.duodeck.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duodeck.player.EQ_PRESETS
import com.duodeck.player.EqPreset
import com.duodeck.player.EqState
import com.duodeck.player.PlayerUiState
import com.duodeck.player.Progress
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.duodeck.player.RepeatMode
import com.duodeck.player.audio.AudioBus
import com.duodeck.player.data.Track
import com.duodeck.player.skin.Slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Everything the panels can ask the player to do. */
interface PlayerActions {
    fun play() {}
    fun togglePause() {}
    fun stop() {}
    fun next() {}
    fun previous() {}
    fun seek(fraction: Float) {}
    fun setVolume(v: Float) {}
    fun setBalance(b: Float) {}
    fun toggleShuffle() {}
    fun cycleRepeat() {}
    fun playIndex(i: Int) {}
    fun addFiles() {}
    fun cycleSort(): String = ""
    fun toggleEqPanel() {}
    fun togglePlaylist() {}
    fun openMenu() {}
    fun setEqEnabled(on: Boolean) {}
    fun setPreamp(db: Float) {}
    fun setBand(i: Int, db: Float) {}
    fun applyPreset(p: EqPreset) {}
    fun requestPermission() {}
    fun swapScreens() {}

    // library & playlists
    fun playTracks(tracks: List<Track>, start: Int) {}
    fun queueTracks(tracks: List<Track>, next: Boolean) {}
    fun removeFromQueue(index: Int) {}
    fun clearQueue() {}
    fun createPlaylist(name: String, tracks: List<Track>) {}
    fun addToPlaylist(id: String, tracks: List<Track>) {}
    fun removeFromPlaylist(id: String, index: Int) {}
    fun deletePlaylist(id: String) {}
}

object NoActions : PlayerActions

// ===================================================================== MAIN PANEL

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPanel(
    state: PlayerUiState,
    actions: PlayerActions,
    eqVisible: Boolean,
    playlistVisible: Boolean,
    modifier: Modifier = Modifier,
    demoVis: Boolean = false,
    progressFlow: StateFlow<Progress>? = null,
    titleModifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    var showRemaining by remember { mutableStateOf(false) }
    val seekPreview = remember { mutableStateOf<Float?>(null) }
    val perf by com.duodeck.player.PerfSettings.values.collectAsState()
    // Position changes several times a second; it's only read inside the clock and seek bar,
    // so only those small parts redraw, not the whole screen.
    val fallback = remember(state.positionMs, state.durationMs) { MutableStateFlow(Progress(state.positionMs, state.durationMs)) }
    val progress = (progressFlow ?: fallback).collectAsState()
    val disp = sc(Slot.DISPLAY_TEXT)
    val dim = sc(Slot.DISPLAY_DIM)

    SkinPanel("Duo-Deck", modifier, onMenu = { actions.openMenu() }, titleModifier = titleModifier) {
        Row(Modifier.fillMaxWidth().height(84.dp)) {
            // ---- clock + mini spectrum
            DisplayBox(Modifier.weight(0.42f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when {
                            state.isPlaying -> Icon.PLAY
                            state.isPaused -> Icon.PAUSE
                            else -> Icon.STOP
                        }
                        TransportIcon(icon, disp, Modifier.size(9.dp))
                        Spacer(Modifier.width(6.dp))
                        val pr = progress.value
                        val pos = seekPreview.value?.let { (it * pr.durationMs).toLong() } ?: pr.positionMs
                        val text = if (showRemaining) "-" + formatTime(pr.durationMs - pos) else formatTime(pos)
                        SevenSegment(
                            text, disp, dim,
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clickable { showRemaining = !showRemaining }
                        )
                    }
                    if (perf.miniVis || demoVis) {
                        VuMeters(state.isPlaying, Modifier.fillMaxWidth().height(20.dp), demo = demoVis)
                    } else {
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(5.dp))
            // ---- title + stream info
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                DisplayBox(Modifier.fillMaxWidth().height(28.dp)) {
                    val t = state.current
                    val radioSong by com.duodeck.player.data.RadioNow.songTitle.collectAsState()
                    val title = when {
                        t == null -> "DUO-DECK  ***  READY  ***  "
                        t.isRadio -> "RADIO: ${t.title}${radioSong?.let { " - $it" } ?: ""} (LIVE)  ***  "
                        else -> "${state.currentIndex + 1}. ${t.displayName} (${formatLong(t.durationMs)})  ***  "
                    }
                    Text(
                        text = title.uppercase(),
                        color = disp,
                        maxLines = 1,
                        style = TextStyle(fontFamily = skin.listFamily(), fontSize = (14 * skin.listScale()).sp),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .then(
                                if (perf.scrollTitle || demoVis) Modifier
                                    .graphicsLayer()
                                    .basicMarquee(iterations = Int.MAX_VALUE, animationMode = MarqueeAnimationMode.Immediately, initialDelayMillis = 800, velocity = 40.dp)
                                else Modifier
                            )
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val kbps = state.current?.kbps ?: 0
                    DisplayBox(Modifier.height(22.dp)) {
                        SkinText(if (kbps > 0) "%3d".format(kbps.coerceAtMost(999)) else "  -", disp, 13.sp, Modifier.align(Alignment.Center), family = TerminalFamily)
                    }
                    SkinText("KBPS", sc(Slot.TITLE_TEXT), 8.sp, Modifier.padding(horizontal = 4.dp))
                    DisplayBox(Modifier.height(22.dp)) {
                        val khz = if (state.sampleRate > 0) (state.sampleRate / 1000).toString() else " -"
                        SkinText(khz, disp, 13.sp, Modifier.align(Alignment.Center), family = TerminalFamily)
                    }
                    SkinText("KHZ", sc(Slot.TITLE_TEXT), 8.sp, Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.weight(1f))
                    SleepBadge()
                    val stereo = state.channels >= 2
                    val mono = state.channels == 1
                    SkinText("MONO", if (mono) sc(Slot.ACCENT) else sc(Slot.FRAME_DARK), 8.sp)
                    Spacer(Modifier.width(4.dp))
                    SkinText("STEREO", if (stereo) sc(Slot.ACCENT) else sc(Slot.FRAME_DARK), 8.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkinSlider(state.volume, { actions.setVolume(it) }, Modifier.weight(1.3f))
                    Spacer(Modifier.width(4.dp))
                    SkinSlider((state.balance + 1f) / 2f, { actions.setBalance(it * 2f - 1f) }, Modifier.weight(0.7f), centered = true, thumbWidth = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    TextButtonSkin("EQ", lit = eqVisible, size = 8.sp) { actions.toggleEqPanel() }
                    Spacer(Modifier.width(3.dp))
                    TextButtonSkin("PL", lit = playlistVisible, size = 8.sp) { actions.togglePlaylist() }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        // ---- seek bar
        SeekBar(progress, seekPreview, actions)
        Spacer(Modifier.height(5.dp))
        // ---- transport row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val ic = sc(Slot.BUTTON_TEXT)
            val btn = Modifier.size(width = 34.dp, height = 28.dp)
            SkinButton(btn, onClick = { actions.previous() }) { TransportIcon(Icon.PREV, ic) }
            Spacer(Modifier.width(3.dp))
            SkinButton(btn, onClick = { actions.play() }) { TransportIcon(Icon.PLAY, ic) }
            Spacer(Modifier.width(3.dp))
            SkinButton(btn, onClick = { actions.togglePause() }) { TransportIcon(Icon.PAUSE, ic) }
            Spacer(Modifier.width(3.dp))
            SkinButton(btn, onClick = { actions.stop() }) { TransportIcon(Icon.STOP, ic) }
            Spacer(Modifier.width(3.dp))
            SkinButton(btn, onClick = { actions.next() }) { TransportIcon(Icon.NEXT, ic) }
            Spacer(Modifier.width(8.dp))
            SkinButton(Modifier.size(width = 30.dp, height = 24.dp), onClick = { actions.addFiles() }) {
                TransportIcon(Icon.EJECT, ic, Modifier.size(12.dp))
            }
            Spacer(Modifier.weight(1f))
            val stats by com.duodeck.player.data.MusicStats.stats.collectAsState()
            val curId = state.current?.id
            TextButtonSkin("FAV", lit = curId != null && curId in stats.favorites, size = 8.sp) {
                if (curId != null) com.duodeck.player.data.MusicStats.setFavorite(listOf(curId), curId !in stats.favorites)
            }
            Spacer(Modifier.width(3.dp))
            TextButtonSkin("SWAP", size = 8.sp) { actions.swapScreens() }
            Spacer(Modifier.width(3.dp))
            TextButtonSkin("SHUFFLE", lit = state.shuffle, size = 8.sp) { actions.toggleShuffle() }
            Spacer(Modifier.width(3.dp))
            TextButtonSkin(if (state.repeat == RepeatMode.ONE) "REP 1" else "REP", lit = state.repeat != RepeatMode.OFF, size = 8.sp) { actions.cycleRepeat() }
        }
    }
}

@Composable
private fun SeekBar(progress: State<Progress>, seekPreview: MutableState<Float?>, actions: PlayerActions) {
    val pr = progress.value
    val frac = if (pr.durationMs > 0) (pr.positionMs.toFloat() / pr.durationMs).coerceIn(0f, 1f) else 0f
    SkinSlider(
        seekPreview.value ?: frac,
        onChange = { seekPreview.value = it },
        onFinished = { actions.seek(it); seekPreview.value = null },
        modifier = Modifier.fillMaxWidth(),
        thumbWidth = 30.dp,
    )
}

// ===================================================================== EQUALIZER

@Composable
fun EqPanel(eq: EqState, actions: PlayerActions, modifier: Modifier = Modifier, titleModifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    var presetsOpen by remember { mutableStateOf(false) }
    SkinPanel("Equalizer", modifier, titleModifier = titleModifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButtonSkin("ON", lit = eq.enabled, size = 8.sp) { actions.setEqEnabled(!eq.enabled) }
            Spacer(Modifier.width(6.dp))
            // response curve
            DisplayBox(Modifier.weight(1f).height(26.dp)) {
                val line = sc(Slot.DISPLAY_TEXT)
                val mid = sc(Slot.DISPLAY_DIM)
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(mid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                    val p = Path()
                    val n = eq.bands.size
                    for (i in 0 until n) {
                        val x = size.width * i / (n - 1)
                        val y = size.height / 2 - (eq.bands[i] / 12f) * size.height * 0.45f
                        if (i == 0) p.moveTo(x, y) else {
                            val px = size.width * (i - 1) / (n - 1)
                            val py = size.height / 2 - (eq.bands[i - 1] / 12f) * size.height * 0.45f
                            p.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
                        }
                    }
                    drawPath(p, if (eq.enabled) line else mid, style = Stroke(2f))
                }
            }
            Spacer(Modifier.width(6.dp))
            Box {
                TextButtonSkin("PRESETS", size = 8.sp) { presetsOpen = true }
                SkinMenu(
                    expanded = presetsOpen,
                    onDismiss = { presetsOpen = false },
                    items = EQ_PRESETS.map { p -> SkinMenuItem(p.name) { actions.applyPreset(p) } },
                    alignment = Alignment.TopEnd,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(118.dp)) {
            EqColumn("PRE", eq.preamp, eq.enabled) { actions.setPreamp(it) }
            Column(Modifier.padding(horizontal = 2.dp).fillMaxHeight().padding(bottom = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                SkinText("+12", sc(Slot.TITLE_TEXT), 7.sp)
                SkinText("0", sc(Slot.TITLE_TEXT), 7.sp)
                SkinText("-12", sc(Slot.TITLE_TEXT), 7.sp)
            }
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until AudioBus.BANDS) {
                    EqColumn(AudioBus.BAND_LABELS[i], eq.bands[i], eq.enabled) { actions.setBand(i, it) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        SkinText("PRESET: ${eq.presetName.uppercase()}", sc(Slot.TITLE_TEXT), 7.sp, Modifier.align(Alignment.End))
        @Suppress("UNUSED_EXPRESSION") skin
    }
}

@Composable
private fun EqColumn(label: String, db: Float, lit: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        VSlider((db + 12f) / 24f, { f ->
            var v = f * 24f - 12f
            if (kotlin.math.abs(v) < 0.6f) v = 0f
            onChange(v)
        }, Modifier.weight(1f), lit = lit)
        SkinText(label, sc(Slot.TITLE_TEXT), 7.sp)
    }
}

// ===================================================================== VISUALIZER PANEL

@Composable
fun VisualizerPanel(
    playing: Boolean,
    modifier: Modifier = Modifier,
    demo: Boolean = false,
    bars: Int = 32,
    titleModifier: Modifier = Modifier,
    /** Set to remember a style for this visualizer; tapping it then cycles through the looks. */
    styleKey: String? = null,
) {
    val skin = LocalSkin.current
    val styles by VisStyles.styles.collectAsState()
    val style = styleKey?.let { styles[it] } ?: skin.visStyle
    SkinPanel("Visualization", modifier, titleModifier = titleModifier) {
        DisplayBox(Modifier.fillMaxWidth().weight(1f), bg = sc(Slot.VIS_BG)) {
            Visualizer(
                playing,
                Modifier.fillMaxSize().then(
                    if (styleKey != null) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { VisStyles.cycle(styleKey, style) } else Modifier
                ),
                bars = bars, demo = demo, styleOverride = style,
            )
        }
    }
}

// ===================================================================== VU METERS

/** Left / right level meters with peak-hold ticks, like an old tape deck. */
@Composable
fun VuMeters(playing: Boolean, modifier: Modifier = Modifier, demo: Boolean = false) {
    val skin = LocalSkin.current
    val label = skin.c(Slot.DISPLAY_TEXT)
    val low = skin.c(Slot.VIS_LOW)
    val high = skin.c(Slot.VIS_HIGH)
    // the "too loud" end: the skin's top colour pushed toward red
    val hot = lerpColor(high, Color(0xFFFF2A1A), 0.65f)
    val isPlaying by androidx.compose.runtime.rememberUpdatedState(playing)
    val isDemo by androidx.compose.runtime.rememberUpdatedState(demo)
    val perf by com.duodeck.player.PerfSettings.values.collectAsState()
    val fps by androidx.compose.runtime.rememberUpdatedState(perf.visFps)
    val lv = remember { FloatArray(2) }
    val pk = remember { FloatArray(2) }
    val hold = remember { FloatArray(2) }
    var tick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (fps == 0) { lv.fill(0f); pk.fill(0f); tick++ }
            if (fps == 0 || (!isPlaying && !isDemo && lv.all { it <= 0f } && pk.all { it <= 0f })) {
                androidx.compose.runtime.snapshotFlow { fps > 0 && (isPlaying || isDemo) }.first { it }
                last = 0L
            }
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val fresh = System.nanoTime() - AudioBus.lastAnalysisNanos < 250_000_000L
                val t = now / 1e9
                for (i in 0..1) {
                    val target = when {
                        isDemo && !(isPlaying && fresh) -> (0.62 + 0.25 * kotlin.math.sin(t * (4.0 + i)) * kotlin.math.sin(t * 1.3 + i)).toFloat()
                        isPlaying && fresh -> if (i == 0) AudioBus.levelL else AudioBus.levelR
                        else -> 0f
                    }
                    lv[i] = if (target > lv[i]) lv[i] + (target - lv[i]) * 0.7f else kotlin.math.max(target, lv[i] - dt * 2.6f)
                    if (lv[i] >= pk[i]) { pk[i] = lv[i]; hold[i] = 0.8f }
                    else if (hold[i] > 0) hold[i] -= dt
                    else pk[i] = kotlin.math.max(0f, pk[i] - dt * 0.6f)
                }
                tick = now
            }
            if (fps in 1..59) kotlinx.coroutines.delay((1000L / fps - 16L).coerceAtLeast(0L))
        }
    }
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_VARIABLE") val force = tick
        val labelW = size.height * 0.45f
        val rowH = size.height / 2f
        val segs = 30
        val barX = labelW + 2f
        val segW = (size.width - barX) / segs
        for (i in 0..1) {
            val y0 = i * rowH + rowH * 0.18f
            val h = rowH * 0.64f
            // little L / R letters drawn as blocks
            val lx = 1f; val ly = y0; val lw = labelW * 0.55f
            val stroke = kotlin.math.max(1.5f, h * 0.2f)
            drawRect(label, Offset(lx, ly), androidx.compose.ui.geometry.Size(stroke, h))
            if (i == 0) {
                drawRect(label, Offset(lx, ly + h - stroke), androidx.compose.ui.geometry.Size(lw, stroke))
            } else {
                drawRect(label, Offset(lx, ly), androidx.compose.ui.geometry.Size(lw, stroke))
                drawRect(label, Offset(lx + lw - stroke, ly), androidx.compose.ui.geometry.Size(stroke, h * 0.5f))
                drawRect(label, Offset(lx, ly + h * 0.45f), androidx.compose.ui.geometry.Size(lw, stroke))
                drawLine(label, Offset(lx + lw * 0.4f, ly + h * 0.5f), Offset(lx + lw, ly + h), stroke)
            }
            val lit = (lv[i] * segs).toInt()
            for (s in 0 until segs) {
                val frac = s / (segs - 1f)
                // skin colours: low -> high across the bar, then warmer at the hot end
                val meter = if (frac < 0.7f) lerpColor(low, high, frac / 0.7f)
                    else lerpColor(high, hot, (frac - 0.7f) / 0.3f)
                val c = if (s < lit) meter else meter.copy(alpha = 0.15f)
                drawRect(c, Offset(barX + s * segW, y0), androidx.compose.ui.geometry.Size(segW * 0.72f, h))
            }
        }
    }
}



/** "SLEEP 23" on the display while the sleep timer runs. */
@Composable
private fun SleepBadge() {
    val st by com.duodeck.player.SleepTimer.state.collectAsState()
    if (!st.active) return
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), st) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val label = if (st.endOfSong) "SLEEP: END OF SONG" else {
        val left = ((st.endAt - now) / 1000).coerceAtLeast(0)
        "SLEEP %d:%02d".format(left / 60, left % 60)
    }
    SkinText(label, sc(Slot.ACCENT), 8.sp)
    Spacer(Modifier.width(6.dp))
}
