package com.duodeck.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.duodeck.player.audio.AudioBus
import com.duodeck.player.skin.Skin
import com.duodeck.player.skin.Slot
import com.duodeck.player.skin.VisStyle
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Spectrum analyzer / oscilloscope. Reads live data from the audio engine.
 * demo = true animates fake data (used in the Skin Studio preview when nothing is playing).
 */
@Composable
fun Visualizer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 32,
    demo: Boolean = false,
    skinOverride: Skin? = null,
    showBackground: Boolean = true,
    styleOverride: com.duodeck.player.skin.VisStyle? = null,
) {
    val skin = skinOverride ?: LocalSkin.current
    val isPlaying by rememberUpdatedState(playing)
    val isDemo by rememberUpdatedState(demo)
    val levels = remember(bars) { FloatArray(bars) }
    val peaks = remember(bars) { FloatArray(bars) }
    val peakHold = remember(bars) { FloatArray(bars) }
    val wave = remember { FloatArray(AudioBus.WAVE_POINTS) }
    var tick by remember { mutableLongStateOf(0L) }
    val perf by com.duodeck.player.PerfSettings.values.collectAsState()
    val fps by rememberUpdatedState(perf.visFps)

    LaunchedEffect(bars) {
        var last = 0L
        while (true) {
            // Nothing playing and the bars have fallen to zero: stop animating until music starts.
            if (fps == 0) {
                // Visualizer switched off in Performance options: blank it and wait.
                levels.fill(0f); peaks.fill(0f); wave.fill(0f); tick++
            }
            if (fps == 0 || (!isPlaying && !isDemo && levels.all { it <= 0f } && peaks.all { it <= 0f })) {
                androidx.compose.runtime.snapshotFlow { fps > 0 && (isPlaying || isDemo) }.first { it }
                last = 0L
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val fresh = System.nanoTime() - AudioBus.lastAnalysisNanos < 250_000_000L
                val src = AudioBus.spectrum
                val srcWave = AudioBus.waveform
                val t = now / 1e9
                for (i in 0 until bars) {
                    val target = when {
                        isDemo && !(isPlaying && fresh) -> {
                            val x = i / bars.toDouble()
                            (0.55 + 0.35 * sin(t * 3.1 + x * 9) * sin(t * 1.7 + x * 4) - x * 0.25).toFloat().coerceIn(0.05f, 1f)
                        }
                        isPlaying && fresh -> {
                            // map requested bar count onto the 32 analysis bands
                            val pos = i * (src.size - 1f) / max(1, bars - 1)
                            val a = src[pos.toInt()]
                            val b = src[(pos.toInt() + 1).coerceAtMost(src.size - 1)]
                            a + (b - a) * (pos - pos.toInt())
                        }
                        else -> 0f
                    }
                    levels[i] = if (target > levels[i]) target else max(target, levels[i] - dt * 1.6f)
                    if (levels[i] >= peaks[i]) { peaks[i] = levels[i]; peakHold[i] = 0.4f }
                    else if (peakHold[i] > 0) peakHold[i] -= dt
                    else peaks[i] = max(0f, peaks[i] - dt * 0.5f)
                }
                for (i in wave.indices) {
                    wave[i] = when {
                        isDemo && !(isPlaying && fresh) -> (0.5 * sin(t * 6 + i / 12.0) * sin(i / 40.0 + t)).toFloat()
                        isPlaying && fresh -> srcWave.getOrElse(i) { 0f }
                        else -> wave[i] * 0.85f
                    }
                    if (kotlin.math.abs(wave[i]) < 0.001f) wave[i] = 0f
                }
                tick = now
            }
            // Frame limit: 30 or 15 times a second skips frames in between (60 = every frame).
            if (fps in 1..59) kotlinx.coroutines.delay((1000L / fps - 16L).coerceAtLeast(0L))
        }
    }

    val bg = skin.c(Slot.VIS_BG)
    val low = skin.c(Slot.VIS_LOW)
    val high = skin.c(Slot.VIS_HIGH)
    val peakColor = skin.c(Slot.VIS_PEAK)
    val style = styleOverride ?: skin.visStyle

    // own drawing layer: redrawing 60x a second must not repaint the whole screen behind it
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_VARIABLE") val force = tick
        if (showBackground) drawRect(bg)
        val w = size.width
        val h = size.height
        if (style == VisStyle.SCOPE) {
            // faint grid
            for (gx in 1..3) drawLine(low.copy(alpha = 0.15f), Offset(w * gx / 4, 0f), Offset(w * gx / 4, h), 1f)
            drawLine(low.copy(alpha = 0.25f), Offset(0f, h / 2), Offset(w, h / 2), 1f)
            val p = Path()
            for (i in wave.indices) {
                val x = w * i / (wave.size - 1)
                val y = h / 2 - wave[i].coerceIn(-1f, 1f) * h * 0.45f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, peakColor, style = Stroke(width = 2.5f))
            drawPath(p, low.copy(alpha = 0.35f), style = Stroke(width = 6f))
            return@Canvas
        }
        val slot = w / bars
        val barW = max(1f, slot * 0.78f)
        val seg = max(3f, h / 24f)
        for (i in 0 until bars) {
            val x = i * slot + (slot - barW) / 2
            val lv = levels[i]
            when (style) {
                VisStyle.BARS, VisStyle.BARS_PEAKS -> {
                    // Whole LED blocks only; the peak cap snaps to the block grid so it always sits on top.
                    val blockH = seg * 0.75f
                    val maxBlocks = ((h - blockH) / seg).toInt() + 1
                    val blocks = kotlin.math.ceil(lv * h / seg).toInt().coerceIn(0, maxBlocks)
                    for (k in 0 until blocks) {
                        val bottom = h - k * seg
                        val frac = 1f - (bottom / h)
                        drawRect(lerpColor(low, high, frac), Offset(x, bottom - blockH), Size(barW, blockH))
                    }
                    if (style == VisStyle.BARS_PEAKS && peaks[i] > 0.02f) {
                        val peakBlocks = max(blocks, kotlin.math.ceil(peaks[i] * h / seg).toInt()).coerceIn(1, maxBlocks)
                        // sits just above the top of its block, never inside it
                        val capY = h - (peakBlocks - 1) * seg - blockH - 2.5f - 1.5f
                        drawRect(peakColor, Offset(x, max(0f, capY)), Size(barW, 2.5f))
                    }
                }
                VisStyle.SOLID -> {
                    val top = h - lv * h
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(high, low), startY = 0f, endY = h),
                        Offset(x, top), Size(barW, h - top)
                    )
                    if (peaks[i] > 0.02f) drawRect(peakColor, Offset(x, h - max(peaks[i], lv) * h - 2f), Size(barW, 2f))
                }
                VisStyle.MIRROR -> {
                    val half = lv * h / 2
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(high, low, high), startY = h / 2 - half, endY = h / 2 + half + 0.1f),
                        Offset(x, h / 2 - half), Size(barW, half * 2 + 1f)
                    )
                }
                VisStyle.DOTS -> {
                    val y = h - peaks[i] * h
                    drawCircle(lerpColor(low, high, peaks[i]), barW * 0.4f, Offset(x + barW / 2, y - barW * 0.4f))
                    val y2 = h - lv * h
                    drawCircle(low.copy(alpha = 0.5f), barW * 0.25f, Offset(x + barW / 2, y2))
                }
                VisStyle.SCOPE -> Unit
            }
        }
    }
}

@Suppress("unused")
private val keepPi = PI
@Suppress("unused")
private val keepColor = Color.Unspecified
