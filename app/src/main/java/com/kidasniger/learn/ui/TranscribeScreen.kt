package com.kidasniger.learn.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    val chunks = remember { Collections.synchronizedList(ArrayList<ShortArray>()) }

    val pickModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importModel(uri, context)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recording = true
            chunks.clear()
            startRec(context, viewModel, { rec -> recorderInstance = rec }, { t -> recordingThread = t }, chunks) { recording = it }
        } else {
            viewModel.setStatus("Permission micro refusée.")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Transcription Audio (Whisper)") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Statut du Moteur", style = MaterialTheme.typography.titleMedium)
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
                        enabled = !isBusy
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Télécharger Modèle")
                    }
                    DropdownMenu(
                        expanded = downloadMenuExpanded,
                        onDismissRequest = { downloadMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Modèle Q8 (Turbo - Haute Précision)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin?download=true")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Modèle Q5 (Turbo - Plus Léger)") },
                            onClick = {
                                downloadMenuExpanded = false
                                viewModel.downloadModel("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sélectionner depuis l'appareil (.bin)") },
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
                            recording = false
                            stopAndTranscribe(recorderInstance, recordingThread, chunks, viewModel) {
                                recorderInstance = null
                                recordingThread = null
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                recording = true
                                chunks.clear()
                                startRec(context, viewModel, { rec -> recorderInstance = rec }, { t -> recordingThread = t }, chunks) { recording = it }
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = modelLoaded && !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
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
                    scope.launch { snackbarHostState.showSnackbar("Transcription sauvegardée !") }
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
    context: Context,
    viewModel: MainViewModel,
    setRecorder: (AudioRecord?) -> Unit,
    setThread: (Thread?) -> Unit,
    chunks: MutableList<ShortArray>,
    setState: (Boolean) -> Unit
) {
    val rate = 16000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
    try {
        rec.startRecording()
        setRecorder(rec)
        viewModel.setStatus("Enregistrement en cours...")
        val t = Thread {
            val buf = ShortArray(4096)
            while (true) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(buf.copyOf(n))
                    }
                } else {
                    break
                }
            }
        }
        t.start()
        setThread(t)
    } catch (e: Exception) {
        viewModel.setStatus("Erreur micro : ${e.message}")
        setState(false)
    }
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
        recorder?.release()
    } catch (_: Exception) {}
    try {
        thread?.join(500)
    } catch (_: Exception) {}
    cleanup()

    val audio: FloatArray
    synchronized(chunks) {
        val total = chunks.sumOf { it.size }
        audio = FloatArray(total)
        var k = 0
        for (c in chunks) {
            for (s in c) {
                audio[k++] = s / 32768f
            }
        }
        chunks.clear()
    }

    viewModel.transcribeAudio(audio) {}
}
