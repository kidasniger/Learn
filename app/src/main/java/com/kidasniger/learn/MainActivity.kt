package com.kidasniger.learn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kidasniger.learn.data.TranscriptionEntity
import com.kidasniger.learn.ui.AboutScreen
import com.kidasniger.learn.ui.HistoryScreen
import com.kidasniger.learn.ui.MainViewModel
import com.kidasniger.learn.ui.TranscribeScreen
import com.kidasniger.learn.ui.theme.WhisperLearnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember {
                getSharedPreferences("learn_preferences", MODE_PRIVATE)
            }
            var darkTheme by remember {
                mutableStateOf(preferences.getBoolean("dark_theme", false))
            }

            WhisperLearnTheme(darkTheme = darkTheme, dynamicColor = false) {
                val viewModel: MainViewModel = viewModel()
                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Mic, contentDescription = "Transcription") },
                                label = { Text("Transcription") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.History, contentDescription = "Historique") },
                                label = { Text("Historique") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Info, contentDescription = "Paramètres") },
                                label = { Text("Paramètres") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (selectedTab) {
                            0 -> TranscribeScreen(viewModel)
                            1 -> HistoryScreen(viewModel) { item: TranscriptionEntity ->
                                viewModel.loadTranscription(item)
                                selectedTab = 0
                            }
                            2 -> AboutScreen(
                                darkTheme = darkTheme,
                                onDarkThemeChanged = { enabled ->
                                    darkTheme = enabled
                                    preferences.edit().putBoolean("dark_theme", enabled).apply()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
