## PianoDroid – Android Native Project Summary

### Purpose
PianoDroid (labeled **“Piano Learner”** in the UI) is an **Android app** to practice piano with interactive MIDI playback, live grading, and a readable piano‑roll.  
You load a MIDI file, connect a MIDI keyboard *or* use the microphone, choose a mode, and practice with visual guidance.

### Functionality

#### Core Features
1. **MIDI File Load**  
   - Load standard MIDI files (`.mid` / `.midi`) from local storage via the system file picker.

2. **MIDI / Microphone Input**
   - **MIDI Device Integration** (Android MIDI API)  
     - Detects and connects to connected MIDI devices.  
     - First available device can be auto‑selected.
   - **Microphone Mode**  
     - Uses the device microphone (`AudioRecord`) and a basic pitch detector (autocorrelation) to convert audio into note on/off events so you can practice without a MIDI keyboard.

3. **Modes**
   - **Learn Mode**  
     - Auto‑pauses at each upcoming note/chord.  
     - Waits until you play the correct notes, then auto‑resumes to the next group.
   - **Play Mode**  
     - Free‑running playback, unaffected by user input; you just play along.

4. **Live Grading**
   - Incoming key presses (from MIDI or microphone) are matched to scheduled notes within a small timing window.  
   - Each note is marked as:
     - `hit` (green) or  
     - `miss` (red).

5. **Piano Roll Visualization**
   - Notes “fall” toward a bottom keyboard lane.  
   - Current/graded notes are colorized; pressed keys light up on the keyboard.

6. **Playback Controls**
   - Play / Pause  
   - Seek‑to‑Start  
   - Tempo slider (50%–150%).

#### User Experience
- **Modern dark theme** UI built with Jetpack Compose + Material 3.
- **Clear visual grid** (time lines, octave lines) and a bottom keyboard lane with pressed‑key highlighting.
- Helpful **status messaging** around permissions (microphone) and MIDI devices.

---

## Architecture

### High‑Level Modules

1. **UI Layer (Jetpack Compose)**
   - `MainActivity`  
     - Sets up the Compose UI and injects `PianoLearnerViewModel`.
   - `MainScreen`  
     - Main screen with:
       - MIDI file picker  
       - Mode selection (Learn / Play)  
       - Input mode selection (MIDI / Microphone)  
       - Piano‑roll canvas  
       - Playback controls and tempo slider  
       - Status messages.
   - `PianoRollCanvas`  
     - Composable `Canvas` that draws:
       - Time grid (vertical axis is time, scrolling downward into the future).  
       - Pitch axis (horizontal).  
       - Colored note rectangles (pending / hit / miss).  
       - Bottom keyboard lane with pressed‑key highlights.

2. **ViewModel / Orchestration**
   - `PianoLearnerViewModel`
     - Owns the main `PianoLearnerUiState` (song, notes, timing, pressed keys, modes, tempo, status).  
     - Wires together:
       - `Transport` (timing)  
       - `SimpleSynth` (audio)  
       - `SongScheduler` (note scheduling)  
       - `MidiParser` (parses MIDI into `Song`)  
       - `Grader` (hit/miss grading)  
       - `LearnGate` (Learn‑mode gating)  
       - `MidiInputHandler` (MIDI keyboard input)  
       - `PitchDetector` (microphone input).

3. **Timing**
   - `Transport`
     - Uses `Handler` + `System.currentTimeMillis()` to maintain a **song time in ms**.  
     - Exposes:
       - `currentTimeMs: StateFlow<Long>`  
       - `isPlaying` state  
       - `tempoMultiplier` (internally clamped to 0.25×–2×, UI uses 0.5×–1.5×).
     - Supports:
       - `play()` / `pause()`  
       - `seekToStart()`  
       - `setTempoMultiplier(multiplier: Double)`.

4. **Audio**
   - `SimpleSynth`
     - Minimal poly synth using **triangle waves** and a simple **ADSR** envelope per voice.  
     - Uses Android `AudioTrack` for low‑latency streaming audio.  
     - Manages:
       - `noteOn(pitch, velocity)`  
       - `noteOff(pitch)`  
       - Internal `Voice` objects with ADSR.
   - `SongScheduler`
     - Look‑ahead scheduler that:
       - Reads `currentTimeMs` and `tempoMultiplier` from `Transport`.  
       - Schedules note on/off events in a ~120ms window ahead of time.  
       - Calls `SimpleSynth.noteOn` / `noteOff` for all notes across all tracks in the current song.

5. **MIDI Processing**
   - `MidiParser`
     - Minimal **Standard MIDI File (SMF)** parser (format 0/1).  
     - Handles:
       - Header (`MThd`) and track (`MTrk`) chunks  
       - Variable‑length delta times  
       - Tempo meta events (`0xFF 0x51`)  
       - Track name meta events (`0xFF 0x03`)  
       - Channel messages for Note On/Off  
       - Running status  
       - Basic tempo map → per‑note `startMs` / `endMs`.
   - Data models:
     - `Song` (format, ticksPerQuarter, list of `Track`, tempo map)  
     - `Track` (name, list of `Note`)  
     - `Note` (pitch, `startMs`, `endMs`, velocity, `NoteState`)  
     - `TempoEvent` (tick, tempo).

6. **Learning / Feedback**
   - `Grader`
     - Maintains a per‑note `NoteState` map:
       - `Pending` (0)  
       - `Hit` (1)  
       - `Miss` (2).
     - Core logic:
       - `onNotePlayed(pitch, currentTimeMs)` – marks the closest matching note within a small timing window (~150ms) as **Hit**.  
       - `evaluateMisses(currentTimeMs)` – marks overdue notes as **Miss**.
   - `LearnGate`
     - Groups near‑simultaneous notes into **chords** based on a small ms threshold.  
     - For Learn mode, it:
       - Tracks which chord group is “current”.  
       - Requests a pause as playback approaches the group.  
       - Waits until all notes in the current group are **Hit**; then advances to the next group and allows playback to resume.

7. **Input Integration**
   - `MidiInputHandler` (Android MIDI API)
     - Enumerates available MIDI devices via `MidiManager`.  
     - Opens a device and listens for **Note On / Note Off** events.  
     - Emits callbacks wired into the `ViewModel` for grading and keyboard visualization.
   - `PitchDetector` (Microphone)
     - Uses `AudioRecord` to capture mono audio.  
     - Runs a simple autocorrelation‑based pitch detector on a background coroutine.  
     - Converts frequency → MIDI note and calls `onNoteOn` / `onNoteOff` callbacks.

---

## Data Flow (End‑to‑End)

1. **User selects a MIDI file** using the system file picker (MIME `audio/midi`).
2. `PianoLearnerViewModel.loadMidiFile`:
   - Parses the file with `MidiParser` → `Song` object with tracks and per‑note timing.
   - Flattens all notes for visualization.
   - Resets `Transport`, `Grader`, and `LearnGate`.  
   - Creates / restarts `SongScheduler` wired to the current `Song`.
3. **On Play**:
   - `Transport.play()` starts emitting `currentTimeMs`.  
   - `SongScheduler` schedules note on/off events into `SimpleSynth`.  
   - `Grader.evaluateMisses` runs continuously as time advances.  
   - In Learn mode, `LearnGate` may request a pause around each chord group until notes are played correctly.
4. **User Input (MIDI or Microphone)**:
   - Incoming note events call into the ViewModel → `onNotePlayed` / `onNoteReleased`.  
   - `Grader.onNotePlayed` updates note states (Hit / Miss) and pushes updates to the UI.  
   - Pressed keys are reflected in the keyboard lane; colored notes update in the piano roll.

---

## Tech Stack

### Core Technologies
- **Platform**: Android (minSdk ~26 for MIDI API, targetSdk 34)
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with `AndroidViewModel` + Kotlin coroutines + `StateFlow`

### Audio & MIDI
- **Audio Output**: `AudioTrack` (stream mode) for real‑time synthesis.
- **Microphone Input**: `AudioRecord` (mono, 44.1 kHz) with basic pitch detection.
- **MIDI Input**: Android MIDI API (`MidiManager`, `MidiDevice`, `MidiOutputPort`, `MidiReceiver`).

### Libraries / Dependencies
- `androidx.core:core-ktx`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-runtime-ktx`
- `androidx.compose:*` (BOM, UI, Material3, tooling)
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- Standard Android test libraries (`junit`, `androidx.test`, etc.).

---

## Running the App

### Prerequisites
- Android Studio (Giraffe or newer recommended).
- Android SDK with:
  - Compile SDK 34  
  - Min SDK 26+

### Steps
1. **Open the project**
   - In Android Studio: **File → Open…** and select the `PianoDroid` directory.
2. **Sync Gradle**
   - Android Studio should auto‑sync; if not, click **Sync Project with Gradle Files**.
3. **Run on a device / emulator**
   - Prefer a **real device** with:
     - USB‑MIDI support (for MIDI keyboards), and/or  
     - A microphone (for mic mode).  
   - Select a run configuration and click **Run**.

---

## How to Use the App

1. Launch **Piano Learner** on your Android device.
2. Tap **“Select MIDI File”** and choose a `.mid`/`.midi` file from local storage.
3. Select **Mode**:
   - **Learn Mode** for stepwise, auto‑paused practice.  
   - **Play Mode** for continuous playback.
4. Select **Input Mode**:
   - **MIDI** if you have a MIDI keyboard attached.  
   - **Microphone** if you want to practice acoustically (device listens and infers pitches).
5. Press **Play**:
   - Use the **⏮ (seek‑to‑start)** button to return to the beginning.  
   - Adjust **Tempo** with the slider (50%–150%).

Notes:
- MIDI device availability depends on your Android device and connection method (USB‑OTG, etc.).  
- Microphone mode requires the `RECORD_AUDIO` permission; the app will prompt for it when needed.

---

## Current Status

Prototype‑level Android app: functional and suitable for practicing simple pieces.  
The synth is intentionally simple (not a realistic piano). Potential future improvements:
- Sustain pedal / CC handling (MIDI control changes).
- Richer sound engine and instrument selection.
- Velocity curves and per‑instrument settings.
- Multiple track/part selection.
- More advanced learning flows and progress tracking.