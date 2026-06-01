package com.pianodroid.app.learn

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import com.pianodroid.app.data.TempoEvent
import com.pianodroid.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class LearnGateTest {
    @Test
    fun pausesBeforeChordAndResumesAfterAllNotesAreHit() {
        val left = Note(pitch = 60, startMs = 1000, endMs = 1200)
        val right = Note(pitch = 64, startMs = 1020, endMs = 1220)
        val next = Note(pitch = 67, startMs = 1600, endMs = 1800)
        val gate = LearnGate(song(left, right, next))

        assertEquals(LearnGateStatus.Waiting, gate.onTimeUpdate(800, emptyMap()))
        assertEquals(LearnGateStatus.PauseRequested, gate.onTimeUpdate(900, emptyMap()))
        assertEquals(LearnGateStatus.PauseRequested, gate.onTimeUpdate(900, mapOf(left.id to NoteState.Hit)))

        val hitChord = mapOf(left.id to NoteState.Hit, right.id to NoteState.Hit)
        assertEquals(LearnGateStatus.ResumeRequested, gate.onTimeUpdate(900, hitChord))
        assertEquals(listOf(next), gate.currentGroup.value)
    }

    @Test
    fun resetReturnsToFirstGroup() {
        val first = Note(pitch = 60, startMs = 1000, endMs = 1200)
        val second = Note(pitch = 62, startMs = 1600, endMs = 1800)
        val gate = LearnGate(song(first, second))

        gate.onTimeUpdate(900, mapOf(first.id to NoteState.Hit))
        gate.reset()

        assertEquals(listOf(first), gate.currentGroup.value)
        assertEquals(LearnGateStatus.Waiting, gate.status.value)
    }

    private fun song(vararg notes: Note): Song {
        return Song(
            format = 0,
            ticksPerQuarter = 96,
            tracks = listOf(Track(notes = notes.toList())),
            tempoMap = listOf(TempoEvent(0, 500000))
        )
    }
}
