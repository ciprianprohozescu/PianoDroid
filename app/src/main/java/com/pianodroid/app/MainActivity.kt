package com.pianodroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pianodroid.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val viewModel: PianoLearnerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

private fun darkColorScheme() = androidx.compose.material3.darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF, 0xBB, 0x86, 0xFC),
    secondary = androidx.compose.ui.graphics.Color(0xFF, 0x03, 0xDA, 0xC6),
    background = androidx.compose.ui.graphics.Color(0xFF, 0x12, 0x12, 0x12),
    surface = androidx.compose.ui.graphics.Color(0xFF, 0x1E, 0x1E, 0x1E),
    error = androidx.compose.ui.graphics.Color(0xFF, 0xCF, 0x66, 0x79)
)
