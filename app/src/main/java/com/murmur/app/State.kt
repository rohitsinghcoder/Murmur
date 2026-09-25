package com.murmur.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
    Off, Loading, Ready, Listening, Finishing;

    /** A dictation is in progress (the bubble shows the wide pill). */
    val isActive get() = this == Listening || this == Finishing
}

data class DictationState(
    val phase: Phase = Phase.Off,
    /** Live transcript of the current dictation. */
    val partial: String = "",
    /** Recent microphone levels (0..1), newest last, for the waveform. */
    val levels: List<Float> = emptyList(),
    val error: String? = null,
    val loadMs: Long? = null,
    val lastAudioMs: Long? = null,
    /** Time from tapping stop to having the final text. */
    val lastLatencyMs: Long? = null,
)

/** App-wide dictation state shared by the service, the bubble and the main screen. */
object Murmur {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state

    fun update(change: (DictationState) -> DictationState) = _state.update(change)
}
