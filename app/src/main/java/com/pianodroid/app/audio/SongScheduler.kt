package com.pianodroid.app.audio

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Look-ahead scheduler that queues note on/off events based on song time.
 */
class SongScheduler(
    private val synth: SimpleSynth,
    private val song: Song,
    private val currentTimeMs: StateFlow<Long>,
    private val tempoMultiplier: StateFlow<Double>
) {
    private var scheduledNotes = mutableSetOf<Int>()
    private var isRunning = false
    private var lastScheduledTime = 0L

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        scheduledNotes.clear()
        lastScheduledTime = 0L

        scope.launch {
            scheduleLoop()
        }
    }

    fun stop() {
        isRunning = false
        // Release all active notes
        scheduledNotes.toList().forEach { pitch ->
            synth.noteOff(pitch)
        }
        scheduledNotes.clear()
    }

    fun seekTo(timeMs: Long, scope: CoroutineScope) {
        val wasRunning = isRunning
        stop()

        // Release all notes
        scheduledNotes.toList().forEach { pitch ->
            synth.noteOff(pitch)
        }
        scheduledNotes.clear()
        lastScheduledTime = timeMs

        if (wasRunning) {
            start(scope)
        }
    }

    private suspend fun scheduleLoop() {
        val lookAheadMs = 120L  // Look ahead 120ms
        val tickIntervalMs = 25L  // Check every 25ms

        while (isRunning) {
            val currentTime = currentTimeMs.value
            val tempoMultiplier = tempoMultiplier.value
            val scheduleUntil = currentTime + (lookAheadMs / tempoMultiplier).toLong()

            // Schedule notes that should play between lastScheduledTime and scheduleUntil
            val allNotes = song.tracks.flatMap { it.notes }

            allNotes.forEach { note ->
                val adjustedStart = (note.startMs / tempoMultiplier).toLong()
                val adjustedEnd = (note.endMs / tempoMultiplier).toLong()

                if (adjustedStart >= lastScheduledTime && adjustedStart <= scheduleUntil) {
                    if (note.pitch !in scheduledNotes) {
                        synth.noteOn(note.pitch, note.velocity)
                        scheduledNotes.add(note.pitch)
                    }
                }

                if (adjustedEnd >= lastScheduledTime && adjustedEnd <= scheduleUntil) {
                    if (note.pitch in scheduledNotes) {
                        synth.noteOff(note.pitch)
                        scheduledNotes.remove(note.pitch)
                    }
                }
            }

            lastScheduledTime = scheduleUntil
            delay(tickIntervalMs)
        }
    }
}
