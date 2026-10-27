package com.kidasniger.learn.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kidasniger.learn.data.TranscriptSegment
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000
private const val MAX_RECORDING_SECONDS = 5 * 60
private const val MAX_AUDIO_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val statusText by viewModel.statusText.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val currentSegments by viewModel.currentSegments.collectAsState()
    val modelNames by viewModel.availableModels.collectAsState()
    val selectedModel by viewModel.selectedModelName.collectAsState()
    val language by viewModel.language.collectAsState()
    val highAccuracy by viewModel.highAccuracy.collectAsState()
    val personalNotes by viewModel.personalNotes.collectAsState()
    val analysisText by viewModel.analysisText.collectAsState()
    val translatedText by viewModel.translatedText.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    var recording by remember { mutableStateOf(false) }
    var recordingPaused by remember { mutableStateOf(false) }
    var saveDialogShown by remember { mutableStateOf(false) }
    var saveTitle by remember { mutableStateOf("") }
    var saveFolder by remember { mutableStateOf("Général") }
    var saveNotes by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var translationMenuExpanded by remember { mutableStateOf(false) }
    var translationTarget by remember { mutableStateOf("en") }
    var showSubtitles by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showModelDeleteConfirmation by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var recorderInstance by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val recordingFinalized = remember { AtomicBoolean(true) }
    val pausedFlag = remember { AtomicBoolean(false) }
    val chunks = remember { Collections.synchronizedList(ArrayList<ShortArray>()) }

    
    val modelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri, context)
    }
    val audioImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.transcribeAudioFile(uri, context)
    }
    val txtExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) writeExport(uri, "txt", viewModel, context, currentText, currentSegments, scope, snackbarHostState)
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) writeExport(uri, "pdf", viewModel, context, currentText, currentSegments, scope, snackbarHostState)
    }
    val srtExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) writeExport(uri, "srt", viewModel, context, currentText, currentSegments, scope, snackbarHostState)
    }
    val vttExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        if (uri != null) writeExport(uri, "vtt", viewModel, context, currentText, currentSegments, scope, snackbarHostState)
    }

    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) viewModel.setStatus("Synthèse vocale indisponible sur cet appareil.")
        }
        speechEngine = engine
        onDispose {
            try { engine.stop() } catch (_: Exception) { }
            engine.shutdown()
            speechEngine = null
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recordingFinalized.set(false)
            pausedFlag.set(false)
            recordingPaused = false
            recording = true
            chunks.clear()
            startRec(
                viewModel,
                { recorderInstance = it },
                { recordingThread = it },
                chunks,
                recordingFinalized,
                pausedFlag
            ) { recording = it }
        } else {
            viewModel.setStatus("Permission microphone refusée.")
        }
    }

    fun startRecordingNow() {
        if (isBusy || recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            recordingFinalized.set(false)
            pausedFlag.set(false)
            recordingPaused = false
            recording = true
            chunks.clear()
            startRec(
                viewModel,
                { recorderInstance = it },
                { recordingThread = it },
                chunks,
                recordingFinalized,
                pausedFlag
            ) { recording = it }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }



    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Learn 2.0") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { viewModel.runDiagnostics(context) }, enabled = !isBusy && !recording) {
                        Text("Diagnostic")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Moteur et modèle", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { modelMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isBusy && !recording
                            ) {
                                Text(
                                    if (selectedModel.isBlank()) "Choisir un modèle"
                                    else "Modèle : ${selectedModel.take(24)}",
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                modelNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text((if (name == selectedModel) "✓ " else "") + viewModel.displayModelName(name)) },
                                        onClick = {
                                            modelMenuExpanded = false
                                            viewModel.selectModel(name)
                                        }
                                    )
                                }
                                if (modelNames.isNotEmpty()) HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Télécharger Tiny Q8 — rapide (~42 Mo)") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        viewModel.downloadModel("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin?download=true")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Télécharger Base Q5 — équilibré (~57 Mo)") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        viewModel.downloadModel("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Télécharger Small Q5 — plus lourd (~181 Mo)") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        viewModel.downloadModel("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Importer un modèle .bin") },
                                    onClick = {
                                        modelMenuExpanded = false
                                        modelImportLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Langue", modifier = Modifier.padding(end = 8.dp))
                        Box {
                            OutlinedButton(onClick = { languageMenuExpanded = true }, enabled = !isBusy && !recording) {
                                Text(languageLabel(language))
                            }
                            DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
                                listOf(
                                    "auto" to "Détection automatique",
                                    "fr" to "Français",
                                    "en" to "Anglais",
                                    "es" to "Espagnol",
                                    "de" to "Allemand",
                                    "pt" to "Portugais",
                                    "ar" to "Arabe",
                                    "ha" to "Haoussa",
                                    "sw" to "Swahili"
                                ).forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        languageMenuExpanded = false
                                        viewModel.setLanguage(code)
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mode haute précision", fontWeight = FontWeight.Medium)
                            Text("Plus lent que le mode rapide.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = highAccuracy,
                            onCheckedChange = viewModel::setHighAccuracy,
                            enabled = !isBusy && !recording
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { startRecordingNow() },
                    modifier = Modifier.weight(1f),
                    enabled = modelLoaded && !isBusy && !recording
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Enregistrer")
                }
                Button(
                    onClick = {
                        if (recording && recordingFinalized.compareAndSet(false, true)) {
                            pausedFlag.set(false)
                            recordingPaused = false
                            recording = false
                            stopAndTranscribe(recorderInstance, recordingThread, chunks, viewModel) {
                                recorderInstance = null
                                recordingThread = null
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = recording
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Arrêter")
                }
            }

            if (recording) {
                OutlinedButton(
                    onClick = {
                        recordingPaused = !recordingPaused
                        pausedFlag.set(recordingPaused)
                        viewModel.setStatus(
                            if (recordingPaused) "Enregistrement en pause : les nouveaux échantillons ne sont pas conservés."
                            else "Enregistrement repris."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (recordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (recordingPaused) "Reprendre" else "Mettre en pause")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { audioImportLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = modelLoaded && !isBusy && !recording
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Importer audio")
                }
                OutlinedButton(
                    onClick = { viewModel.runDiagnostics(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy && !recording
                ) {
                    Text("Vérifier le téléphone")
                }
            }
            OutlinedButton(
                onClick = { viewModel.runDemoTranscription(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = modelLoaded && !isBusy && !recording
            ) {
                Text("Tester le moteur avec l'audio de démonstration (anglais, Internet requis)")
            }

            Text(
                "Les fichiers audio longs sont décodés et transcrits par blocs de 30 secondes. Le texte apparaît au fur et à mesure du traitement de ces blocs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transcription", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSubtitles = !showSubtitles }, enabled = currentText.isNotBlank()) {
                    Icon(Icons.Default.Subtitles, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (showSubtitles) "Texte modifiable" else "Sous-titres")
                }
            }
            if (showSubtitles) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    if (currentSegments.isEmpty()) {
                        Text(currentText, modifier = Modifier.padding(12.dp))
                    } else {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            currentSegments.forEach { segment ->
                                Column {
                                    Text(formatTimestamp(segment.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(segment.text)
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = currentText,
                    onValueChange = viewModel::setCurrentText,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    minLines = 7,
                    label = { Text("Modifiez le texte si nécessaire") },
                    placeholder = { Text("Le texte transcrit apparaîtra ici...") },
                    enabled = !isBusy && !recording
                )
            }

            if (currentText.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            saveTitle = ""
                            saveFolder = "Général"
                            saveNotes = personalNotes
                            saveDialogShown = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy && !recording
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Sauvegarder")
                    }
                    OutlinedButton(
                        onClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("Learn transcript", currentText))
                            scope.launch { snackbarHostState.showSnackbar("Texte copié.") }
                        },
                        enabled = !isBusy && !recording
                    ) { Text("Copier") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transcription Learn")
                                putExtra(Intent.EXTRA_TEXT, currentText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Partager la transcription"))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy && !recording
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Partager")
                    }
                    OutlinedButton(
                        onClick = {
                            val speech = currentText.take(TextToSpeech.getMaxSpeechInputLength())
                            val result = speechEngine?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "learn-transcript")
                            if (result == null || result == TextToSpeech.ERROR) {
                                viewModel.setStatus("La lecture vocale n'est pas disponible.")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy && !recording
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Lire à voix haute")
                    }
                }

                var exportMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { exportMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Exporter : TXT, PDF, SRT ou VTT")
                    }
                    DropdownMenu(expanded = exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Texte brut (.txt)") }, onClick = {
                            exportMenuExpanded = false
                            txtExportLauncher.launch("learn-transcription.txt")
                        })
                        DropdownMenuItem(text = { Text("Document PDF (.pdf)") }, onClick = {
                            exportMenuExpanded = false
                            pdfExportLauncher.launch("learn-transcription.pdf")
                        })
                        DropdownMenuItem(text = { Text("Sous-titres SubRip (.srt)") }, onClick = {
                            exportMenuExpanded = false
                            srtExportLauncher.launch("learn-transcription.srt")
                        })
                        DropdownMenuItem(text = { Text("Sous-titres WebVTT (.vtt)") }, onClick = {
                            exportMenuExpanded = false
                            vttExportLauncher.launch("learn-transcription.vtt")
                        })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { viewModel.analyzeCurrent("summary") }, modifier = Modifier.weight(1f), enabled = !isBusy) {
                        Text("Résumé")
                    }
                    OutlinedButton(onClick = { viewModel.analyzeCurrent("ideas") }, modifier = Modifier.weight(1f), enabled = !isBusy) {
                        Text("Idées clés")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { viewModel.analyzeCurrent("tasks") }, modifier = Modifier.weight(1f), enabled = !isBusy) {
                        Text("Actions")
                    }
                    OutlinedButton(onClick = { viewModel.analyzeCurrent("flashcards") }, modifier = Modifier.weight(1f), enabled = !isBusy) {
                        Text("Fiches de révision")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { translationMenuExpanded = true },
                            enabled = !isBusy && !recording
                        ) {
                            Text("Vers : ${translationLabel(translationTarget)}")
                        }
                        DropdownMenu(
                            expanded = translationMenuExpanded,
                            onDismissRequest = { translationMenuExpanded = false }
                        ) {
                            listOf(
                                "ar" to "Arabe",
                                "de" to "Allemand",
                                "en" to "Anglais",
                                "es" to "Espagnol",
                                "fr" to "Français",
                                "hi" to "Hindi",
                                "id" to "Indonésien",
                                "it" to "Italien",
                                "ja" to "Japonais",
                                "ko" to "Coréen",
                                "nl" to "Néerlandais",
                                "pl" to "Polonais",
                                "pt" to "Portugais",
                                "ru" to "Russe",
                                "th" to "Thaï",
                                "tr" to "Turc",
                                "uk" to "Ukrainien",
                                "vi" to "Vietnamien",
                                "zh" to "Chinois"
                            ).forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        translationTarget = code
                                        translationMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { viewModel.translateCurrentText(translationTarget) },
                        enabled = !isBusy && !recording && currentText.isNotBlank()
                    ) {
                        Text("Traduire")
                    }
                }
                Text(
                    "Le modèle de traduction peut être téléchargé au premier usage. Ensuite, la traduction utilise ce modèle local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (translatedText.isNotBlank()) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Traduction", style = MaterialTheme.typography.titleMedium)
                            Text(translatedText)
                            TextButton(onClick = {
                                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("Learn translation", translatedText))
                                scope.launch { snackbarHostState.showSnackbar("Traduction copiée.") }
                            }) { Text("Copier la traduction") }
                        }
                    }
                }

                if (analysisText.isNotBlank()) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(analysisText, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Analyse locale indicative : les actions et idées sont repérées par règles textuelles, sans modèle IA.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { showNotes = !showNotes }) {
                                Text(if (showNotes) "Masquer mes notes" else "Ajouter des commentaires personnels")
                            }
                            if (showNotes) {
                                OutlinedTextField(
                                    value = personalNotes,
                                    onValueChange = viewModel::setPersonalNotes,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Mes notes") },
                                    minLines = 3,
                                    enabled = !isBusy
                                )
                            }
                        }
                    }
                }
            }

            if (modelNames.size > 1) {
                Text(
                    "Modèles conservés : ${modelNames.size}. Choisissez celui à charger dans le menu « Modèle ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        modelToDelete = modelNames.firstOrNull { it != selectedModel && it != "model.bin" }.orEmpty()
                        showModelDeleteConfirmation = modelToDelete.isNotBlank()
                    },
                    enabled = !isBusy && !recording && modelNames.any { it != selectedModel && it != "model.bin" }
                ) { Text("Supprimer le modèle sélectionné") }
            }
        }
    }

    if (showModelDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showModelDeleteConfirmation = false },
            title = { Text("Supprimer ce modèle ?") },
            text = { Text("Le fichier du modèle « $modelToDelete » sera supprimé du stockage local. Cette action ne supprime pas les transcriptions.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloadedModel(modelToDelete)
                    showModelDeleteConfirmation = false
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showModelDeleteConfirmation = false }) { Text("Annuler") } }
        )
    }

    if (saveDialogShown) {
        AlertDialog(
            onDismissRequest = { saveDialogShown = false },
            title = { Text("Enregistrer dans l'historique") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = saveTitle,
                        onValueChange = { saveTitle = it },
                        label = { Text("Titre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = saveFolder,
                        onValueChange = { saveFolder = it },
                        label = { Text("Dossier / cours / réunion") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = saveNotes,
                        onValueChange = { saveNotes = it },
                        label = { Text("Commentaire personnel (facultatif)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCurrentTranscription(saveTitle, saveFolder, saveNotes)
                    saveDialogShown = false
                    saveTitle = ""
                    scope.launch { snackbarHostState.showSnackbar("Transcription sauvegardée localement.") }
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogShown = false }) { Text("Annuler") }
            }
        )
    }
}

private fun writeExport(
    uri: android.net.Uri,
    format: String,
    viewModel: MainViewModel,
    context: android.content.Context,
    text: String,
    segments: List<TranscriptSegment>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    try {
        val exportSegments = viewModel.currentExportSegments().ifEmpty {
            if (text.isBlank()) emptyList() else listOf(TranscriptSegment(0, 1_000, text))
        }
        TranscriptExporter.write(
            context = context,
            uri = uri,
            format = format,
            title = "Transcription Learn",
            text = text,
            segments = exportSegments
        )
        scope.launch { snackbarHostState.showSnackbar("Export $format terminé.") }
    } catch (error: Exception) {
        viewModel.setStatus("Export impossible : ${error.message ?: "erreur d'écriture"}.")
        scope.launch { snackbarHostState.showSnackbar("Échec de l'export.") }
    }
}

private fun translationLabel(code: String): String = when (code) {
    "ar" -> "Arabe"
    "de" -> "Allemand"
    "en" -> "Anglais"
    "es" -> "Espagnol"
    "fr" -> "Français"
    "hi" -> "Hindi"
    "id" -> "Indonésien"
    "it" -> "Italien"
    "ja" -> "Japonais"
    "ko" -> "Coréen"
    "nl" -> "Néerlandais"
    "pl" -> "Polonais"
    "pt" -> "Portugais"
    "ru" -> "Russe"
    "th" -> "Thaï"
    "tr" -> "Turc"
    "uk" -> "Ukrainien"
    "vi" -> "Vietnamien"
    "zh" -> "Chinois"
    else -> code
}

private fun languageLabel(code: String): String = when (code) {
    "auto" -> "Auto"
    "fr" -> "Français"
    "en" -> "Anglais"
    "es" -> "Espagnol"
    "de" -> "Allemand"
    "pt" -> "Portugais"
    "ar" -> "Arabe"
    "ha" -> "Haoussa"
    "sw" -> "Swahili"
    else -> code
}

private fun formatTimestamp(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}


private fun startRec(
    viewModel: MainViewModel,
    setRecorder: (AudioRecord?) -> Unit,
    setThread: (Thread?) -> Unit,
    chunks: MutableList<ShortArray>,
    recordingFinalized: AtomicBoolean,
    paused: AtomicBoolean,
    setState: (Boolean) -> Unit
) {
    val minimumBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    if (minimumBuffer <= 0) {
        viewModel.setStatus("Le microphone ne prend pas en charge ce format audio.")
        recordingFinalized.set(true)
        setState(false)
        return
    }

    val recorder = try {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimumBuffer * 2
        )
    } catch (error: Exception) {
        viewModel.setStatus("Impossible d'initialiser le microphone : ${error.message ?: "erreur inconnue"}")
        recordingFinalized.set(true)
        setState(false)
        return
    }

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        viewModel.setStatus("Le microphone n'a pas pu être initialisé.")
        recordingFinalized.set(true)
        setState(false)
        return
    }

    try {
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("L'enregistrement n'a pas démarré.")
        }
        setRecorder(recorder)
        viewModel.setStatus("Enregistrement en cours. Limite : 5 minutes par session.")

        val threadHolder = ThreadReference()
        val thread = Thread({
            val buffer = ShortArray(4096)
            var totalSamples = 0
            var maximumReached = false

            while (!recordingFinalized.get()) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    if (paused.get()) continue
                    val remaining = MAX_AUDIO_SAMPLES - totalSamples
                    if (remaining <= 0) {
                        maximumReached = true
                        break
                    }
                    val accepted = minOf(count, remaining)
                    synchronized(chunks) { chunks.add(buffer.copyOf(accepted)) }
                    totalSamples += accepted
                    if (totalSamples >= MAX_AUDIO_SAMPLES) {
                        maximumReached = true
                        break
                    }
                } else if (count < 0) {
                    if (!recordingFinalized.get()) {
                        Handler(Looper.getMainLooper()).post {
                            viewModel.setStatus("Lecture du microphone interrompue ($count).")
                        }
                    }
                    break
                }
            }

            if (maximumReached) {
                try { recorder.stop() } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    if (recordingFinalized.compareAndSet(false, true)) {
                        setState(false)
                        stopAndTranscribe(recorder, threadHolder.get(), chunks, viewModel) {
                            setRecorder(null)
                            setThread(null)
                        }
                    }
                }
            }
        }, "audio-recorder")

        threadHolder.set(thread)
        thread.start()
        setThread(thread)
    } catch (error: Exception) {
        try { recorder.stop() } catch (_: Exception) { }
        recorder.release()
        setRecorder(null)
        setThread(null)
        recordingFinalized.set(true)
        viewModel.setStatus("Erreur microphone : ${error.message ?: "erreur inconnue"}")
        setState(false)
    }
}

private class ThreadReference {
    @Volatile private var current: Thread? = null
    fun set(thread: Thread?) { current = thread }
    fun get(): Thread? = current
}

private fun stopAndTranscribe(
    recorder: AudioRecord?,
    thread: Thread?,
    chunks: MutableList<ShortArray>,
    viewModel: MainViewModel,
    cleanup: () -> Unit
) {
    try { recorder?.stop() } catch (_: Exception) { }
    try { thread?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    try { recorder?.release() } catch (_: Exception) { }
    cleanup()

    val audio: FloatArray
    synchronized(chunks) {
        val total = chunks.sumOf { it.size }
        audio = FloatArray(total)
        var index = 0
        for (chunk in chunks) {
            for (sample in chunk) audio[index++] = sample / 32768f
        }
        chunks.clear()
    }
    if (audio.isEmpty()) {
        viewModel.setStatus("Aucun audio n'a été capturé.")
        return
    }
    viewModel.transcribeAudio(audio)
}
