package com.pianodroid.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pianodroid.app.PianoLearnerViewModel
import android.net.Uri

enum class PlayMode {
    Learn,
    Play
}

enum class InputMode {
    Midi,
    Microphone
}

@Composable
fun MainScreen(viewModel: PianoLearnerViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            inputStream?.use { stream ->
                viewModel.loadMidiFile(stream)
            }
        }
    }

    // Permission launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && uiState.inputMode == InputMode.Microphone) {
            viewModel.startMicrophoneInput()
        }
    }

    LaunchedEffect(uiState.inputMode) {
        if (uiState.inputMode == InputMode.Microphone) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                viewModel.startMicrophoneInput()
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF, 0x12, 0x12, 0x12)  // Dark theme
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Piano Learner",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // File selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("audio/midi") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select MIDI File")
                }
                
                if (uiState.song != null) {
                    Text(
                        text = "Loaded",
                        color = Color.Green,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mode selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.playMode == PlayMode.Learn,
                    onClick = { viewModel.setPlayMode(PlayMode.Learn) },
                    label = { Text("Learn Mode") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.playMode == PlayMode.Play,
                    onClick = { viewModel.setPlayMode(PlayMode.Play) },
                    label = { Text("Play Mode") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Input mode selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.inputMode == InputMode.Midi,
                    onClick = { viewModel.setInputMode(InputMode.Midi) },
                    label = { Text("MIDI") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.inputMode == InputMode.Microphone,
                    onClick = { viewModel.setInputMode(InputMode.Microphone) },
                    label = { Text("Microphone") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Piano roll visualization
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (uiState.song != null) {
                    PianoRollCanvas(
                        notes = uiState.allNotes,
                        currentTimeMs = uiState.currentTimeMs,
                        pressedKeys = uiState.pressedKeys,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Select a MIDI file to begin",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.seekToStart() }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.FastRewind,
                        contentDescription = "Seek to start",
                        tint = Color.White
                    )
                }

                Button(
                    onClick = {
                        if (uiState.isPlaying) {
                            viewModel.pause()
                        } else {
                            viewModel.play()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (uiState.isPlaying) "Pause" else "Play")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tempo slider
            Column {
                Text(
                    text = "Tempo: ${(uiState.tempoMultiplier * 100).toInt()}%",
                    color = Color.White
                )
                Slider(
                    value = uiState.tempoMultiplier.toFloat(),
                    onValueChange = { viewModel.setTempoMultiplier(it.toDouble()) },
                    valueRange = 0.5f..1.5f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Status messages
            uiState.statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
