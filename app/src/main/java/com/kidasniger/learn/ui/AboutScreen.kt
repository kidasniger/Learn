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
fun AboutScreen(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("À propos & Paramètres") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Learn 2.0", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "Application Android de transcription locale avec whisper.cpp. Les modèles et les transcriptions restent dans le stockage privé du téléphone ; la transcription elle-même fonctionne hors ligne après installation d'un modèle.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mode sombre", style = MaterialTheme.typography.titleMedium)
                        Text("Le choix est conservé sur cet appareil.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChanged)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Modèles et performances", style = MaterialTheme.typography.titleMedium)
                    Text("Tiny Q8 privilégie la vitesse et les téléphones peu puissants. Base Q5 offre un compromis. Small Q5 peut être plus précis mais consomme davantage de mémoire et de calcul.", style = MaterialTheme.typography.bodyMedium)
                    Text("Chaque modèle téléchargé est conservé séparément. Le menu Modèle permet de choisir celui qui sera chargé. Les fichiers peuvent être supprimés depuis la page Transcription après avoir sélectionné un autre modèle.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Confidentialité et limites", style = MaterialTheme.typography.titleMedium)
                    Text("Les fichiers audio importés sont décodés par blocs de 30 secondes. Les transcriptions enregistrées restent dans la base Room locale. Vous pouvez les rechercher, les classer dans des dossiers, les marquer comme favoris, les partager ou les supprimer après confirmation.", style = MaterialTheme.typography.bodyMedium)
                    Text("Les résumés, idées clés et actions sont des extractions locales par règles textuelles ; ce ne sont pas des résultats générés par une IA. La traduction libre vers n'importe quelle langue n'est pas incluse dans cette version.", style = MaterialTheme.typography.bodyMedium)
                    Text("La version de test et la version de production sont distinctes. Une publication de production exige une clé de signature privée configurée dans les secrets du dépôt GitHub.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
