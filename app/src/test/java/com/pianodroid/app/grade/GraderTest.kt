package com.pianodroid.app.grade

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import com.pianodroid.app.data.TempoEvent
import com.pianodroid.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GraderTest {
    @Test
    fun marksClosestMatchingNoteAsHit() {
        val note = Note(pitch = 60, startMs = 1000, endMs = 1200)
        val grader = Grader(song(note))

        val event = grader.onNotePlayed(60, 1080)

        assertEquals(GradeDetail.Hit, event.detail)
        assertEquals(80L, event.offsetMs)
        assertEquals(NoteState.Hit, grader.noteStatesFlow.value[note.id])
    }

    @Test
    fun distinguishesEarlyLateWrongAndMissedNotes() {
        val note = Note(pitch = 60, startMs = 1000, endMs = 1200)
        val grader = Grader(song(note))

        assertEquals(GradeDetail.Early, grader.onNotePlayed(60, 700).detail)
        assertEquals(GradeDetail.Late, grader.onNotePlayed(60, 1300).detail)
        assertEquals(GradeDetail.WrongNote, grader.onNotePlayed(61, 1000).detail)

        grader.evaluateMisses(1200)

        assertEquals(NoteState.Miss, grader.noteStatesFlow.value[note.id])
        assertEquals(GradeDetail.Miss, grader.feedbackFlow.value[note.id])
    }

    @Test
    fun resetReturnsAllNotesToPending() {
        val note = Note(pitch = 60, startMs = 1000, endMs = 1200)
        val grader = Grader(song(note))

        grader.onNotePlayed(60, 1000)
        grader.reset()

        assertEquals(NoteState.Pending, grader.noteStatesFlow.value[note.id])
        assertEquals(emptyMap<Long, GradeDetail>(), grader.feedbackFlow.value)
        assertNull(grader.lastEventFlow.value)
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
