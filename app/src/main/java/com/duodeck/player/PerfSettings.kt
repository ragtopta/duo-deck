package com.duodeck.player

import android.content.Context
import com.duodeck.player.audio.AudioBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Options for trading eye candy for speed and battery. Saved on the device. */
object PerfSettings {

    data class Values(
        /** Visualizer frames per second: 60, 30, 15, or 0 = off. */
        val visFps: Int = 30,
        /** Little spectrum under the clock. */
        val miniVis: Boolean = true,
        /** Song title scrolls across the display. */
        val scrollTitle: Boolean = true,
        /** Patterned panel textures (carbon, dots, brushed metal...). */
        val textures: Boolean = true,
        /** Glossy highlights and metal shading. */
        val gloss: Boolean = true,
        /** Keep the screen on while music plays: 0 = never, 1 = only when plugged in, 2 = always. */
        val keepAwake: Int = 2,
    )

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var uiVisible = true

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("performance", Context.MODE_PRIVATE)
        prefs = p
        _values.value = Values(
            visFps = p.getInt("visFps", 30).takeIf { it in listOf(0, 15, 30, 60) } ?: 30,
            miniVis = p.getBoolean("miniVis", true),
            scrollTitle = p.getBoolean("scrollTitle", true),
            textures = p.getBoolean("textures", true),
            gloss = p.getBoolean("gloss", true),
            keepAwake = p.getInt("keepAwake2", 2).coerceIn(0, 2),
        )
        applyAnalysis()
    }

    fun update(change: (Values) -> Values) {
        val v = change(_values.value)
        _values.value = v
        prefs?.edit()
            ?.putInt("visFps", v.visFps)
            ?.putBoolean("miniVis", v.miniVis)
            ?.putBoolean("scrollTitle", v.scrollTitle)
            ?.putBoolean("textures", v.textures)
            ?.putBoolean("gloss", v.gloss)
            ?.putInt("keepAwake2", v.keepAwake)
            ?.apply()
        applyAnalysis()
    }

    /** Called when the app goes on/off screen: no point analysing sound nobody can see. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        applyAnalysis()
    }

    private fun applyAnalysis() {
        AudioBus.analysisEnabled = uiVisible && _values.value.visFps > 0
    }
}
