package com.duodeck.player.ui

import android.content.Context
import com.duodeck.player.skin.VisStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's own startup setup: where each section sits, whether the equalizer and playlist
 * are shown, and each visualizer's style. It is separate from skins, so it applies to every skin.
 */
object Setup {
    data class Values(
        /** true = open in "my setup" every time; false = open the way it was left. */
        val startWithMine: Boolean = false,
        val hasSaved: Boolean = false,
        /** Big visualizer on a screen that has no sections. */
        val filler: Boolean = true,
    )

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values
    private var applied = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("setup", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = prefs(ctx)
        _values.value = Values(
            startWithMine = p.getBoolean("startWithMine", false),
            hasSaved = p.contains("mySections"),
            filler = p.getBoolean("filler", true),
        )
        // Once per app launch: put "my setup" in place before the screen is built.
        if (!applied) {
            applied = true
            if (_values.value.startWithMine && _values.value.hasSaved) loadMine(ctx)
        }
    }

    // ---- what is on screen right now (remembered between launches)
    fun eqShown(ctx: Context) = prefs(ctx).getBoolean("eqShown", true)
    fun playlistShown(ctx: Context) = prefs(ctx).getBoolean("playlistShown", true)
    fun setEqShown(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("eqShown", v).apply()
    fun setPlaylistShown(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("playlistShown", v).apply()

    fun setStartWithMine(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("startWithMine", v).apply()
        _values.value = _values.value.copy(startWithMine = v)
    }

    fun setFiller(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("filler", v).apply()
        _values.value = _values.value.copy(filler = v)
    }

    /** Save what is on screen now as "my setup". */
    fun saveMine(ctx: Context, layout: SectionLayout, eq: Boolean, playlist: Boolean) {
        val styles = VisStyles.styles.value.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        prefs(ctx).edit()
            .putString("mySections", layout.encode())
            .putBoolean("myEq", eq)
            .putBoolean("myPlaylist", playlist)
            .putString("myVis", styles)
            .putBoolean("myFiller", _values.value.filler)
            .apply()
        _values.value = _values.value.copy(hasSaved = true)
    }

    /** Copy "my setup" into the live settings. Returns the layout, or null if nothing is saved. */
    fun loadMine(ctx: Context): SectionLayout? {
        val p = prefs(ctx)
        val enc = p.getString("mySections", null) ?: return null
        val layout = SectionLayout.decode(enc)
        SectionLayout.save(ctx, layout)
        val eq = p.getBoolean("myEq", true)
        val pl = p.getBoolean("myPlaylist", true)
        val filler = p.getBoolean("myFiller", true)
        p.edit().putBoolean("eqShown", eq).putBoolean("playlistShown", pl).putBoolean("filler", filler).apply()
        _values.value = _values.value.copy(filler = filler)
        val styles = (p.getString("myVis", "") ?: "").split(",").mapNotNull { e ->
            val kv = e.split("=")
            if (kv.size != 2) null else VisStyle.entries.firstOrNull { it.name == kv[1] }?.let { kv[0] to it }
        }.toMap()
        VisStyles.setAll(ctx, styles)
        return layout
    }
}
