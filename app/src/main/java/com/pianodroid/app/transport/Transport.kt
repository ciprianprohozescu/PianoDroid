package com.pianodroid.app.transport

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport controller for playback timing.
 * Manages play/pause, seek, and tempo multiplier.
 */
class Transport {
    private val handler = Handler(Looper.getMainLooper())
    private var startTime: Long = 0
    private var pausedPosition: Long = 0
    private var isPlaying = false
    private var tempoMultiplier = 1.0

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs

    private val _isPlayingState = MutableStateFlow(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState

    private val _tempoMultiplierState = MutableStateFlow(1.0)
    val tempoMultiplierState: StateFlow<Double> = _tempoMultiplierState

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                val elapsed = System.currentTimeMillis() - startTime
                val scaledElapsed = (elapsed * tempoMultiplier).toLong()
                _currentTimeMs.value = pausedPosition + scaledElapsed
                handler.postDelayed(this, 25)  // Update every 25ms
            }
        }
    }

    fun play() {
        if (!isPlaying) {
            startTime = System.currentTimeMillis()
            isPlaying = true
            _isPlayingState.value = true
            handler.post(updateRunnable)
        }
    }

    fun pause() {
        if (isPlaying) {
            val elapsed = System.currentTimeMillis() - startTime
            val scaledElapsed = (elapsed * tempoMultiplier).toLong()
            pausedPosition += scaledElapsed
            isPlaying = false
            _isPlayingState.value = false
            handler.removeCallbacks(updateRunnable)
        }
    }

    fun seekToStart() {
        val wasPlaying = isPlaying
        pause()
        pausedPosition = 0
        _currentTimeMs.value = 0L
        if (wasPlaying) {
            play()
        }
    }

    fun setTempoMultiplier(multiplier: Double) {
        val clamped = multiplier.coerceIn(0.25, 2.0)
        if (isPlaying) {
            // Adjust paused position to account for tempo change
            val elapsed = System.currentTimeMillis() - startTime
            val scaledElapsed = (elapsed * tempoMultiplier).toLong()
            pausedPosition += scaledElapsed
            startTime = System.currentTimeMillis()
        }
        tempoMultiplier = clamped
        _tempoMultiplierState.value = clamped
    }

    fun getTempoMultiplier(): Double = tempoMultiplier

    fun cleanup() {
        handler.removeCallbacks(updateRunnable)
    }
}
