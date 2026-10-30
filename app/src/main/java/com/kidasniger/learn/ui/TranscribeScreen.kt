package com.kidasniger.learn.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000
private const val MAX_RECORDING_SECONDS = 2 * 60
private const val MAX_AUDIO_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val statusText by viewModel.statusText.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val selectedLang by viewModel.selectedLang.collectAsState()

    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var importingAudio by remember { mutableStateOf(false) }
    var clearDraftConfirmation by remember { mutableStateOf(false) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf("txt") }
    var subtitleMode by remember { mutableStateOf(false) }
    var saveDialogShown by remember { mutableStateOf(false) }
    var saveTitle by remember { mutableStateOf("") }
    var downloadMenuExpanded by remember { mutableStateOf(false) }
    var langMenuExpanded by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    val whisperSettings by viewModel.whisperSettings.collectAsState()
    val lastSegments by viewModel.lastSegments.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var recorderInstance by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val recordingFinalized = remember { AtomicBoolean(true) }
    val pauseRequested = remember { AtomicBoolean(false) }
    val chunks = remember { Collections.synchronizedList(ArrayList<ShortArray>()) }
    LaunchedEffect(recording, paused) {
        while (recording && !paused) { delay(1000); elapsedSeconds += 1 }
    }
    LaunchedEffect(recording, paused, amplitude) {
        if (!recording || paused) amplitude = 0f
        else { delay(140); amplitude = (amplitude * 0.78f).coerceAtLeast(0f) }
    }

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val pickModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importModel(uri, context)
        }
    }

    val audioImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importingAudio = true
            viewModel.setStatus("Lecture du fichier audio…")
            scope.launch {
                try {
                    val audio = withContext(Dispatchers.IO) { AudioFileDecoder.decode(context, uri) }
                    importingAudio = false
                    viewModel.setStatus("Audio prêt · ${audio.size / SAMPLE_RATE} seconde(s). Transcription lancée.")
                    viewModel.transcribeAudio(audio) {}
                } catch (error: Exception) {
                    importingAudio = false
                    viewModel.setStatus("Import audio impossible : ${error.message ?: "format non pris en charge"}.")
                    snackbarHostState.showSnackbar("Impossible d'importer ce fichier audio.")
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val nativeSubtitles = lastSegments.isNotEmpty()
                        when (exportFormat) {
                            "pdf" -> writeTextPdf(output, currentText)
                            "srt" -> output.write(
                                (if (nativeSubtitles) subtitlesFromSegments(lastSegments, false)
                                else makeEstimatedSubtitles(currentText, false)).toByteArray(Charsets.UTF_8)
                            )
                            "vtt" -> output.write(
                                (if (nativeSubtitles) subtitlesFromSegments(lastSegments, true)
                                else makeEstimatedSubtitles(currentText, true)).toByteArray(Charsets.UTF_8)
                            )
                            else -> output.write(currentText.toByteArray(Charsets.UTF_8))
                        }
                    } ?: throw IllegalStateException("Impossible d'ouvrir le fichier.")
                }
                snackbarHostState.showSnackbar("Export terminé.")
            } catch (error: Exception) {
                snackbarHostState.showSnackbar("Export impossible : ${error.message ?: "erreur inconnue"}.")
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recordingFinalized.set(false)
            pauseRequested.set(false)
            recording = true
            paused = false
            elapsedSeconds = 0
            amplitude = 0f
            chunks.clear()
            startRec(
                context,
                viewModel,
                { rec -> recorderInstance = rec },
                { thread -> recordingThread = thread },
                chunks, recordingFinalized, pauseRequested, { value -> amplitude = value }
            ) { recording = it }
        } else {
            viewModel.setStatus("Permission microphone refusée.")
        }
    }

    val languages = mapOf(
        "auto" to "Auto-détection",
        "fr" to "Français",
        "en" to "Anglais",
        "es" to "Espagnol",
        "de" to "Allemand",
        "it" to "Italien",
        "pt" to "Portugais"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Column { Text("Learn", fontWeight = FontWeight.Bold); Text("Transcription vocale hors ligne", style = MaterialTheme.typography.labelSmall) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { settingsDialog = true }, enabled = !isBusy) {
                        Icon(Icons.Default.Tune, contentDescription = "Réglages du moteur")
                    }
                    Box {
                        TextButton(onClick = { langMenuExpanded = true }) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(languages[selectedLang] ?: selectedLang.uppercase())
                        }
                        DropdownMenu(
                            expanded = langMenuExpanded,
                            onDismissRequest = { langMenuExpanded = false }
                        ) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.setLanguage(code)
                                        langMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Votre voix, en texte.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Enregistrez, transcrivez et gardez vos notes sur cet appareil.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (recording) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (recording) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .scale(pulseScale)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    } else {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (recording) "Enregistrement en cours..." else "Statut du moteur",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = { downloadMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy && !recording
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Modèle")
                    }
                    DropdownMenu(
                        expanded = downloadMenuExpanded,
                        onDismissRequest = { downloadMenuExpanded = false }
                    ) {
                        if (viewModel.modelFile.isFile && !modelLoaded) {
                            DropdownMenuItem(
                                text = { Text("Charger le modèle existant") },
                                onClick = {
                                    downloadMenuExpanded = false
                                    viewModel.loadExistingModel()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Tiny Q8 — rapide (~42 Mo)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel(
                                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin?download=true"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Base Q5 — recommandé (~57 Mo)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel(
                                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Small Q5 — précis (~181 Mo)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel(
                                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Importer (.bin)") },
                            onClick = {
                                downloadMenuExpanded = false
                                pickModelLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }

                Button(
                    onClick = {
                        if (recording) {
                            if (recordingFinalized.compareAndSet(false, true)) {
                                recording = false
                                stopAndTranscribe(
                                    recorderInstance,
                                    recordingThread,
                                    chunks,
                                    viewModel
                                ) {
                                    recorderInstance = null
                                    recordingThread = null
                                }
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                recordingFinalized.set(false)
                                recording = true
                                chunks.clear()
                                startRec(
                                    context,
                                    viewModel,
                                    { rec -> recorderInstance = rec },
                                    { thread -> recordingThread = thread },
                                    chunks, recordingFinalized, pauseRequested, { value -> amplitude = value }
                                ) { recording = it }
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = modelLoaded && !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (recording) "Arrêter" else "Enregistrer")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { audioImportLauncher.launch(arrayOf("audio/*")) },
                    enabled = modelLoaded && !isBusy && !recording && !importingAudio,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Importer audio")
                }
                Text("Max. 20 min", modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recording) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (paused) "Enregistrement en pause" else "Capture audio en direct",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60), fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            repeat(25) { index ->
                                val barHeight = (5f + amplitude * (20f + (index % 5) * 4f)).coerceIn(5f, 34f)
                                Box(Modifier.padding(horizontal = 2.dp).width(3.dp).height(barHeight.dp).background(
                                    if (paused) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary, CircleShape))
                            }
                        }
                        OutlinedButton(onClick = {
                            if (!paused) {
                                pauseRequested.set(true)
                                try {
                                    recorderInstance?.stop()
                                    paused = true
                                    amplitude = 0f
                                    viewModel.setStatus("Enregistrement en pause. Reprenez ou terminez.")
                                } catch (error: Exception) {
                                    pauseRequested.set(false)
                                    viewModel.setStatus("Pause impossible : ${error.message ?: "erreur microphone"}.")
                                }
                            } else {
                                try {
                                    recorderInstance?.startRecording()
                                    pauseRequested.set(false)
                                    paused = false
                                    viewModel.setStatus("Enregistrement repris.")
                                } catch (error: Exception) {
                                    viewModel.setStatus("Reprise impossible : ${error.message ?: "erreur microphone"}.")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (paused) "Reprendre" else "Mettre en pause")
                        }
                    }
                }
            }
            val progressPercent = Regex("""(\d{1,3})%""").find(statusText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (isBusy) {
                if (progressPercent != null) LinearProgressIndicator(progress = progressPercent.coerceIn(0, 100) / 100f, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (importingAudio) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 390.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    OutlinedTextField(
                        value = currentText,
                        onValueChange = { viewModel.updateCurrentText(it) },
                        placeholder = {
                            Text("Le texte apparaît pendant le traitement et le brouillon est sauvegardé automatiquement…")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )

                    if (currentText.isNotEmpty()) {
                        val wordCount = currentText.trim().split("\\s+".toRegex()).size
                        val charCount = currentText.length
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "$wordCount mots • $charCount caractères",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { clearDraftConfirmation = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Effacer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (currentText.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = subtitleMode, onClick = { subtitleMode = !subtitleMode },
                        label = { Text("Sous-titres") }, leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) })
                    Spacer(Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { exportMenuExpanded = true }) {
                            Icon(Icons.Default.FileDownload, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("Exporter")
                        }
                        DropdownMenu(exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                            val nativeAvailable = lastSegments.isNotEmpty()
                            listOf(
                                "txt" to "Texte TXT", "pdf" to "Document PDF",
                                "srt" to (if (nativeAvailable) "SRT (horodatages natifs)" else "SRT (horodatages estimés)"),
                                "vtt" to (if (nativeAvailable) "VTT (horodatages natifs)" else "VTT (horodatages estimés)")
                            ).forEach { item ->
                                DropdownMenuItem(text = { Text(item.second) }, onClick = {
                                    exportFormat = item.first; exportMenuExpanded = false
                                    exportLauncher.launch("learn-transcription.${item.first}")
                                })
                            }
                        }
                    }
                }
                if (subtitleMode) {
                    if (lastSegments.isNotEmpty()) {
                        Text("Horodatages natifs du moteur : les fichiers SRT/VTT suivent le rythme réel de votre voix.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(subtitlesFromSegments(lastSegments, true), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Horodatages estimés par phrases (les horodatages natifs apparaissent après la prochaine transcription).",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Text(makeEstimatedSubtitles(currentText, true), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (currentText.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { saveDialogShown = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy && !recording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Sauvegarder")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transcription Whisper Learn")
                                putExtra(Intent.EXTRA_TEXT, currentText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Partager la transcription"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Partager")
                    }
                }
            }
        }
    }

    if (clearDraftConfirmation) AlertDialog(
        onDismissRequest = { clearDraftConfirmation = false },
        title = { Text("Effacer le brouillon ?") },
        text = { Text("Le texte actuel sera supprimé. Cette action ne peut pas être annulée.") },
        confirmButton = { TextButton(onClick = {
            viewModel.updateCurrentText("")
            clearDraftConfirmation = false
            scope.launch { snackbarHostState.showSnackbar("Brouillon effacé.") }
        }) { Text("Effacer", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearDraftConfirmation = false }) { Text("Annuler") } }
    )

    if (saveDialogShown) {
        AlertDialog(
            onDismissRequest = { saveDialogShown = false },
            title = { Text("Sauvegarder la transcription") },
            text = {
                OutlinedTextField(
                    value = saveTitle,
                    onValueChange = { saveTitle = it },
                    label = { Text("Titre (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCurrentTranscription(saveTitle)
                    saveDialogShown = false
                    saveTitle = ""
                    scope.launch { snackbarHostState.showSnackbar("Transcription sauvegardée dans l'historique.") }
                }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogShown = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (settingsDialog) {
        WhisperSettingsDialog(
            current = whisperSettings,
            onDismiss = { settingsDialog = false },
            onApply = { newSettings ->
                viewModel.updateWhisperSettings(newSettings)
                settingsDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Réglages appliqués. Rechargez le modèle pour changer d'accélération.")
                }
            }
        )
    }
}

private fun startRec(
    context: Context,
    viewModel: MainViewModel,
    setRecorder: (AudioRecord?) -> Unit,
    setThread: (Thread?) -> Unit,
    chunks: MutableList<ShortArray>,
    recordingFinalized: AtomicBoolean,
    pauseRequested: AtomicBoolean,
    onAmplitude: (Float) -> Unit,
    setState: (Boolean) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        viewModel.setStatus("Permission microphone non accordée. Autorisez-la puis réessayez.")
        recordingFinalized.set(true)
        setState(false)
        return
    }

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
        viewModel.setStatus("Impossible d'initialiser le microphone : " + (error.message ?: "erreur inconnue"))
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
        viewModel.setStatus("Enregistrement en cours. Limite : 2 minutes.")

        val threadHolder = ThreadReference()
        val thread = Thread({
            val buffer = ShortArray(4096)
            var totalSamples = 0
            var maximumReached = false

            while (!recordingFinalized.get()) {
                if (pauseRequested.get()) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    val remaining = MAX_AUDIO_SAMPLES - totalSamples
                    if (remaining <= 0) {
                        maximumReached = true
                        break
                    }
                    val accepted = minOf(count, remaining)
                    synchronized(chunks) {
                        chunks.add(buffer.copyOf(accepted))
                    }
                    totalSamples += accepted
                    var peak = 0f
                    for (sampleIndex in 0 until accepted) {
                        peak = maxOf(peak, kotlin.math.abs(buffer[sampleIndex].toInt()) / 32768f)
                    }
                    Handler(Looper.getMainLooper()).post { onAmplitude(peak) }
                    if (totalSamples >= MAX_AUDIO_SAMPLES) {
                        maximumReached = true
                        break
                    }
                } else {
                    if (pauseRequested.get()) continue
                    if (count < 0 && !recordingFinalized.get()) {
                        Handler(Looper.getMainLooper()).post {
                            if (!recordingFinalized.get()) {
                                viewModel.setStatus("La lecture du microphone s'est interrompue (" + count + ").")
                            }
                        }
                    }
                    break
                }
            }

            if (maximumReached) {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
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
        try {
            recorder.stop()
        } catch (_: Exception) {
        }
        recorder.release()
        setRecorder(null)
        setThread(null)
        recordingFinalized.set(true)
        viewModel.setStatus("Erreur microphone : " + (error.message ?: "erreur inconnue"))
        setState(false)
    }
}

private class ThreadReference {
    @Volatile
    private var current: Thread? = null

    fun set(thread: Thread?) {
        current = thread
    }

    fun get(): Thread? = current
}

private fun stopAndTranscribe(
    recorder: AudioRecord?,
    thread: Thread?,
    chunks: MutableList<ShortArray>,
    viewModel: MainViewModel,
    cleanup: () -> Unit
) {
    try {
        recorder?.stop()
    } catch (_: Exception) {
    }

    try {
        thread?.join(1_000)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    try {
        recorder?.release()
    } catch (_: Exception) {
    }
    cleanup()

    val audio: FloatArray
    synchronized(chunks) {
        val total = chunks.sumOf { it.size }
        audio = FloatArray(total)
        var index = 0
        for (chunk in chunks) {
            for (sample in chunk) {
                audio[index++] = sample / 32768f
            }
        }
        chunks.clear()
    }

    if (audio.isEmpty()) {
        viewModel.setStatus("Aucun audio n'a été capturé.")
        return
    }
    viewModel.transcribeAudio(audio) {}
}

private fun writeTextPdf(output: OutputStream, text: String) {
    val document = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
    val width = 595; val height = 842
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = 50f
    fun startPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
        canvas = page!!.canvas
        y = 50f
        paint.isFakeBoldText = true
        canvas!!.drawText("Learn · Transcription", 42f, y, paint)
        paint.isFakeBoldText = false
        y += 26f
    }
    fun finishPage() { page?.let { document.finishPage(it) }; page = null; canvas = null }
    try {
        startPage()
        text.lines().forEach { raw ->
            var line = raw
            if (line.isBlank()) y += 18f
            while (line.isNotEmpty()) {
                if (y > height - 48f) { finishPage(); startPage() }
                val chars = paint.breakText(line, true, 510f, null).coerceAtLeast(1)
                canvas!!.drawText(line.take(chars), 42f, y, paint)
                line = line.drop(chars).trimStart()
                y += 18f
            }
        }
        finishPage()
        document.writeTo(output)
    } finally {
        try { finishPage() } catch (_: Exception) { }
        document.close()
    }
}
