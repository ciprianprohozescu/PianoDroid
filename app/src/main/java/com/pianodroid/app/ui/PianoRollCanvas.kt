package com.pianodroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import kotlin.math.min

private const val PIANO_LOW_MIDI = 21
private const val PIANO_HIGH_MIDI = 108
private const val PIANO_KEY_COUNT = 88

/**
 * Piano roll visualization with notes falling toward a keyboard lane.
 * Time = vertical (future downward), Pitch = horizontal.
 */
@Composable
fun PianoRollCanvas(
    notes: List<Note>,
    noteStates: Map<Long, NoteState>,
    currentTimeMs: Long,
    pressedKeys: Set<Int>,
    modifier: Modifier = Modifier,
    visibleWindowMs: Long = 6000L  // ~6 seconds
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Draw grid (time lines and octave lines)
        drawGrid(width, height, currentTimeMs, visibleWindowMs)

        // Draw notes
        val visibleNotes = notes.filter { note ->
            val noteStartRelative = note.startMs - currentTimeMs
            val noteEndRelative = note.endMs - currentTimeMs
            noteEndRelative > 0 && noteStartRelative < visibleWindowMs
        }

        visibleNotes.forEach { note ->
            drawNote(note, noteStates[note.id] ?: NoteState.Pending, currentTimeMs, visibleWindowMs, width, height)
        }

        // Draw keyboard lane at bottom
        drawKeyboardLane(pressedKeys, width)
    }
}

private fun DrawScope.drawGrid(
    width: Float,
    height: Float,
    currentTimeMs: Long,
    visibleWindowMs: Long
) {
    // Draw octave lines (C notes)
    val keyWidth = width / PIANO_KEY_COUNT
    val cKeys = listOf(24, 36, 48, 60, 72, 84, 96, 108)

    cKeys.forEach { midiPitch ->
        val x = (midiPitch - PIANO_LOW_MIDI) * keyWidth
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f
        )
    }

    // Draw time lines (every second)
    val secondsVisible = visibleWindowMs / 1000f
    val timeLineSpacing = height / secondsVisible

    for (i in 0 until secondsVisible.toInt() + 1) {
        val y = i * timeLineSpacing
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
    }

    // Draw current time indicator
    drawLine(
        color = Color.White.copy(alpha = 0.8f),
        start = Offset(0f, 0f),
        end = Offset(width, 0f),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawNote(
    note: Note,
    noteState: NoteState,
    currentTimeMs: Long,
    visibleWindowMs: Long,
    width: Float,
    height: Float
) {
    val noteStartRelative = note.startMs - currentTimeMs
    val noteEndRelative = note.endMs - currentTimeMs

    if (noteEndRelative <= 0 || noteStartRelative >= visibleWindowMs) return

    // Calculate position
    val secondsVisible = visibleWindowMs / 1000f
    val yStart = (noteStartRelative / 1000f / secondsVisible * height).coerceIn(0f, height)
    val yEnd = (noteEndRelative / 1000f / secondsVisible * height).coerceIn(0f, height)
    val noteHeight = yEnd - yStart

    // Calculate x position based on MIDI pitch
    val pitch = note.pitch.coerceIn(PIANO_LOW_MIDI, PIANO_HIGH_MIDI)
    val keyWidth = width / PIANO_KEY_COUNT
    val x = ((pitch - PIANO_LOW_MIDI) * keyWidth).coerceIn(0f, width)
    val noteWidth = min(keyWidth, 10f)

    // Choose color based on note state
    val color = when (noteState) {
        NoteState.Pending -> Color(0xFF, 0x4A, 0x9F)  // Indigo
        NoteState.Hit -> Color(0x4C, 0xAF, 0x50)  // Green
        NoteState.Miss -> Color(0xF4, 0x43, 0x36)  // Red
    }

    drawRect(
        color = color,
        topLeft = Offset(x, yStart),
        size = Size(noteWidth, noteHeight)
    )
}

private fun DrawScope.drawKeyboardLane(pressedKeys: Set<Int>, width: Float) {
    val laneHeight = size.height * 0.15f  // 15% of height
    val yStart = size.height - laneHeight
    val keyWidth = width / PIANO_KEY_COUNT

    // Draw keyboard background
    drawRect(
        color = Color(0x22, 0x22, 0x22),
        topLeft = Offset(0f, yStart),
        size = Size(width, laneHeight)
    )

    // Draw white and black keys
    val whiteSemitones = setOf(0, 2, 4, 5, 7, 9, 11)
    for (pitch in PIANO_LOW_MIDI..PIANO_HIGH_MIDI) {
        if (pitch % 12 in whiteSemitones) {
            val isPressed = pressedKeys.contains(pitch)
            val x = ((pitch - PIANO_LOW_MIDI) * keyWidth).coerceIn(0f, width)

            drawRect(
                color = if (isPressed) Color(0xFF, 0xE0, 0x81) else Color.White,
                topLeft = Offset(x, yStart),
                size = Size(keyWidth, laneHeight)
            )

            // Draw key border
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(x, yStart),
                end = Offset(x, yStart + laneHeight),
                strokeWidth = 1f
            )
        }
    }

    // Draw pressed keys highlight
    pressedKeys.forEach { pitch ->
        if (pitch !in PIANO_LOW_MIDI..PIANO_HIGH_MIDI) return@forEach
        val x = ((pitch - PIANO_LOW_MIDI) * keyWidth).coerceIn(0f, width)
        drawLine(
            color = Color(0xFF, 0xD7, 0x00),
            start = Offset(x, yStart),
            end = Offset(x, yStart + laneHeight),
            strokeWidth = 3f
        )
    }
}
