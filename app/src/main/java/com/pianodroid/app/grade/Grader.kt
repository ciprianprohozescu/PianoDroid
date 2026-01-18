package com.pianodroid.app.grade

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Matches played notes to scheduled notes and maintains per-note state.
 * State: 0=pending, 1=hit, 2=miss
 */
class Grader(private val song: Song) {
    private val noteStates = mutableMapOf<Note, NoteState>()
    private val timingWindowMs = 150L  // Notes must be within 150ms to be considered hit

    private val _noteStatesFlow = MutableStateFlow<Map<Note, NoteState>>(emptyMap())
    val noteStatesFlow: StateFlow<Map<Note, NoteState>> = _noteStatesFlow

    init {
        // Initialize all notes as pending
        song.tracks.flatMap { it.notes }.forEach { note ->
            noteStates[note] = NoteState.Pending
            note.state = NoteState.Pending
        }
        updateFlow()
    }

    fun onNotePlayed(pitch: Int, currentTimeMs: Long) {
        val allNotes = song.tracks.flatMap { it.notes }
        
        // Find notes that should be playing at this time
        val candidateNotes = allNotes.filter { note ->
            note.pitch == pitch && 
            note.state == NoteState.Pending &&
            kotlin.math.abs(note.startMs - currentTimeMs) <= timingWindowMs
        }

        if (candidateNotes.isNotEmpty()) {
            // Mark the closest note as hit
            val closestNote = candidateNotes.minByOrNull { 
                kotlin.math.abs(it.startMs - currentTimeMs) 
            }
            
            closestNote?.let { note ->
                noteStates[note] = NoteState.Hit
                note.state = NoteState.Hit
                updateFlow()
            }
        }
    }

    fun evaluateMisses(currentTimeMs: Long) {
        val allNotes = song.tracks.flatMap { it.notes }
        
        // Mark notes as miss if they've passed their timing window without being hit
        allNotes.forEach { note ->
            if (note.state == NoteState.Pending && currentTimeMs > note.startMs + timingWindowMs) {
                noteStates[note] = NoteState.Miss
                note.state = NoteState.Miss
            }
        }
        
        updateFlow()
    }

    fun reset() {
        noteStates.clear()
        song.tracks.flatMap { it.notes }.forEach { note ->
            noteStates[note] = NoteState.Pending
            note.state = NoteState.Pending
        }
        updateFlow()
    }

    private fun updateFlow() {
        _noteStatesFlow.value = noteStates.toMap()
    }
}
