package com.pianodroid.app.learn

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Groups near-simultaneous notes into chords and auto-pauses/resumes
 * to gate progress in Learn mode.
 */
class LearnGate(private val song: Song) {
    private val chordToleranceMs = 50L  // Notes within 50ms are considered a chord
    private var currentGroupIndex = 0
    private var noteGroups: List<List<Note>> = emptyList()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _currentGroup = MutableStateFlow<List<Note>>(emptyList())
    val currentGroup: StateFlow<List<Note>> = _currentGroup

    init {
        buildNoteGroups()
    }

    private fun buildNoteGroups() {
        val allNotes = song.tracks.flatMap { it.notes }.sortedBy { it.startMs }
        val groups = mutableListOf<List<Note>>()

        if (allNotes.isEmpty()) {
            noteGroups = emptyList()
            return
        }

        var currentGroup = mutableListOf<Note>()
        var groupStartTime = allNotes[0].startMs

        allNotes.forEach { note ->
            if (note.startMs - groupStartTime <= chordToleranceMs) {
                // Add to current group
                currentGroup.add(note)
            } else {
                // Start new group
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup.toList())
                }
                currentGroup = mutableListOf(note)
                groupStartTime = note.startMs
            }
        }

        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }

        noteGroups = groups
        updateCurrentGroup()
    }

    fun onTimeUpdate(currentTimeMs: Long, noteStates: Map<Note, NoteState>) {
        if (currentGroupIndex >= noteGroups.size) {
            _isPaused.value = false
            return
        }

        val currentGroup = noteGroups[currentGroupIndex]
        val groupStartTime = currentGroup.first().startMs

        // Check if we've reached the next group
        if (currentTimeMs >= groupStartTime - 100) {  // 100ms before group starts
            // Pause and wait for user to play the correct notes
            _isPaused.value = true

            // Check if all notes in current group are hit
            val allHit = currentGroup.all { note ->
                noteStates[note] == NoteState.Hit
            }

            if (allHit) {
                // Move to next group and resume
                currentGroupIndex++
                updateCurrentGroup()
                _isPaused.value = false
            }
        } else {
            _isPaused.value = false
        }
    }

    fun reset() {
        currentGroupIndex = 0
        updateCurrentGroup()
        _isPaused.value = false
    }

    private fun updateCurrentGroup() {
        if (currentGroupIndex < noteGroups.size) {
            _currentGroup.value = noteGroups[currentGroupIndex]
        } else {
            _currentGroup.value = emptyList()
        }
    }
}
