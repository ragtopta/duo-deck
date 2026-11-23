package com.duodeck.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stops the music after a while (the player service does the fade-out). */
object SleepTimer {
    data class State(
        /** Wall-clock time to stop at, or 0 when off. */
        val endAt: Long = 0L,
        /** Stop when the current song finishes. */
        val endOfSong: Boolean = false,
        val minutes: Int = 0,
    ) {
        val active get() = endAt > 0L || endOfSong
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun start(minutes: Int) { _state.value = State(endAt = System.currentTimeMillis() + minutes * 60_000L, minutes = minutes) }
    fun endOfSong() { _state.value = State(endOfSong = true) }
    fun cancel() { _state.value = State() }
}
