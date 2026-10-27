package com.kidasniger.learn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("À propos & Aide") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Whisper Learn v1.0", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "Application de transcription vocale intelligente et hors-ligne utilisant les modèles Whisper, propulsée par Jetpack Compose, Material 3 et Room Database.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Comment utiliser l'application ?", style = MaterialTheme.typography.titleMedium)
                    Text("1. Téléchargez ou sélectionnez un fichier de modèle Whisper compatible (.bin) via le bouton 'Modèle (.bin)'.", style = MaterialTheme.typography.bodyMedium)
                    Text("2. Appuyez sur 'Enregistrer' pour capturer votre voix via le microphone de l'appareil.", style = MaterialTheme.typography.bodyMedium)
                    Text("3. Appuyez sur 'Arrêter' pour lancer la transcription et afficher le texte.", style = MaterialTheme.typography.bodyMedium)
                    Text("4. Sauvegardez vos transcriptions dans l'historique local pour y accéder à tout moment.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
