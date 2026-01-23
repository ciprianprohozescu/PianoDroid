package com.pianodroid.app.audio

/**
 * Stub synthesizer used only for note scheduling/visualization.
 * All methods are no-ops so the app produces no audio.
 */
class SimpleSynth {

    fun noteOn(pitch: Int, velocity: Int) {
        // Audio rendering disabled: no-op
    }

    fun noteOff(pitch: Int) {
        // Audio rendering disabled: no-op
    }

    fun cleanup() {
        // Nothing to clean up when audio is disabled
    }
}
