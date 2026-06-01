package com.pianodroid.app.grade

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

enum class GradeDetail {
    Hit,
    Early,
    Late,
    WrongNote,
    Miss
}

data class GradeEvent(
    val detail: GradeDetail,
    val pitch: Int,
    val noteId: Long? = null,
    val offsetMs: Long? = null
)

/**
 * Matches played notes to scheduled notes and keeps grading state separate from note data.
 */
class Grader(private val song: Song) {
    private val allNotes = song.tracks.flatMap { it.notes }.sortedBy { it.startMs }
    private val noteStates = allNotes.associate { it.id to NoteState.Pending }.toMutableMap()
    private val feedback = mutableMapOf<Long, GradeDetail>()

    private val timingWindowMs = 150L
    private val nearbyWindowMs = 600L

    private val _noteStatesFlow = MutableStateFlow<Map<Long, NoteState>>(noteStates.toMap())
    val noteStatesFlow: StateFlow<Map<Long, NoteState>> = _noteStatesFlow

    private val _feedbackFlow = MutableStateFlow<Map<Long, GradeDetail>>(emptyMap())
    val feedbackFlow: StateFlow<Map<Long, GradeDetail>> = _feedbackFlow

    private val _lastEventFlow = MutableStateFlow<GradeEvent?>(null)
    val lastEventFlow: StateFlow<GradeEvent?> = _lastEventFlow

    fun onNotePlayed(pitch: Int, currentTimeMs: Long): GradeEvent {
        val pendingSamePitch = allNotes.filter { note ->
            note.pitch == pitch && noteStates[note.id] == NoteState.Pending
        }

        val hit = pendingSamePitch
            .filter { note -> abs(note.startMs - currentTimeMs) <= timingWindowMs }
            .minByOrNull { note -> abs(note.startMs - currentTimeMs) }

        if (hit != null) {
            noteStates[hit.id] = NoteState.Hit
            feedback[hit.id] = GradeDetail.Hit
            val event = GradeEvent(
                detail = GradeDetail.Hit,
                pitch = pitch,
                noteId = hit.id,
                offsetMs = currentTimeMs - hit.startMs
            )
            updateFlows(event)
            return event
        }

        val nearbySamePitch = pendingSamePitch
            .filter { note -> abs(note.startMs - currentTimeMs) <= nearbyWindowMs }
            .minByOrNull { note -> abs(note.startMs - currentTimeMs) }

        val event = when {
            nearbySamePitch != null && currentTimeMs < nearbySamePitch.startMs -> {
                feedback[nearbySamePitch.id] = GradeDetail.Early
                GradeEvent(GradeDetail.Early, pitch, nearbySamePitch.id, currentTimeMs - nearbySamePitch.startMs)
            }
            nearbySamePitch != null -> {
                feedback[nearbySamePitch.id] = GradeDetail.Late
                GradeEvent(GradeDetail.Late, pitch, nearbySamePitch.id, currentTimeMs - nearbySamePitch.startMs)
            }
            else -> GradeEvent(GradeDetail.WrongNote, pitch)
        }

        updateFlows(event)
        return event
    }

    fun evaluateMisses(currentTimeMs: Long) {
        var changed = false
        allNotes.forEach { note ->
            if (noteStates[note.id] == NoteState.Pending && currentTimeMs > note.startMs + timingWindowMs) {
                noteStates[note.id] = NoteState.Miss
                feedback[note.id] = GradeDetail.Miss
                changed = true
            }
        }

        if (changed) {
            _noteStatesFlow.value = noteStates.toMap()
            _feedbackFlow.value = feedback.toMap()
        }
    }

    fun reset() {
        noteStates.clear()
        noteStates.putAll(allNotes.associate { it.id to NoteState.Pending })
        feedback.clear()
        updateFlows(null)
    }

    private fun updateFlows(event: GradeEvent?) {
        _noteStatesFlow.value = noteStates.toMap()
        _feedbackFlow.value = feedback.toMap()
        _lastEventFlow.value = event
    }
}
