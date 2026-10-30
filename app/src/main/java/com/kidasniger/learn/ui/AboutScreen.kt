package com.kidasniger.learn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kidasniger.learn.BuildConfig
import com.kidasniger.learn.updater.AppUpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: MainViewModel,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    updateViewModel: AppUpdateViewModel
) {
    val context = LocalContext.current
    val status by viewModel.statusText.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val transcriptions by viewModel.transcriptions.collectAsState()
    val modelFile = viewModel.modelFile
    val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val sizeMb = if (modelFile.exists()) modelFile.length() / (1024L * 1024L) else 0L
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val totalWords = transcriptions.sumOf { item ->
        item.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val totalChars = transcriptions.sumOf { it.text.length }
    val languagesUsed = transcriptions.map { it.language.lowercase() }.distinct().sorted()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportHistoryJson { json ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                output.write(json.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("Fichier inaccessible.")
                        }
                        snackbar.showSnackbar("Sauvegarde de l'historique terminée.")
                    } catch (error: Exception) {
                        snackbar.showSnackbar("Sauvegarde impossible : " + (error.message ?: "erreur inconnue"))
                    }
                }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                            input.readBytes().toString(Charsets.UTF_8)
                        } ?: throw IllegalStateException("Fichier inaccessible.")
                    }
                    viewModel.importHistoryJson(json) { count, errorMessage ->
                        scope.launch {
                            if (errorMessage != null) {
                                snackbar.showSnackbar(errorMessage)
                            } else {
                                snackbar.showSnackbar("$count transcription(s) restaurée(s).")
                            }
                        }
                    }
                } catch (error: Exception) {
                    snackbar.showSnackbar("Lecture du fichier impossible : " + (error.message ?: "erreur inconnue"))
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages et aide") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Learn v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Transcription vocale locale. Vos enregistrements et brouillons restent sur l'appareil.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Apparence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = themeMode == 0, onClick = { onThemeModeChange(0) }, label = { Text("Système") })
                        FilterChip(selected = themeMode == 1, onClick = { onThemeModeChange(1) }, label = { Text("Clair") },
                            leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null) })
                        FilterChip(selected = themeMode == 2, onClick = { onThemeModeChange(2) }, label = { Text("Sombre") },
                            leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null) })
                    }
                }
            }
            AppUpdateCard(updateViewModel, Modifier.fillMaxWidth())
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Statistiques", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (transcriptions.isEmpty()) {
                        Text("Enregistrez votre première transcription pour voir apparaître vos statistiques ici.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatCell("${transcriptions.size}", "transcriptions")
                            StatCell("$totalWords", "mots")
                            StatCell("$totalChars", "caractères")
                        }
                        Text("Langues utilisées : ${languagesUsed.joinToString(", ") { it.uppercase() }}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Sauvegarde de l'historique", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Exportez toutes vos transcriptions dans un fichier JSON pour les archiver, puis restaurez-les sur ce téléphone ou un autre appareil.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportBackupLauncher.launch("learn-historique.json") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Exporter")
                        }
                        OutlinedButton(onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Importer")
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostic rapide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusRow("Microphone", if (micGranted) "Autorisé" else "À autoriser", micGranted)
                    StatusRow("Moteur Whisper", if (modelLoaded) "Prêt" else "À vérifier", modelLoaded)
                    StatusRow("Fichier du modèle", if (modelFile.exists()) "$sizeMb Mo présents" else "Aucun modèle", modelFile.exists())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Stockage libre")
                            Text("${freeBytes / (1024L * 1024L)} Mo disponibles", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guide rapide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("1. Choisissez Tiny Q8 pour la vitesse ou Base Q5 pour un compromis.")
                    Text("2. Autorisez le microphone et enregistrez. Une session micro dure deux minutes au maximum.")
                    Text("3. Le texte est sauvegardé comme brouillon au fur et à mesure du traitement.")
                    Text("4. Dans l'historique, recherchez, éditez, mettez en favori et exportez vos transcriptions.")
                    Text("5. Les exports SRT/VTT utilisent les horodatages natifs du moteur dès la première transcription.")
                    Text("6. Sauvegardez régulièrement votre historique (JSON) depuis cette page.")
                }
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}
