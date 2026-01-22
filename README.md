# PianoDroid - Project Summary

## Purpose
PianoVibe (labeled "Piano Learner" in the UI) is a web app to practice piano with interactive MIDI playback, live grading, and a readable piano‑roll. Upload a MIDI file, connect a MIDI keyboard, choose a mode, and practice with visual guidance.

## Functionality

### Core Features
1. **MIDI File Upload**: Load standard MIDI files (`.mid`/`.midi`) for practice and playback.
2. **MIDI/Microphone Input**:
   - **MIDI Device Integration**: Detects and connects to MIDI input devices via the Web MIDI API. Hot‑plugging is handled and the first device is auto‑selected when available.
   - **Microphone Mode**: Uses your device microphone (via Web Audio + getUserMedia) to detect pitch and convert it into note on/off events so you can practice without a MIDI keyboard.
3. **Modes**:
   - **Learn Mode**: Auto‑pauses at each upcoming note/chord and waits until you play the correct notes, then auto‑resumes to the next group.
   - **Play Mode**: Free‑running playback, unaffected by user input.
4. **Live Grading**: Incoming key presses are matched to scheduled notes within a small timing window and marked per note as `hit` (green) or `miss` (red).
5. **Piano Roll Visualization**: Notes “fall” toward a keyboard lane. Current/graded notes are colorized; pressed keys light up on the keyboard.
6. **Playback Controls**: Play/Pause, Seek‑to‑Start, and Tempo slider (50%–150%).

### User Experience
- Modern dark theme UI.
- Clear visual grid (time lines, octave lines) and a bottom keyboard lane with pressed‑key highlighting.
- Helpful notices for MIDI support/permission.

## Architecture

### Component Structure
1. **App** (`src/App.tsx`): Orchestrates transport, audio, scheduling, grading, learn gating, device binding, and the UI.
2. **Transport** (`src/transport.ts`): Stable timing based on `AudioContext`. Supports play/pause, seek, and a tempo multiplier.
3. **Audio**:
   - **SimpleSynth** (`src/audio/SimpleSynth.ts`): Minimal poly synth using Web Audio API oscillators + ADSR envelopes.
   - **SongScheduler** (`src/audio/SongScheduler.ts`): Look‑ahead scheduler that queues note on/off using `AudioContext` time.
4. **MIDI Processing**:
   - **parseMidi** (`src/midi/parseMidi.ts`): Minimal SMF parser (format 0/1). Handles tempo map, track names, Note On/Off, running status.
5. **Learning/Feedback**:
   - **Grader** (`src/grade/Grader.ts`): Matches played notes to scheduled notes and maintains a per‑note state map: `0=pending`, `1=hit`, `2=miss`.
   - **LearnGate** (`src/learn/LearnGate.ts`): Groups near‑simultaneous notes into chords and auto‑pauses/resumes to gate progress in Learn mode.
6. **Visualization**:
   - **PianoRollCanvas** (`src/render/PianoRollCanvas.tsx`): Canvas renderer for the piano roll, graded note coloring, and keyboard lane.

### Data Flow
1. User selects a MIDI file.
2. File is parsed into a structured `Song` object with per‑note `startMs`/`endMs` using the tempo map.
3. On Play:
   - `Transport` exposes song time in ms and tempo multiplier.
   - `SongScheduler` schedules note on/off events ahead of time.
   - `SimpleSynth` renders audio for scheduled notes.
   - `Grader` continuously evaluates user key presses against note start times and updates the note state map.
   - `LearnGate` (in Learn mode) auto‑pauses at the next note/chord until satisfied, then resumes.
   - `PianoRollCanvas` draws the windowed piano roll with graded colors and pressed‑key highlights.

## Tech Stack

### Core Technologies
- **Framework**: React 19 (`react@^19.1.1`)
- **Language**: TypeScript (~5.9)
- **Build Tool**: Vite 7

### Web APIs
- **Web Audio API**: Audio synthesis and precise timing.
- **Web MIDI API**: MIDI input integration.
- **Canvas 2D API**: Piano roll rendering.

### Development Tools
- ESLint 9
- TypeScript ESLint

## Implementation Details

### Audio & Timing
- Triangle‑wave poly synth with simple ADSR; master gain control in `SimpleSynth`.
- Look‑ahead scheduling window (~120ms) with ~25ms ticks for responsive tempo/seek changes.
- `Transport` tempo multiplier range is clamped internally (0.25×–2×). UI exposes 50%–150%.

### MIDI Parsing
- Minimal SMF support: header, multiple tracks, meta tempo (`0xFF 0x51`), track name (`0xFF 0x03`), Note On/Off, running status.
- Converts ticks to milliseconds using a tempo map; default tempo 120 BPM until a tempo event is encountered.
- SMPTE timebase is not supported.

### Visualization
- Time = vertical (future downward). Pitch = horizontal.
- Visible window defaults to ~6s of future time and resizes with the container.
- Note coloring: pending (indigo), hit (green), miss (red). Keyboard lane highlights pressed keys.

## How to Use
1. Open the app (Vite dev server or deployed build).
2. Choose a `.mid`/`.midi` file.
3. Select your MIDI input device (first device is auto‑selected when available).
4. Pick a mode: Learn or Play.
5. Press Play. Use ⏮︎ to seek to start and adjust Tempo as needed.

Notes:
- Web MIDI is supported in Chromium‑based desktops. If unsupported or permission is denied, the app shows a notice.
- Connect your keyboard before loading the page, or refresh after connecting.

## Current Status
Prototype: functional and suitable for practicing simple pieces. The synth is intentionally simple (not a realistic piano). Feature areas to expand later include sustain pedal/CC handling, richer sound, velocity curves, score/part selection, and more advanced learning flows.