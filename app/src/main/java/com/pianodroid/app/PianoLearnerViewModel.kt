package com.pianodroid.app

import android.app.Application
import android.content.Context
import android.media.midi.MidiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianodroid.app.audio.PitchDetector
import com.pianodroid.app.data.Note
import com.pianodroid.app.data.NoteState
import com.pianodroid.app.data.Song
import com.pianodroid.app.grade.GradeDetail
import com.pianodroid.app.grade.Grader
import com.pianodroid.app.learn.LearnGate
import com.pianodroid.app.learn.LearnGateStatus
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
    val noteStates: Map<Long, NoteState> = emptyMap(),
    val noteFeedback: Map<Long, GradeDetail> = emptyMap(),
    val midiDevices: List<String> = emptyList(),
    val statusMessage: String? = null
)

class PianoLearnerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PianoLearnerUiState())
    val uiState: StateFlow<PianoLearnerUiState> = _uiState.asStateFlow()

    private var transport: Transport? = null
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

        // Observe transport time
        viewModelScope.launch {
            transport?.currentTimeMs?.collect { time ->
                _uiState.value = _uiState.value.copy(currentTimeMs = time)
                
                // Update grader misses
                grader?.evaluateMisses(time)
                
                // Update learn gate
                if (_uiState.value.playMode == PlayMode.Learn) {
                    val noteStates = grader?.noteStatesFlow?.value ?: emptyMap()
                    handleLearnGateStatus(learnGate?.onTimeUpdate(time, noteStates))
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
                midiHandler?.setDeviceChangeCallback { refreshMidiDevices() }
                refreshMidiDevices()
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
                    allNotes = song.tracks.flatMap { it.notes },
                    noteStates = emptyMap(),
                    noteFeedback = emptyMap()
                )

                // Initialize components for this song
                transport?.seekToStart()
                grader = Grader(song)
                learnGate = LearnGate(song)
                observeGrader(grader!!)

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
        _uiState.value = _uiState.value.copy(isPlaying = true)
    }

    fun pause() {
        transport?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToStart() {
        transport?.seekToStart()
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
            handleLearnGateStatus(learnGate?.onTimeUpdate(currentTime, noteStates))
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
            onNoteOff = { pitch -> onNoteReleased(pitch) }
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
        midiHandler?.cleanup()
        pitchDetector?.stop()
    }

    fun onMicrophonePermissionDenied() {
        stopMicrophoneInput()
        _uiState.value = _uiState.value.copy(
            inputMode = InputMode.Midi,
            statusMessage = "Microphone permission denied. MIDI input selected."
        )
    }

    private fun observeGrader(grader: Grader) {
        viewModelScope.launch {
            grader.noteStatesFlow.collect { states ->
                _uiState.value = _uiState.value.copy(noteStates = states)
            }
        }
        viewModelScope.launch {
            grader.feedbackFlow.collect { feedback ->
                _uiState.value = _uiState.value.copy(noteFeedback = feedback)
            }
        }
    }

    private fun handleLearnGateStatus(status: LearnGateStatus?) {
        when (status) {
            LearnGateStatus.PauseRequested -> {
                if (_uiState.value.isPlaying) {
                    pause()
                }
            }
            LearnGateStatus.ResumeRequested -> {
                if (!_uiState.value.isPlaying) {
                    play()
                }
            }
            LearnGateStatus.Finished -> Unit
            LearnGateStatus.Waiting -> Unit
            null -> Unit
        }
    }

    private fun refreshMidiDevices() {
        val devices = midiHandler?.getAvailableDeviceNames().orEmpty()
        _uiState.value = _uiState.value.copy(
            midiDevices = devices,
            statusMessage = if (devices.isEmpty()) {
                "No MIDI devices connected"
            } else {
                "MIDI device ready: ${devices.first()}"
            }
        )
    }
}
