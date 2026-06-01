package com.pianodroid.app.data

import java.util.concurrent.atomic.AtomicLong

data class Note(
    val pitch: Int,  // MIDI note number (0-127)
    val startMs: Long,
    val endMs: Long,
    val velocity: Int = 64,
    val id: Long = nextId()
) {
    companion object {
        private val idCounter = AtomicLong(1L)

        fun nextId(): Long = idCounter.getAndIncrement()
    }
}

enum class NoteState {
    Pending,  // 0 - not yet played
    Hit,      // 1 - correctly played
    Miss      // 2 - missed or incorrect
}
