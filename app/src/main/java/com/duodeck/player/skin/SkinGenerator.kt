package com.duodeck.player.skin

import android.graphics.Color as AColor

/**
 * "Quick theme": builds a whole matching palette from one or two picked colours,
 * so you don't have to set all 21 colours by hand.
 */
object SkinGenerator {

    private fun hsv(c: Long): FloatArray = FloatArray(3).also { AColor.colorToHSV(c.toInt(), it) }
    private fun fromHsv(h: Float, s: Float, v: Float): Long =
        AColor.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))).toLong() and 0xFFFFFFFFL

    fun generate(base: Skin, panel: Long, glow: Long): Skin {
        val p = hsv(panel)
        val g = hsv(glow)
        val panelIsDark = p[2] < 0.55f
        val light = fromHsv(p[0], p[1] * 0.7f, (p[2] + 0.28f))
        val dark = fromHsv(p[0], p[1], p[2] * 0.4f)
        val button = if (panelIsDark) fromHsv(p[0], p[1] * 0.5f, (p[2] + 0.45f).coerceAtMost(0.88f)) else fromHsv(p[0], p[1] * 0.4f, 0.97f)
        val buttonText = if (panelIsDark) fromHsv(p[0], p[1], 0.14f) else fromHsv(p[0], (p[1] + 0.5f), 0.32f)
        val displayBg = fromHsv(g[0], g[1] * 0.6f, 0.05f)
        val dim = fromHsv(g[0], g[1] * 0.8f, g[2] * 0.3f)
        val complement = fromHsv(g[0] + 50f, g[1], g[2])
        val stripe = fromHsv(p[0] + 180f, 0.55f, 0.85f)
        val colors = mapOf(
            Slot.FRAME to panel, Slot.FRAME_LIGHT to light, Slot.FRAME_DARK to dark,
            Slot.TITLE_BAR to stripe, Slot.TITLE_TEXT to if (panelIsDark) 0xFFEDEDF5L else buttonText,
            Slot.DISPLAY_BG to displayBg, Slot.DISPLAY_TEXT to glow, Slot.DISPLAY_DIM to dim,
            Slot.BUTTON to button, Slot.BUTTON_TEXT to buttonText, Slot.ACCENT to glow,
            Slot.SLIDER_TRACK to dark, Slot.SLIDER_THUMB to button,
            Slot.LIST_BG to displayBg, Slot.LIST_TEXT to glow, Slot.LIST_CURRENT to 0xFFFFFFFFL,
            Slot.LIST_SELECT_BG to fromHsv(g[0] + 180f, 0.8f, 0.6f),
            Slot.VIS_BG to displayBg, Slot.VIS_LOW to glow, Slot.VIS_HIGH to complement, Slot.VIS_PEAK to 0xFFFFFFFFL,
        )
        return base.copy(colors = colors)
    }
}
