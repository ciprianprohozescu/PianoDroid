package com.pianodroid.app.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Pitch detection from microphone using autocorrelation.
 * Converts detected pitch to MIDI note numbers.
 */
class PitchDetector(
    private val onNoteOn: (pitch: Int) -> Unit,
    private val onNoteOff: () -> Unit
) {
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        android.media.AudioFormat.CHANNEL_IN_MONO,
        android.media.AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val fftBuffer = FloatArray(bufferSize)
    private val currentNotes = mutableSetOf<Int>()

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRunning = true

            scope.launch(Dispatchers.Default) {
                detectLoop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        currentNotes.forEach { onNoteOff() }
        currentNotes.clear()
    }

    private suspend fun detectLoop() {
        val buffer = ShortArray(bufferSize)

        while (isRunning && audioRecord != null) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (readResult > 0) {
                // Convert to float
                for (i in buffer.indices) {
                    fftBuffer[i] = buffer[i] / 32768f
                }

                // Detect pitch using autocorrelation
                val pitch = detectPitch(fftBuffer, sampleRate)

                if (pitch > 0) {
                    val midiNote = frequencyToMidi(pitch)

                    // Only trigger if note changed
                    if (midiNote !in currentNotes && midiNote > 0) {
                        currentNotes.forEach { onNoteOff() }
                        currentNotes.clear()
                        currentNotes.add(midiNote)
                        onNoteOn(midiNote)
                    }
                } else {
                    // No pitch detected
                    if (currentNotes.isNotEmpty()) {
                        currentNotes.forEach { onNoteOff() }
                        currentNotes.clear()
                        onNoteOff()
                    }
                }

                kotlinx.coroutines.delay(50)  // Update every 50ms
            }
        }
    }

    private fun detectPitch(buffer: FloatArray, sampleRate: Int): Float {
        // Simple autocorrelation pitch detection
        val minPeriod = sampleRate / 1000  // ~1000 Hz max
        val maxPeriod = sampleRate / 80    // ~80 Hz min

        var maxCorrelation = 0f
        var bestPeriod = 0

        for (period in minPeriod until min(maxPeriod, buffer.size / 2)) {
            var correlation = 0f
            for (i in 0 until min(buffer.size - period, 1024)) {
                correlation += buffer[i] * buffer[i + period]
            }

            if (correlation > maxCorrelation) {
                maxCorrelation = correlation
                bestPeriod = period
            }
        }

        return if (bestPeriod > 0 && maxCorrelation > 0.1f) {
            sampleRate.toFloat() / bestPeriod
        } else {
            0f
        }
    }

    private fun frequencyToMidi(frequency: Float): Int {
        if (frequency <= 0) return 0
        val midi = 69 + 12 * log2(frequency / 440f)
        return midi.roundToInt().coerceIn(0, 127)
    }
}
