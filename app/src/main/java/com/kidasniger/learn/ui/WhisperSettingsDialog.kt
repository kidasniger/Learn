package com.kidasniger.learn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Réglages avancés du moteur : nombre de fils, beam search, température,
 * prompt initial et bascule GPU expérimentale.
 */
@Composable
fun WhisperSettingsDialog(
    current: WhisperSettings,
    onDismiss: () -> Unit,
    onApply: (WhisperSettings) -> Unit
) {
    var threads by remember { mutableStateOf(current.threads) }
    var beam by remember { mutableStateOf(current.beamSize) }
    var temperature by remember { mutableStateOf(current.temperature) }
    var prompt by remember { mutableStateOf(current.initialPrompt) }
    var useGpu by remember { mutableStateOf(current.useGpu) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Réglages du moteur") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Ces réglages influencent la vitesse et la précision de la transcription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Fils de calcul : $threads", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 6, 8).forEach { option ->
                        FilterChip(
                            selected = threads == option,
                            onClick = { threads = option },
                            label = { Text(option.toString()) }
                        )
                    }
                }

                Text("Beam search : ${if (beam == 1) "désactivé (glouton)" else "$beam hypothèses"}",
                    fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 3, 5).forEach { option ->
                        FilterChip(
                            selected = beam == option,
                            onClick = { beam = option },
                            label = { Text(if (option == 1) "Off" else option.toString()) }
                        )
                    }
                }
                Text(
                    "Le beam search améliore la précision mais ralentit la transcription.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Température : ${"%.1f".format(temperature)}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.0f, 0.2f, 0.5f).forEach { option ->
                        FilterChip(
                            selected = kotlin.math.abs(temperature - option) < 0.01f,
                            onClick = { temperature = option },
                            label = { Text("%.1f".format(option)) }
                        )
                    }
                }
                Text(
                    "0,0 est déterministe. Une température plus élevée peut aider sur un audio bruité.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(500) },
                    label = { Text("Prompt initial (facultatif)") },
                    placeholder = { Text("Ex. : vocabulaire médical, noms propres…") },
                    supportingText = { Text("${prompt.length}/500 caractères — guide le moteur sur le vocabulaire attendu.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Accélération GPU (expérimental)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Demande l'usage du processeur graphique au prochain chargement du modèle. Sans backend GPU, le CPU est utilisé.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = useGpu, onCheckedChange = { useGpu = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    WhisperSettings(
                        threads = threads,
                        beamSize = beam,
                        temperature = temperature,
                        initialPrompt = prompt.trim(),
                        useGpu = useGpu
                    )
                )
            }) { Text("Appliquer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
