package com.pianodroid.app.learn

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LearnGateStatus {
    Waiting,
    PauseRequested,
    ResumeRequested,
    Finished
}

/**
 * Groups near-simultaneous notes into chords and emits explicit playback actions.
 */
class LearnGate(private val song: Song) {
    private val chordToleranceMs = 50L
    private val preRollPauseMs = 100L
    private var currentGroupIndex = 0
    private var noteGroups: List<List<Note>> = emptyList()
    private var waitingForCurrentGroup = false

    private val _status = MutableStateFlow(LearnGateStatus.Waiting)
    val status: StateFlow<LearnGateStatus> = _status

    private val _currentGroup = MutableStateFlow<List<Note>>(emptyList())
    val currentGroup: StateFlow<List<Note>> = _currentGroup

    init {
        buildNoteGroups()
    }

    private fun buildNoteGroups() {
        val allNotes = song.tracks.flatMap { it.notes }.sortedBy { it.startMs }
        if (allNotes.isEmpty()) {
            noteGroups = emptyList()
            _status.value = LearnGateStatus.Finished
            return
        }

        val groups = mutableListOf<List<Note>>()
        var currentGroup = mutableListOf<Note>()
        var groupStartTime = allNotes.first().startMs

        allNotes.forEach { note ->
            if (note.startMs - groupStartTime <= chordToleranceMs) {
                currentGroup.add(note)
            } else {
                groups.add(currentGroup.toList())
                currentGroup = mutableListOf(note)
                groupStartTime = note.startMs
            }
        }

        groups.add(currentGroup.toList())
        noteGroups = groups
        updateCurrentGroup()
    }

    fun onTimeUpdate(currentTimeMs: Long, noteStates: Map<Long, NoteState>): LearnGateStatus {
        if (currentGroupIndex >= noteGroups.size) {
            _status.value = LearnGateStatus.Finished
            return LearnGateStatus.Finished
        }

        val currentGroup = noteGroups[currentGroupIndex]
        val allHit = currentGroup.all { note -> noteStates[note.id] == NoteState.Hit }
        if (allHit) {
            currentGroupIndex++
            waitingForCurrentGroup = false
            updateCurrentGroup()
            val status = if (currentGroupIndex >= noteGroups.size) {
                LearnGateStatus.Finished
            } else {
                LearnGateStatus.ResumeRequested
            }
            _status.value = status
            return status
        }

        val groupStartTime = currentGroup.first().startMs
        val status = if (currentTimeMs >= groupStartTime - preRollPauseMs) {
            waitingForCurrentGroup = true
            LearnGateStatus.PauseRequested
        } else if (waitingForCurrentGroup) {
            LearnGateStatus.PauseRequested
        } else {
            LearnGateStatus.Waiting
        }

        _status.value = status
        return status
    }

    fun reset() {
        currentGroupIndex = 0
        waitingForCurrentGroup = false
        updateCurrentGroup()
        _status.value = if (noteGroups.isEmpty()) LearnGateStatus.Finished else LearnGateStatus.Waiting
    }

    private fun updateCurrentGroup() {
        _currentGroup.value = noteGroups.getOrNull(currentGroupIndex).orEmpty()
    }
}
