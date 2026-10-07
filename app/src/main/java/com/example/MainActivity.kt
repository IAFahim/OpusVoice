package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.OpusVoiceScreen
import com.example.ui.OpusVoiceViewModel
import com.example.ui.theme.OpusVoiceTheme

class MainActivity : ComponentActivity() {

    private val viewModel: OpusVoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            OpusVoiceTheme(darkTheme = uiState.isDarkMode) {
                OpusVoiceScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // onDestroy also fires on configuration changes (rotation): tearing the stream
        // down there kills playback mid-session while the ViewModel survives. Real
        // teardown happens in the ViewModel's onCleared(); only a finishing activity
        // needs the eager stop.
        if (isFinishing) {
            viewModel.stopStreaming()
        }
    }
}
