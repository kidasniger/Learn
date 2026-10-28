package com.kidasniger.learn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
            val prefs = remember { getSharedPreferences("learn_preferences", MODE_PRIVATE) }
            var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }
            var showWelcome by remember { mutableStateOf(!prefs.getBoolean("welcome_completed", false)) }
            val darkTheme = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            WhisperLearnTheme(darkTheme = darkTheme, dynamicColor = themeMode == 0) {
                val viewModel: MainViewModel = viewModel()
                var selectedTab by remember { mutableIntStateOf(0) }
                Scaffold(modifier = Modifier.fillMaxSize(), bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Mic, contentDescription = "Transcription") }, label = { Text("Transcrire") })
                        NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.History, contentDescription = "Historique") }, label = { Text("Historique") })
                        NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Info, contentDescription = "Paramètres et aide") }, label = { Text("Réglages") })
                    }
                }) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                        when (selectedTab) {
                            0 -> TranscribeScreen(viewModel)
                            1 -> HistoryScreen(viewModel)
                            else -> AboutScreen(viewModel, themeMode) { mode ->
                                themeMode = mode
                                prefs.edit().putInt("theme_mode", mode).apply()
                            }
                        }
                    }
                }
                if (showWelcome) AlertDialog(
                    onDismissRequest = {
                        showWelcome = false
                        prefs.edit().putBoolean("welcome_completed", true).apply()
                    },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    title = { Text("Bienvenue dans Learn") },
                    text = { Text("Transcrivez votre voix sur ce téléphone. Autorisez le microphone, installez un modèle Whisper léger, puis appuyez sur Enregistrer. Vos brouillons sont conservés localement.") },
                    confirmButton = {
                        Button(onClick = {
                            showWelcome = false
                            prefs.edit().putBoolean("welcome_completed", true).apply()
                        }) { Text("Commencer") }
                    }
                )
            }
        }
    }
}
