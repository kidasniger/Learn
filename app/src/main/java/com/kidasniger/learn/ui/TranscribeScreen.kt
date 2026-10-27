package com.kidasniger.learn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000
private const val MAX_RECORDING_SECONDS = 10 * 60
private const val MAX_AUDIO_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val statusText by viewModel.statusText.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val currentText by viewModel.currentText.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    var recording by remember { mutableStateOf(false) }
    var saveDialogShown by remember { mutableStateOf(false) }
    var saveTitle by remember { mutableStateOf("") }
    var downloadMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var recorderInstance by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val recordingFinalized = remember { AtomicBoolean(true) }
    val chunks = remember { Collections.synchronizedList(ArrayList<ShortArray>()) }

    val pickModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importModel(uri, context)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recordingFinalized.set(false)
            recording = true
            chunks.clear()
            startRec(
                viewModel,
                { rec -> recorderInstance = rec },
                { thread -> recordingThread = thread },
                chunks,
                recordingFinalized
            ) { recording = it }
        } else {
            viewModel.setStatus("Permission microphone refusée.")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Transcription audio (Whisper)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Statut du moteur", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
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
                        Text("Télécharger le modèle")
                    }
                    DropdownMenu(
                        expanded = downloadMenuExpanded,
                        onDismissRequest = { downloadMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Modèle Q8 (Turbo, haute précision)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel(
                                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin?download=true"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Modèle Q5 (Turbo, plus léger)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel(
                                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Importer depuis l'appareil (.bin)") },
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
                                    viewModel,
                                    { rec -> recorderInstance = rec },
                                    { thread -> recordingThread = thread },
                                    chunks,
                                    recordingFinalized
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

            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    if (currentText.isEmpty()) {
                        Text(
                            "Le texte transcrit apparaîtra ici...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        SelectionContainer(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(currentText, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            if (currentText.isNotEmpty()) {
                Button(
                    onClick = { saveDialogShown = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && !recording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sauvegarder dans l'historique")
                }
            }
        }
    }

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
                    scope.launch { snackbarHostState.showSnackbar("Transcription sauvegardée.") }
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
}

private fun startRec(
    viewModel: MainViewModel,
    setRecorder: (AudioRecord?) -> Unit,
    setThread: (Thread?) -> Unit,
    chunks: MutableList<ShortArray>,
    recordingFinalized: AtomicBoolean,
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
        viewModel.setStatus("Enregistrement en cours. Limite : 10 minutes.")

        val threadHolder = ThreadReference()
        val thread = Thread({
            val buffer = ShortArray(4096)
            var totalSamples = 0
            var maximumReached = false

            while (true) {
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
                    if (totalSamples >= MAX_AUDIO_SAMPLES) {
                        maximumReached = true
                        break
                    }
                } else {
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
