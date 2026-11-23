package com.duodeck.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Lets the menu ask for the laptop-mode view to be turned the other way up. */
object LaptopView {
    val flips = MutableStateFlow(0)
    fun flip() { flips.value = flips.value + 1 }
}
