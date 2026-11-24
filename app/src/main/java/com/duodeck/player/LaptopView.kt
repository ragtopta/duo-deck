package com.duodeck.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Lets the menu turn the laptop-mode view the other way up, or switch laptop turning off. */
object LaptopView {
    val flips = MutableStateFlow(0)
    fun flip() { flips.value = flips.value + 1 }

    /** true = turn the player sideways by itself when the Duo is folded like a laptop. */
    val auto = MutableStateFlow(true)
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("posture", android.content.Context.MODE_PRIVATE)
        auto.value = prefs!!.getBoolean("laptopAuto", true)
    }
    fun setAuto(on: Boolean) {
        auto.value = on
        prefs?.edit()?.putBoolean("laptopAuto", on)?.apply()
    }
}
