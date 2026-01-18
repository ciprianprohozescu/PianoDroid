package com.pianodroid.app.audio

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Simple polyphonic synthesizer using triangle wave with ADSR envelope.
 */
class SimpleSynth {
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val activeVoices = mutableMapOf<Int, Voice>()
    private var masterGain = 0.3f

    init {
        initializeAudio()
    }

    private fun initializeAudio() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun noteOn(pitch: Int, velocity: Int) {
        val voice = Voice(pitch, velocity.toFloat() / 127f, sampleRate)
        activeVoices[pitch] = voice
    }

    fun noteOff(pitch: Int) {
        activeVoices[pitch]?.release()
        activeVoices.remove(pitch)
    }

    fun renderAudio() {
        val audioTrack = this.audioTrack ?: return
        val bufferSize = 1024
        val buffer = ShortArray(bufferSize * 2)  // Stereo

        while (true) {
            // Generate audio samples
            for (i in 0 until bufferSize) {
                var sample = 0f

                // Sum all active voices
                val voicesToRemove = mutableListOf<Int>()
                activeVoices.forEach { (pitch, voice) ->
                    val voiceSample = voice.nextSample()
                    if (voice.isFinished()) {
                        voicesToRemove.add(pitch)
                    } else {
                        sample += voiceSample
                    }
                }
                voicesToRemove.forEach { activeVoices.remove(it) }

                // Apply master gain and convert to 16-bit PCM
                sample *= masterGain
                val pcmValue = (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
                buffer[i * 2] = pcmValue.toShort()  // Left
                buffer[i * 2 + 1] = pcmValue.toShort()  // Right
            }

            audioTrack.write(buffer, 0, buffer.size)
        }
    }

    fun startRendering(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            renderAudio()
        }
    }

    fun cleanup() {
        activeVoices.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private class Voice(
        private val pitch: Int,
        private val velocity: Float,
        private val sampleRate: Int
    ) {
        private val frequency = 440.0 * kotlin.math.pow(2.0, (pitch - 69) / 12.0)
        private var phase = 0.0
        private val phaseIncrement = frequency / sampleRate * 2.0 * kotlin.math.PI

        private var attackTime = 0.01
        private var decayTime = 0.1
        private var sustainLevel = 0.7f
        private var releaseTime = 0.2

        private var envelopePhase = EnvelopePhase.Attack
        private var envelopeTime = 0.0
        private var envelopeValue = 0f

        fun nextSample(): Float {
            // Generate triangle wave
            val triangle = if (phase < kotlin.math.PI) {
                2.0 * phase / kotlin.math.PI - 1.0
            } else {
                3.0 - 2.0 * phase / kotlin.math.PI
            }

            phase += phaseIncrement
            if (phase >= 2.0 * kotlin.math.PI) {
                phase -= 2.0 * kotlin.math.PI
            }

            // Apply ADSR envelope
            envelopeTime += 1.0 / sampleRate
            envelopeValue = when (envelopePhase) {
                EnvelopePhase.Attack -> {
                    if (envelopeTime >= attackTime) {
                        envelopePhase = EnvelopePhase.Decay
                        envelopeTime = 0.0
                        envelopeValue
                    } else {
                        (envelopeTime / attackTime).toFloat()
                    }
                }
                EnvelopePhase.Decay -> {
                    if (envelopeTime >= decayTime) {
                        envelopePhase = EnvelopePhase.Sustain
                        sustainLevel
                    } else {
                        val t = (envelopeTime / decayTime).toFloat()
                        1f - (1f - sustainLevel) * t
                    }
                }
                EnvelopePhase.Sustain -> sustainLevel
                EnvelopePhase.Release -> {
                    if (envelopeTime >= releaseTime) {
                        0f
                    } else {
                        sustainLevel * (1f - (envelopeTime / releaseTime).toFloat())
                    }
                }
            }

            return (triangle * envelopeValue * velocity).toFloat()
        }

        fun release() {
            if (envelopePhase != EnvelopePhase.Release) {
                envelopePhase = EnvelopePhase.Release
                envelopeTime = 0.0
            }
        }

        fun isFinished(): Boolean {
            return envelopePhase == EnvelopePhase.Release && envelopeValue <= 0f
        }

        private enum class EnvelopePhase {
            Attack, Decay, Sustain, Release
        }
    }
}
