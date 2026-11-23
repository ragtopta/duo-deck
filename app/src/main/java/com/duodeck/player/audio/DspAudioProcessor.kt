package com.duodeck.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Our own audio effects stage, plugged into ExoPlayer's audio pipeline:
 *  - 10-band graphic equalizer (peaking biquad filters) with preamp
 *  - left/right balance
 *  - taps the audio to feed the spectrum analyzer / oscilloscope
 * Doing this ourselves means no microphone permission is needed for the visualizer
 * and the EQ really has 10 bands on every phone.
 */
@UnstableApi
class DspAudioProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var sampleRate = 0
    private var appliedVersion = -1

    // filter state [channel][band]
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var preampGain = 1f
    private var leftGain = 1f
    private var rightGain = 1f
    private var eqOn = true

    // analysis
    private val fftSize = 1024
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var sumL = 0.0
    private var sumR = 0.0
    private var sumN = 0
    /** Slow running average loudness (dB), so the meters bounce around it instead of sitting at max. */
    private var avgDb = -20f
    private var samplesSinceAnalysis = 0
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat() }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        AudioBus.setFormat(sampleRate, channels)
        appliedVersion = -1
        return inputAudioFormat
    }

    private fun applySettingsIfChanged() {
        val v = AudioBus.settingsVersion
        if (v == appliedVersion && filters.size == channels) return
        appliedVersion = v
        eqOn = AudioBus.eqEnabled
        preampGain = dbToGain(AudioBus.preampDb)
        val gains = AudioBus.bandGainsDb
        if (filters.size != channels) {
            filters = Array(channels) { Array(AudioBus.BANDS) { Biquad() } }
        }
        for (ch in 0 until channels) {
            for (b in 0 until AudioBus.BANDS) {
                filters[ch][b].setPeaking(AudioBus.BAND_FREQS[b], gains.getOrElse(b) { 0f }, 1.2f, sampleRate)
            }
        }
        val bal = AudioBus.balance.coerceIn(-1f, 1f)
        leftGain = if (bal > 0) 1f - bal else 1f
        rightGain = if (bal < 0) 1f + bal else 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        applySettingsIfChanged()
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val frameCount = size / (2 * channels)
        for (f in 0 until frameCount) {
            var mono = 0f
            for (ch in 0 until channels) {
                var s = inputBuffer.getShort() / 32768f
                if (eqOn) {
                    s *= preampGain
                    val chain = filters[ch]
                    for (b in chain.indices) s = chain[b].process(s)
                }
                if (channels == 2) s *= if (ch == 0) leftGain else rightGain
                s = s.coerceIn(-1f, 1f)
                val a = kotlin.math.abs(s)
                if (AudioBus.analysisEnabled) { if (ch == 0) { sumL += a * a; sumN++ } else if (ch == 1) sumR += a * a }
                mono += s
                out.putShort((s * 32767f).toInt().toShort())
            }
            feedAnalyzer(mono / channels)
        }
        // skip any odd trailing bytes
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun feedAnalyzer(sample: Float) {
        if (!AudioBus.analysisEnabled) return
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % fftSize
        samplesSinceAnalysis++
        // ~ 43 updates per second at 44.1 kHz
        if (samplesSinceAnalysis >= fftSize) {
            samplesSinceAnalysis = 0
            analyze()
        }
    }

    private fun analyze() {
        for (i in 0 until fftSize) {
            val s = ring[(ringPos + i) % fftSize]
            re[i] = s * window[i]
            im[i] = 0f
        }
        // waveform snapshot
        val wave = FloatArray(AudioBus.WAVE_POINTS)
        val step = fftSize / AudioBus.WAVE_POINTS
        for (i in wave.indices) wave[i] = ring[(ringPos + i * step) % fftSize]
        fft(re, im)

        val bars = AudioBus.SPECTRUM_BARS
        val out = FloatArray(bars)
        val nyquist = sampleRate / 2f
        val minF = 40f
        val maxF = min(16000f, nyquist)
        for (b in 0 until bars) {
            val f0 = minF * (maxF / minF).pow(b / bars.toFloat())
            val f1 = minF * (maxF / minF).pow((b + 1) / bars.toFloat())
            var i0 = (f0 / nyquist * (fftSize / 2)).toInt().coerceIn(1, fftSize / 2 - 1)
            val i1 = (f1 / nyquist * (fftSize / 2)).toInt().coerceIn(i0 + 1, fftSize / 2)
            var peak = 0f
            while (i0 < i1) {
                val mag = sqrt(re[i0] * re[i0] + im[i0] * im[i0])
                if (mag > peak) peak = mag
                i0++
            }
            val db = 20f * log10(peak / (fftSize / 4f) + 1e-6f)
            out[b] = ((db + 62f) / 58f).coerceIn(0f, 1f)
        }
        AudioBus.spectrum = out
        // VU meters: peak level since last update, on a -48..0 dB scale
        // VU meters: short-window loudness (RMS) of each channel, shown relative to the song's
        // recent average so loud, heavily mastered songs still move with the beat.
        if (sumN > 0) {
            val dbL = (10.0 * log10(sumL / sumN + 1e-12)).toFloat()
            val dbR = if (channels >= 2) (10.0 * log10(sumR / sumN + 1e-12)).toFloat() else dbL
            val now = (dbL + dbR) / 2f
            if (now > -70f) avgDb += (now - avgDb) * 0.02f   // ~1 second to settle
            fun vu(db: Float): Float {
                if (db < -60f) return 0f
                val rel = ((db - avgDb) + 11f) / 16f          // average sits around 70%
                val abs = ((db + 50f) / 50f).coerceIn(0f, 1f)  // quiet songs read lower
                return (rel * (0.55f + 0.45f * abs)).coerceIn(0f, 1f)
            }
            AudioBus.levelL = vu(dbL)
            AudioBus.levelR = vu(dbR)
        }
        sumL = 0.0; sumR = 0.0; sumN = 0
        AudioBus.waveform = wave
        AudioBus.lastAnalysisNanos = System.nanoTime()
    }

    override fun onFlush() {
        for (row in filters) for (f in row) f.reset()
    }

    override fun onReset() {
        filters = emptyArray()
        channels = 0
        sampleRate = 0
        appliedVersion = -1
    }

    private fun dbToGain(db: Float) = 10f.pow(db / 20f)

    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f
        private var bypass = true

        fun setPeaking(freq: Float, gainDb: Float, q: Float, fs: Int) {
            if (fs <= 0 || freq >= fs * 0.45f || kotlin.math.abs(gainDb) < 0.05f) {
                bypass = true; return
            }
            bypass = false
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / fs
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha / a
            b0 = ((1.0 + alpha * a) / a0).toFloat()
            b1 = ((-2.0 * cw) / a0).toFloat()
            b2 = ((1.0 - alpha * a) / a0).toFloat()
            a1 = ((-2.0 * cw) / a0).toFloat()
            a2 = ((1.0 - alpha / a) / a0).toFloat()
        }

        fun process(x: Float): Float {
            if (bypass) return x
            // transposed direct form II
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0f; z2 = 0f }
    }

    companion object {
        /** In-place iterative radix-2 FFT. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang).toFloat()
                val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                        val vr = xr * cr - xi * ci
                        val vi = xr * ci + xi * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
