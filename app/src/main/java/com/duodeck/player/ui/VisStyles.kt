package com.duodeck.player.ui

import android.content.Context
import com.duodeck.player.skin.VisStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Each visualizer can have its own look (tap it to change). Empty = use the skin's style. */
object VisStyles {
    private val _styles = MutableStateFlow<Map<String, VisStyle>>(emptyMap())
    val styles: StateFlow<Map<String, VisStyle>> = _styles
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("vis_styles", Context.MODE_PRIVATE)
        prefs = p
        _styles.value = p.all.mapNotNull { (k, v) -> VisStyle.entries.firstOrNull { it.name == v }?.let { k to it } }.toMap()
    }

    fun cycle(key: String, current: VisStyle) {
        val next = VisStyle.entries[(current.ordinal + 1) % VisStyle.entries.size]
        _styles.value = _styles.value + (key to next)
        prefs?.edit()?.putString(key, next.name)?.apply()
    }

    fun setAll(ctx: Context, map: Map<String, VisStyle>) {
        init(ctx)
        _styles.value = map
        val e = prefs?.edit()?.clear()
        map.forEach { (k, v) -> e?.putString(k, v.name) }
        e?.apply()
    }

    fun clearAll() {
        _styles.value = emptyMap()
        prefs?.edit()?.clear()?.apply()
    }
}
