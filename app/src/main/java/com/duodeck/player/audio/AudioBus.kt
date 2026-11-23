package com.duodeck.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared, in-process bridge between the playback engine (running in the
 * PlaybackService) and the UI. Holds the equalizer settings the DSP reads,
 * and the spectrum / waveform data the visualizer draws.
 */
object AudioBus {
    const val BANDS = 10
    val BAND_FREQS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

    // ---- Equalizer settings (written by UI, read by audio thread) ----
    @Volatile var eqEnabled: Boolean = true
    @Volatile var preampDb: Float = 0f
    @Volatile var bandGainsDb: FloatArray = FloatArray(BANDS)
    /** -1 = full left, 0 = centre, 1 = full right */
    @Volatile var balance: Float = 0f
    @Volatile var settingsVersion: Int = 0

    fun updateEq(enabled: Boolean, preamp: Float, gains: FloatArray, bal: Float) {
        eqEnabled = enabled
        preampDb = preamp
        bandGainsDb = gains.copyOf()
        balance = bal
        settingsVersion++
    }

    // ---- Analysis output (written by audio thread, read by UI) ----
    const val SPECTRUM_BARS = 32
    const val WAVE_POINTS = 256
    @Volatile var spectrum: FloatArray = FloatArray(SPECTRUM_BARS)
    @Volatile var waveform: FloatArray = FloatArray(WAVE_POINTS)
    @Volatile var lastAnalysisNanos: Long = 0L
    /** Left / right loudness 0..1 for the VU meters. */
    @Volatile var levelL: Float = 0f
    @Volatile var levelR: Float = 0f
    /** Off when the app is in the background or the visualizer is turned off, to save battery. */
    @Volatile var analysisEnabled: Boolean = true

    // ---- Stream format ----
    private val _format = MutableStateFlow(StreamFormat(0, 0))
    val format: StateFlow<StreamFormat> = _format

    fun setFormat(sampleRate: Int, channels: Int) {
        _format.value = StreamFormat(sampleRate, channels)
    }
}

data class StreamFormat(val sampleRate: Int, val channels: Int)
