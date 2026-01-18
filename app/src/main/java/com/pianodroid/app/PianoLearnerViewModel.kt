package com.pianodroid.app

import android.app.Application
import android.content.Context
import android.media.midi.MidiManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianodroid.app.audio.PitchDetector
import com.pianodroid.app.audio.SimpleSynth
import com.pianodroid.app.audio.SongScheduler
import com.pianodroid.app.data.Note
import com.pianodroid.app.data.Song
import com.pianodroid.app.grade.Grader
import com.pianodroid.app.learn.LearnGate
import com.pianodroid.app.midi.MidiInputHandler
import com.pianodroid.app.midi.MidiParser
import com.pianodroid.app.transport.Transport
import com.pianodroid.app.ui.InputMode
import com.pianodroid.app.ui.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

data class PianoLearnerUiState(
    val song: Song? = null,
    val allNotes: List<Note> = emptyList(),
    val currentTimeMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playMode: PlayMode = PlayMode.Play,
    val inputMode: InputMode = InputMode.Midi,
    val tempoMultiplier: Double = 1.0,
    val pressedKeys: Set<Int> = emptySet(),
    val statusMessage: String? = null
)

class PianoLearnerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PianoLearnerUiState())
    val uiState: StateFlow<PianoLearnerUiState> = _uiState.asStateFlow()

    private var transport: Transport? = null
    private var synth: SimpleSynth? = null
    private var scheduler: SongScheduler? = null
    private var grader: Grader? = null
    private var learnGate: LearnGate? = null
    private var midiHandler: MidiInputHandler? = null
    private var pitchDetector: PitchDetector? = null

    private val pressedKeysSet = mutableSetOf<Int>()

    init {
        initializeComponents()
    }

    private fun initializeComponents() {
        transport = Transport()
        synth = SimpleSynth()
        
        // Start audio rendering
        synth?.startRendering(viewModelScope)

        // Observe transport time
        viewModelScope.launch {
            transport?.currentTimeMs?.collect { time ->
                _uiState.value = _uiState.value.copy(currentTimeMs = time)
                
                // Update grader misses
                grader?.evaluateMisses(time)
                
                // Update learn gate
                if (_uiState.value.playMode == PlayMode.Learn) {
                    val noteStates = grader?.noteStatesFlow?.value ?: emptyMap()
                    learnGate?.onTimeUpdate(time, noteStates)
                    
                    // Pause if learn gate says so
                    if (learnGate?.isPaused?.value == true && _uiState.value.isPlaying) {
                        pause()
                    }
                }
            }
        }

        // Initialize MIDI handler if available
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val midiManager = getApplication<Application>().getSystemService(Context.MIDI_SERVICE) as? MidiManager
            if (midiManager != null) {
                midiHandler = MidiInputHandler(midiManager)
                midiHandler?.setNoteCallbacks(
                    onNoteOn = { pitch, velocity -> onNotePlayed(pitch) },
                    onNoteOff = { pitch -> onNoteReleased(pitch) }
                )
                midiHandler?.autoSelectFirstDevice()
            }
        }
    }

    fun loadMidiFile(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                val parser = MidiParser()
                val song = parser.parse(inputStream)

                _uiState.value = _uiState.value.copy(
                    song = song,
                    allNotes = song.tracks.flatMap { it.notes }
                )

                // Initialize components for this song
                transport?.seekToStart()
                grader = Grader(song)
                learnGate = LearnGate(song)
                
                scheduler?.stop()
                scheduler = SongScheduler(
                    synth!!,
                    song,
                    transport!!.currentTimeMs,
                    transport!!.tempoMultiplierState
                )

                _uiState.value = _uiState.value.copy(statusMessage = "MIDI file loaded successfully")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(statusMessage = "Error loading MIDI file: ${e.message}")
            }
        }
    }

    fun setPlayMode(mode: PlayMode) {
        _uiState.value = _uiState.value.copy(playMode = mode)
        learnGate?.reset()
    }

    fun setInputMode(mode: InputMode) {
        val wasMicrophone = _uiState.value.inputMode == InputMode.Microphone
        _uiState.value = _uiState.value.copy(inputMode = mode)

        if (mode == InputMode.Microphone && !wasMicrophone) {
            startMicrophoneInput()
        } else if (mode == InputMode.Midi && wasMicrophone) {
            stopMicrophoneInput()
        }
    }

    fun play() {
        transport?.play()
        scheduler?.start(viewModelScope)
        _uiState.value = _uiState.value.copy(isPlaying = true)
    }

    fun pause() {
        transport?.pause()
        scheduler?.stop()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToStart() {
        transport?.seekToStart()
        scheduler?.seekTo(0L, viewModelScope)
        grader?.reset()
        learnGate?.reset()
        _uiState.value = _uiState.value.copy(currentTimeMs = 0L)
    }

    fun setTempoMultiplier(multiplier: Double) {
        transport?.setTempoMultiplier(multiplier)
        _uiState.value = _uiState.value.copy(tempoMultiplier = multiplier)
    }

    private fun onNotePlayed(pitch: Int) {
        pressedKeysSet.add(pitch)
        _uiState.value = _uiState.value.copy(pressedKeys = pressedKeysSet.toSet())

        val currentTime = _uiState.value.currentTimeMs
        grader?.onNotePlayed(pitch, currentTime)

        // In learn mode, check if we can resume
        if (_uiState.value.playMode == PlayMode.Learn) {
            val noteStates = grader?.noteStatesFlow?.value ?: emptyMap()
            learnGate?.onTimeUpdate(currentTime, noteStates)
        }
    }

    private fun onNoteReleased(pitch: Int) {
        pressedKeysSet.remove(pitch)
        _uiState.value = _uiState.value.copy(pressedKeys = pressedKeysSet.toSet())
    }

    fun startMicrophoneInput() {
        pitchDetector?.stop()
        pitchDetector = PitchDetector(
            onNoteOn = { pitch -> onNotePlayed(pitch) },
            onNoteOff = { onNoteReleased(0) }  // Pitch detector doesn't track individual notes
        )
        pitchDetector?.start(viewModelScope)
    }

    fun stopMicrophoneInput() {
        pitchDetector?.stop()
        pitchDetector = null
    }

    override fun onCleared() {
        super.onCleared()
        transport?.cleanup()
        synth?.cleanup()
        scheduler?.stop()
        midiHandler?.cleanup()
        pitchDetector?.stop()
    }
}
