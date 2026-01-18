package com.pianodroid.app.data

data class Note(
    val pitch: Int,  // MIDI note number (0-127)
    val startMs: Long,
    val endMs: Long,
    val velocity: Int = 64,
    var state: NoteState = NoteState.Pending
)

enum class NoteState {
    Pending,  // 0 - not yet played
    Hit,      // 1 - correctly played
    Miss      // 2 - missed or incorrect
}
