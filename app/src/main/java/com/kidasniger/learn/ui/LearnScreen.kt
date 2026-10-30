package com.kidasniger.learn.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kidasniger.learn.ui.TextSimilarity.DiffType
import com.kidasniger.learn.ui.TextSimilarity.Report
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

private const val EXERCISE_SAMPLE_RATE = 16_000
private const val EXERCISE_MAX_SECONDS = 120
private val SAMPLE_EXERCISES = listOf(
    "Le chat noir dort tranquillement près de la fenêtre du salon.",
    "Apprendre chaque jour un peu, voilà la meilleure façon de progresser.",
    "La transcription vocale locale garde vos données sur votre téléphone.",
    "Bonjou, ni se yon aplikasyon ki ede ou aprann pale fransè.",
    "Je voudrais un café bien serré et un croissant chaud, s'il vous plaît."
)

/**
 * Mode « S'entraîner » : dictée interactive. L'utilisateur saisit ou choisit
 * un texte de référence, le lit à voix haute, puis l'application transcrit
 * l'enregistrement et calcule un score de similarité (1 - distance de
 * Levenshtein mot à mot) avec correction colorée.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LearnScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("learn_exercise", Context.MODE_PRIVATE) }
    var referenceText by remember {
        mutableStateOf(prefs.getString("reference", SAMPLE_EXERCISES[0]).orEmpty())
    }
    var phase by remember { mutableStateOf("idle") } // idle | recording | scoring | result
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var report by remember { mutableStateOf<Report?>(null) }
    var hypothesisText by remember { mutableStateOf("") }

    var recorderInstance by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingThread by remember { mutableStateOf<Thread?>(null) }
    val recordingFinalized = remember { AtomicBoolean(true) }
    val chunks = remember { Collections.synchronizedList(ArrayList<ShortArray>()) }

    val infiniteTransition = rememberInfiniteTransition(label = "learnPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "learnPulseScale"
    )

    LaunchedEffect(phase) {
        while (phase == "recording") { delay(1000); elapsedSeconds += 1 }
    }
    LaunchedEffect(phase, amplitude) {
        if (phase != "recording") amplitude = 0f
        else { delay(140); amplitude = (amplitude * 0.78f).coerceAtLeast(0f) }
    }

    fun persistReference() {
        prefs.edit().putString("reference", referenceText).apply()
    }

    fun finalizeExercise() {
        recordingFinalized.set(true)
        try { recorderInstance?.stop() } catch (_: Exception) { }
        try { recordingThread?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { recorderInstance?.release() } catch (_: Exception) { }
        recorderInstance = null
        recordingThread = null

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
            phase = "idle"
            viewModel.setStatus("Aucun son capté. Rapprochez-vous du microphone.")
            return
        }
        phase = "scoring"
        viewModel.transcribeToText(audio) { result ->
            if (result.isBlank()) {
                phase = "idle"
                scope.launch { snackbar.showSnackbar("Aucun texte entendu. Réessayez en articulant.") }
            } else {
                hypothesisText = result
                report = TextSimilarity.analyze(referenceText, result)
                phase = "result"
            }
        }
    }

    fun beginExerciseRecording() {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            EXERCISE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) {
            viewModel.setStatus("Le microphone ne prend pas en charge ce format audio.")
            return@rememberLauncherForActivityResult
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                EXERCISE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBuffer * 2
            )
        } catch (error: Exception) {
            viewModel.setStatus("Impossible d'initialiser le microphone.")
            return@rememberLauncherForActivityResult
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            viewModel.setStatus("Le microphone n'a pas pu être initialisé.")
            return@rememberLauncherForActivityResult
        }
        try {
            recorder.startRecording()
        } catch (error: Exception) {
            recorder.release()
            viewModel.setStatus("L'enregistrement n'a pas démarré.")
            return@rememberLauncherForActivityResult
        }
        recorderInstance = recorder
        recordingFinalized.set(false)
        chunks.clear()
        elapsedSeconds = 0
        amplitude = 0f
        report = null
        hypothesisText = ""
        phase = "recording"
        viewModel.setStatus("Lisez le texte à voix haute, puis appuyez sur Terminer.")

        val maxSamples = EXERCISE_SAMPLE_RATE * EXERCISE_MAX_SECONDS
        val thread = Thread({
            val buffer = ShortArray(4096)
            var totalSamples = 0
            var maximumReached = false
            while (!recordingFinalized.get()) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    val remaining = maxSamples - totalSamples
                    if (remaining <= 0) { maximumReached = true; break }
                    val accepted = minOf(count, remaining)
                    synchronized(chunks) { chunks.add(buffer.copyOf(accepted)) }
                    totalSamples += accepted
                    var peak = 0f
                    for (sampleIndex in 0 until accepted) {
                        peak = maxOf(peak, kotlin.math.abs(buffer[sampleIndex].toInt()) / 32768f)
                    }
                    Handler(Looper.getMainLooper()).post { amplitude = peak }
                    if (totalSamples >= maxSamples) { maximumReached = true; break }
                } else if (count < 0) {
                    break
                }
            }
            if (maximumReached) {
                Handler(Looper.getMainLooper()).post {
                    if (recordingFinalized.compareAndSet(false, true)) {
                        finalizeExercise()
                    }
                }
            }
        }, "learn-exercise-recorder")
        recordingThread = thread
        thread.start()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginExerciseRecording()
        } else {
            viewModel.setStatus("Permission microphone refusée.")
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = {
            Column {
                Text("S'entraîner")
                Text("Dictée interactive avec score de prononciation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!modelLoaded) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Text(
                        "Chargez d'abord un modèle Whisper dans l'onglet Transcrire pour lancer un exercice.",
                        Modifier.padding(14.dp)
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Texte à lire", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = referenceText,
                        onValueChange = {
                            if (phase == "idle" || phase == "result") {
                                referenceText = it
                                report = null
                            }
                        },
                        placeholder = { Text("Écrivez ou collez le texte que vous lirez à voix haute…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        enabled = phase != "recording" && phase != "scoring",
                        minLines = 3
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                referenceText = SAMPLE_EXERCISES.random()
                                report = null
                                persistReference()
                            },
                            label = { Text("Texte au hasard") },
                            enabled = phase != "recording" && phase != "scoring"
                        )
                        AssistChip(
                            onClick = {
                                referenceText = ""
                                report = null
                            },
                            label = { Text("Effacer") },
                            enabled = phase != "recording" && phase != "scoring"
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (phase) {
                            "recording" -> Box(
                                Modifier.size(14.dp).scale(pulseScale)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                            "scoring" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else -> Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            when (phase) {
                                "recording" -> "Enregistrement… (%02d:%02d / 02:00)".format(elapsedSeconds / 60, elapsedSeconds % 60)
                                "scoring" -> "Transcription et correction en cours…"
                                "result" -> "Exercice terminé"
                                else -> "Prêt pour un nouvel exercice"
                            },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (phase == "recording") {
                            repeat(21) { index ->
                                val barHeight = (4f + amplitude * (18f + (index % 5) * 3f)).coerceIn(4f, 28f)
                                Box(Modifier.padding(horizontal = 2.dp).width(3.dp).height(barHeight.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phase != "recording" && phase != "scoring") {
                            Button(
                                onClick = {
                                    if (referenceText.isBlank()) {
                                        viewModel.setStatus("Écrivez d'abord le texte à lire.")
                                    } else {
                                        persistReference()
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                            == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            beginExerciseRecording()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                enabled = modelLoaded && !isBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (phase == "result") "Réessayer" else "Commencer")
                            }
                        } else if (phase == "recording") {
                            Button(
                                onClick = { if (recordingFinalized.compareAndSet(false, true)) finalizeExercise() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Terminer la lecture")
                            }
                        }
                    }
                }
            }

            report?.let { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Résultat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ScoreColumn("${(result.similarity * 100).toInt()} %", "similarité")
                            ScoreColumn("${(result.wer * 100).toInt()} %", "erreurs / mots")
                            ScoreColumn("${result.referenceWords}", "mots attendus")
                            ScoreColumn("${result.hypothesisWords}", "mots entendus")
                        }
                        LinearProgressIndicator(
                            progress = result.similarity.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Correction mot à mot :", fontWeight = FontWeight.SemiBold)
                        FlowCorrectedWords(result)
                        Text(
                            when {
                                result.similarity >= 0.95f -> "Excellent ! Presque parfait."
                                result.similarity >= 0.80f -> "Très bien. Quelques mots à retravailler."
                                result.similarity >= 0.60f -> "Pas mal ! Réécoutez-vous et réessayez."
                                else -> "Courage : lisez plus lentement, articulez, puis réessayez."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScoreColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FlowCorrectedWords(report: Report) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        report.diff.forEach { item ->
            val (colors, label) = when (item.type) {
                DiffType.MATCH -> MaterialTheme.colorScheme.primaryContainer to (item.reference ?: "")
                DiffType.SUBSTITUTION -> MaterialTheme.colorScheme.errorContainer to
                    "${item.reference} → ${item.hypothesis ?: "—"}"
                DiffType.DELETION -> MaterialTheme.colorScheme.tertiaryContainer to
                    "${item.reference ?: ""} (manquant)"
                DiffType.INSERTION -> MaterialTheme.colorScheme.surfaceVariant to
                    "+${item.hypothesis ?: ""}"
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(colors, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
