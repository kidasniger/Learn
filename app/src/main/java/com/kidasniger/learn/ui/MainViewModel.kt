package com.kidasniger.learn.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kidasniger.learn.WhisperLib
import com.kidasniger.learn.data.AppDatabase
import com.kidasniger.learn.data.TranscriptFormats
import com.kidasniger.learn.data.TranscriptSegment
import com.kidasniger.learn.data.TranscriptionEntity
import com.kidasniger.learn.data.TranscriptionRepository
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TranscriptionRepository
    val transcriptions: StateFlow<List<TranscriptionEntity>>

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-engine").apply { isDaemon = true }
    }
    private val preferences = application.getSharedPreferences("learn_preferences", Context.MODE_PRIVATE)
    private val modelsDirectory = File(application.filesDir, "models").apply { mkdirs() }
    private val legacyModelFile = File(application.filesDir, "model.bin")
    private val pendingModelFile = File(application.filesDir, "model.bin.pending")
    private var ctx = 0L

    private val _selectedModelName = MutableStateFlow(initialModelName())
    val selectedModelName: StateFlow<String> = _selectedModelName

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels

    private val _language = MutableStateFlow(preferences.getString("language", "auto") ?: "auto")
    val language: StateFlow<String> = _language

    private val _highAccuracy = MutableStateFlow(preferences.getBoolean("high_accuracy", false))
    val highAccuracy: StateFlow<Boolean> = _highAccuracy

    val modelFile: File
        get() = fileForModel(_selectedModelName.value)

    private val _statusText = MutableStateFlow(
        if (modelFile.isFile) "Vérification du modèle..." else "Téléchargez ou importez un modèle Whisper."
    )
    val statusText: StateFlow<String> = _statusText

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText

    private val _currentSegments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val currentSegments: StateFlow<List<TranscriptSegment>> = _currentSegments

    private val _personalNotes = MutableStateFlow("")
    val personalNotes: StateFlow<String> = _personalNotes

    private val _analysisText = MutableStateFlow("")
    val analysisText: StateFlow<String> = _analysisText

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    private var textWasEdited = false

    init {
        val dao = AppDatabase.getDatabase(application).transcriptionDao()
        repository = TranscriptionRepository(dao)
        transcriptions = repository.allTranscriptions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        refreshAvailableModels()

        val initialFile = modelFile
        if (initialFile.isFile) {
            if (initialFile.length() > MAX_MODEL_BYTES) {
                _statusText.value = "Le modèle sélectionné dépasse 250 Mo. Choisissez un modèle plus léger."
            } else {
                _isBusy.value = true
                engineExecutor.execute {
                    try {
                        loadModelBlocking(initialFile)
                    } finally {
                        _isBusy.value = false
                    }
                }
            }
        }
    }

    private fun initialModelName(): String {
        val saved = preferences.getString("selected_model", null)
        if (!saved.isNullOrBlank() && fileForModel(saved).isFile) return saved
        if (legacyModelFile.isFile) return "model.bin"
        return modelsDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            ?.sortedWith(modelSortOrder())
            ?.firstOrNull()?.name ?: ""
    }

    private fun modelSortOrder(): Comparator<File> = compareBy<File> {
        when {
            it.name.contains("tiny", true) -> 0
            it.name.contains("base", true) -> 1
            it.name.contains("small", true) -> 2
            else -> 3
        }
    }.thenBy { it.name.lowercase(Locale.ROOT) }

    private fun fileForModel(name: String): File {
        val safe = sanitizeModelName(name)
        return if (safe == "model.bin") legacyModelFile else File(modelsDirectory, safe)
    }

    private fun sanitizeModelName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "imported-model.bin" }
        return if (leaf.endsWith(".bin", ignoreCase = true)) leaf else "$leaf.bin"
    }

    private fun refreshAvailableModels() {
        val names = mutableListOf<String>()
        if (legacyModelFile.isFile && legacyModelFile.length() >= MIN_MODEL_BYTES) names += "model.bin"
        names += modelsDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", ignoreCase = true) && it.length() >= MIN_MODEL_BYTES }
            ?.sortedWith(modelSortOrder())
            ?.map { it.name }
            .orEmpty()
        _availableModels.value = names.distinct()
        if (_selectedModelName.value !in names && names.isNotEmpty()) {
            if (!_modelLoaded.value) _selectedModelName.value = names.first()
        }
    }

    fun displayModelName(name: String): String {
        val file = fileForModel(name)
        val megabytes = if (file.isFile) file.length() / (1024L * 1024L) else 0L
        return "$name · $megabytes Mo"
    }

    fun selectModel(name: String) {
        if (name == _selectedModelName.value || name !in _availableModels.value) return
        val candidateFile = fileForModel(name)
        if (!candidateFile.isFile || candidateFile.length() > MAX_MODEL_BYTES) {
            _statusText.value = "Ce modèle est absent ou trop volumineux."
            return
        }
        if (!beginOperation("Chargement du modèle $name...")) return

        viewModelScope.launch {
            try {
                val loaded = onEngine {
                    val oldFile = modelFile
                    freeNativeContext(ctx)
                    ctx = 0L
                    _modelLoaded.value = false
                    val candidate = createNativeContext(candidateFile)
                    if (candidate == 0L) {
                        val restored = oldFile.isFile && oldFile.length() <= MAX_MODEL_BYTES
                        ctx = if (restored) createNativeContext(oldFile) else 0L
                        _modelLoaded.value = ctx != 0L
                        _statusText.value = if (restored && ctx != 0L) {
                            "Le nouveau modèle est invalide. L'ancien modèle est resté sélectionné."
                        } else {
                            "Impossible de charger ce modèle."
                        }
                        false
                    } else {
                        ctx = candidate
                        _selectedModelName.value = name
                        preferences.edit().putString("selected_model", name).apply()
                        _modelLoaded.value = true
                        _statusText.value = "Modèle prêt : $name"
                        true
                    }
                }
                if (loaded) refreshAvailableModels()
            } catch (error: Exception) {
                _statusText.value = "Erreur de chargement : ${safeMessage(error)}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun deleteDownloadedModel(name: String) {
        if (name == _selectedModelName.value) {
            _statusText.value = "Sélectionnez d'abord un autre modèle avant de supprimer celui-ci."
            return
        }
        val file = fileForModel(name)
        if (!file.isFile || !file.delete()) {
            _statusText.value = "Impossible de supprimer ce modèle."
            return
        }
        refreshAvailableModels()
        _statusText.value = "Modèle supprimé : $name"
    }

    fun importModel(uri: Uri, context: Context) {
        if (!beginOperation("Copie du modèle...")) return
        val name = sanitizeModelName(queryDisplayName(uri, context) ?: uri.lastPathSegment ?: "model.bin")
        viewModelScope.launch {
            var stagedFile: File? = null
            try {
                val readyFile = withContext(Dispatchers.IO) {
                    copyModelToPending(uri, context.applicationContext)
                }
                stagedFile = readyFile
                _statusText.value = "Vérification et chargement du modèle..."
                val installed = onEngine { installPendingModelBlocking(readyFile, name) }
                if (installed) refreshAvailableModels()
                else if (!_modelLoaded.value) _statusText.value = "Échec du chargement. Choisissez un fichier Whisper valide."
            } catch (error: Exception) {
                _statusText.value = "Erreur d'importation : ${safeMessage(error)}"
            } catch (error: LinkageError) {
                _statusText.value = "Moteur natif indisponible : bibliothèques manquantes."
            } finally {
                stagedFile?.delete()
                pendingModelFile.delete()
                _isBusy.value = false
            }
        }
    }

    fun downloadModel(urlStr: String) {
        val targetName = try {
            sanitizeModelName(URL(urlStr).path.substringAfterLast('/'))
        } catch (_: Exception) {
            "imported-model.bin"
        }
        if (!beginOperation("Préparation du téléchargement...")) return

        viewModelScope.launch {
            var stagedFile: File? = null
            try {
                val readyFile = withContext(Dispatchers.IO) { downloadModelToPending(urlStr) }
                stagedFile = readyFile
                _statusText.value = "Vérification et chargement du modèle..."
                val installed = onEngine { installPendingModelBlocking(readyFile, targetName) }
                if (installed) refreshAvailableModels()
                else if (!_modelLoaded.value) _statusText.value = "Échec du chargement. Le modèle téléchargé est invalide."
            } catch (error: Exception) {
                _statusText.value = "Erreur de téléchargement : ${safeMessage(error)}"
            } catch (error: LinkageError) {
                _statusText.value = "Moteur natif indisponible : bibliothèques manquantes."
            } finally {
                stagedFile?.delete()
                pendingModelFile.delete()
                _isBusy.value = false
            }
        }
    }

    private fun queryDisplayName(uri: Uri, context: Context): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun beginOperation(status: String): Boolean {
        if (_isBusy.value) return false
        _isBusy.value = true
        _statusText.value = status
        return true
    }

    private suspend fun copyModelToPending(uri: Uri, context: Context): File {
        val destination = pendingModelFile
        destination.delete()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Impossible d'ouvrir le fichier sélectionné.")
        try {
            input.use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count.toLong()
                        checkModelSize(total)
                        checkFreeSpace(count.toLong())
                        output.write(buffer, 0, count)
                    }
                }
            }
            validateStagedModel(destination)
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun downloadModelToPending(urlStr: String): File {
        val url = URL(urlStr)
        if (url.protocol != "https") throw IOException("Le téléchargement doit utiliser HTTPS.")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        val destination = pendingModelFile
        destination.delete()
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Le serveur a répondu HTTP ${connection.responseCode}.")
            }
            if (connection.url.protocol != "https") throw IOException("Redirection HTTPS non sécurisée.")
            val expectedLength = connection.contentLengthLong
            if (expectedLength > MAX_MODEL_BYTES) throw IOException("Le fichier du modèle est trop volumineux.")
            if (expectedLength > 0L) checkFreeSpace(expectedLength)

            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var previousProgress = -5
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count.toLong()
                        checkModelSize(total)
                        checkFreeSpace(count.toLong())
                        output.write(buffer, 0, count)
                        if (expectedLength > 0L) {
                            val progress = ((total * 100L) / expectedLength).toInt().coerceAtMost(100)
                            if (progress >= previousProgress + 5) {
                                previousProgress = progress
                                _statusText.value = "Téléchargement du modèle ($progress%)..."
                            }
                        } else {
                            _statusText.value = "Téléchargement du modèle (${formatMegabytes(total)} Mo)..."
                        }
                    }
                    if (expectedLength > 0L && total != expectedLength) {
                        throw IOException("Téléchargement incomplet : taille reçue incorrecte.")
                    }
                }
            }
            validateStagedModel(destination)
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun installPendingModelBlocking(stagedFile: File, targetName: String): Boolean {
        val previousName = _selectedModelName.value
        val previousFile = fileForModel(previousName)
        freeNativeContext(ctx)
        ctx = 0L
        _modelLoaded.value = false

        val candidateContext = createNativeContext(stagedFile)
        if (candidateContext == 0L) {
            val restored = previousFile.isFile && previousFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES
            ctx = if (restored) createNativeContext(previousFile) else 0L
            _modelLoaded.value = ctx != 0L
            _statusText.value = if (_modelLoaded.value) {
                "Nouveau modèle invalide. L'ancien modèle a été restauré."
            } else {
                "Échec du chargement : fichier modèle invalide ou incompatible."
            }
            return false
        }

        val finalFile = fileForModel(targetName)
        finalFile.parentFile?.mkdirs()
        try {
            Files.move(stagedFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            freeNativeContext(candidateContext)
            val restored = previousFile.isFile && previousFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES
            ctx = if (restored) createNativeContext(previousFile) else 0L
            _modelLoaded.value = ctx != 0L
            _statusText.value = if (_modelLoaded.value) {
                "Installation impossible. L'ancien modèle a été restauré."
            } else {
                "Installation impossible : ${safeMessage(error)}"
            }
            return false
        }

        ctx = candidateContext
        _selectedModelName.value = targetName
        preferences.edit().putString("selected_model", targetName).apply()
        _modelLoaded.value = true
        _statusText.value = "Modèle prêt : $targetName"
        refreshAvailableModels()
        return true
    }

    private fun createNativeContext(file: File): Long {
        return try {
            WhisperLib.initContext(file.absolutePath)
        } catch (error: LinkageError) {
            _statusText.value = "Moteur natif indisponible : bibliothèques manquantes."
            0L
        } catch (error: Exception) {
            _statusText.value = "Erreur de lecture du modèle : ${safeMessage(error)}"
            0L
        }
    }

    private fun loadModelBlocking(file: File): Boolean {
        _statusText.value = "Chargement du modèle..."
        if (!file.isFile || file.length() < MIN_MODEL_BYTES || file.length() > MAX_MODEL_BYTES) {
            _modelLoaded.value = false
            _statusText.value = "Modèle absent, trop lourd ou invalide. Choisissez un modèle compatible."
            return false
        }
        val loadedContext = createNativeContext(file)
        if (loadedContext == 0L) {
            _modelLoaded.value = false
            if (!_statusText.value.startsWith("Moteur natif indisponible")) {
                _statusText.value = "Échec du chargement : fichier modèle invalide ou incompatible."
            }
            return false
        }
        freeNativeContext(ctx)
        ctx = loadedContext
        _modelLoaded.value = true
        _statusText.value = "Modèle prêt : ${file.name}"
        return true
    }

    suspend fun <T> onEngine(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            try {
                engineExecutor.execute {
                    if (!continuation.isActive) return@execute
                    try {
                        continuation.resume(block())
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                }
            } catch (error: RejectedExecutionException) {
                continuation.resumeWithException(error)
            }
        }

    fun setLanguage(language: String) {
        if (language !in SUPPORTED_LANGUAGES) return
        _language.value = language
        preferences.edit().putString("language", language).apply()
    }

    fun setHighAccuracy(enabled: Boolean) {
        _highAccuracy.value = enabled
        preferences.edit().putBoolean("high_accuracy", enabled).apply()
    }

    fun setCurrentText(text: String) {
        _currentText.value = text
        textWasEdited = true
        _analysisText.value = ""
        _translatedText.value = ""
    }

    fun setPersonalNotes(notes: String) {
        _personalNotes.value = notes
    }

    fun clearCurrentTranscription() {
        _currentText.value = ""
        _currentSegments.value = emptyList()
        _analysisText.value = ""
        _translatedText.value = ""
        textWasEdited = false
    }

    private fun exportableSegments(): List<TranscriptSegment> {
        if (textWasEdited) {
            val end = _currentSegments.value.lastOrNull()?.endMs?.coerceAtLeast(1_000L) ?: 1_000L
            return if (_currentText.value.isBlank()) emptyList()
            else listOf(TranscriptSegment(0L, end, _currentText.value.trim()))
        }
        return _currentSegments.value
    }

    fun currentExportSegments(): List<TranscriptSegment> = exportableSegments()

    fun transcribeAudio(audio: FloatArray) {
        if (audio.isEmpty()) {
            _statusText.value = "Aucun audio à transcrire."
            return
        }
        if (!_modelLoaded.value) {
            _statusText.value = "Chargez un modèle avant de transcrire."
            return
        }
        if (!beginOperation("Préparation de la transcription...")) return

        val baseOffsetMs = _currentSegments.value.lastOrNull()?.endMs ?: 0L
        val languageCode = _language.value
        val accurate = _highAccuracy.value
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val startedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                var producedText = false
                var index = 0
                val chunkSize = SAMPLE_RATE * CHUNK_SECONDS
                val totalChunks = (audio.size + chunkSize - 1) / chunkSize
                while (index < audio.size) {
                    val end = (index + chunkSize).coerceAtMost(audio.size)
                    val trimmed = TranscriptFormats.trimSilence(audio.copyOfRange(index, end))
                    val chunkOffsetMs = baseOffsetMs + index.toLong() * 1000L / SAMPLE_RATE +
                        trimmed.leadingSamples.toLong() * 1000L / SAMPLE_RATE
                    if (trimmed.samples.isNotEmpty()) {
                        _statusText.value = "Transcription progressive : segment ${index / chunkSize + 1}/$totalChunks..."
                        val rows = onEngine {
                            if (ctx == 0L) throw IllegalStateException("Le moteur Whisper n'est pas chargé.")
                            WhisperLib.transcribeSegments(ctx, trimmed.samples, languageCode, threads, accurate)
                        }
                        producedText = appendNativeRows(rows, chunkOffsetMs) || producedText
                    }
                    index = end
                }
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                _statusText.value = if (producedText) {
                    "Transcription terminée en ${String.format(Locale.getDefault(), "%.1f", elapsed)} s."
                } else {
                    "Aucune parole détectée. Vérifiez le microphone ou choisissez un modèle multilingue."
                }
            } catch (error: Exception) {
                val root = if (error is ExecutionException) error.cause ?: error else error
                _statusText.value = "Erreur de transcription : ${safeMessage(root)}"
            } catch (error: LinkageError) {
                _statusText.value = "Le moteur natif est indisponible."
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun runDemoTranscription(context: Context) {
        if (!_modelLoaded.value) {
            _statusText.value = "Chargez un modèle avant de lancer le test Whisper."
            return
        }
        if (!beginOperation("Téléchargement de l'audio de démonstration...")) return

        viewModelScope.launch {
            try {
                val demoFile = withContext(Dispatchers.IO) {
                    val destination = File(context.cacheDir, "learn-whisper-demo-jfk.wav")
                    val connection = (URL(DEMO_AUDIO_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                    try {
                        connection.connect()
                        if (connection.responseCode !in 200..299 || connection.url.protocol != "https") {
                            throw IOException("Téléchargement de la démonstration refusé.")
                        }
                        if (connection.contentLengthLong > MAX_DEMO_AUDIO_BYTES) {
                            throw IOException("Le fichier de démonstration est trop volumineux.")
                        }
                        connection.inputStream.use { input ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    total += count.toLong()
                                    if (total > MAX_DEMO_AUDIO_BYTES) {
                                        throw IOException("Le fichier de démonstration est trop volumineux.")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (destination.length() < 44L) throw IOException("Le fichier audio de démonstration est incomplet.")
                        destination
                    } catch (error: Exception) {
                        destination.delete()
                        throw error
                    } finally {
                        connection.disconnect()
                    }
                }
                _isBusy.value = false
                _statusText.value = "Test moteur : audio anglais de démonstration."
                transcribeAudioFile(Uri.fromFile(demoFile), context, languageOverride = "en")
            } catch (error: Exception) {
                _statusText.value = "Test Whisper impossible : ${safeMessage(error)}"
                _isBusy.value = false
            }
        }
    }

    fun transcribeAudioFile(uri: Uri, context: Context, languageOverride: String? = null) {
        if (!_modelLoaded.value) {
            _statusText.value = "Téléchargez ou sélectionnez un modèle avant d'importer un audio."
            return
        }
        if (!beginOperation("Ouverture du fichier audio...")) return
        clearCurrentTranscription()
        val languageCode = languageOverride ?: _language.value
        val accurate = _highAccuracy.value
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val startedAt = SystemClock.elapsedRealtime()

        viewModelScope.launch {
            try {
                var completedChunks = 0
                withContext(Dispatchers.IO) {
                    AudioFileDecoder.decode(context.applicationContext, uri) { audio, startMs ->
                        val trimmed = TranscriptFormats.trimSilence(audio)
                        if (trimmed.samples.isNotEmpty()) {
                            _statusText.value = "Transcription progressive du fichier : segment ${completedChunks + 1}..."
                            val future = engineExecutor.submit(Callable {
                                if (ctx == 0L) throw IllegalStateException("Le moteur Whisper n'est pas chargé.")
                                WhisperLib.transcribeSegments(ctx, trimmed.samples, languageCode, threads, accurate)
                            })
                            val rows = future.get()
                            appendNativeRows(
                                rows,
                                startMs + trimmed.leadingSamples.toLong() * 1000L / SAMPLE_RATE
                            )
                            completedChunks++
                        }
                    }
                }
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                _statusText.value = if (completedChunks > 0) {
                    "Fichier traité par segments : $completedChunks segment(s), ${String.format(Locale.getDefault(), "%.1f", elapsed)} s."
                } else {
                    "Aucune parole détectée dans ce fichier audio."
                }
            } catch (error: Exception) {
                val root = when (error) {
                    is ExecutionException -> error.cause ?: error
                    else -> error.cause ?: error
                }
                _statusText.value = "Import audio impossible : ${safeMessage(root)}"
            } catch (error: LinkageError) {
                _statusText.value = "Le moteur natif est indisponible."
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun appendNativeRows(rows: Array<String>, offsetMs: Long): Boolean {
        val parsed = rows.mapNotNull { row ->
            val parts = row.split('\u001f', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            try {
                val text = parts[2].trim()
                if (text.isBlank()) return@mapNotNull null
                TranscriptSegment(
                    startMs = offsetMs + parts[0].toLong().coerceAtLeast(0L),
                    endMs = offsetMs + parts[1].toLong().coerceAtLeast(parts[0].toLong() + 1L),
                    text = text
                )
            } catch (_: Exception) {
                null
            }
        }
        if (parsed.isEmpty()) return false
        _currentSegments.value = _currentSegments.value + parsed
        if (!textWasEdited) _currentText.value = TranscriptFormats.plainText(_currentSegments.value)
        _analysisText.value = ""
        return true
    }

    fun translateCurrentText(targetTag: String) {
        val sourceText = _currentText.value.trim()
        if (sourceText.isBlank()) {
            _statusText.value = "Transcrivez ou ouvrez un texte avant de le traduire."
            return
        }
        if (targetTag !in TRANSLATION_LANGUAGES) {
            _statusText.value = "Cette langue cible n'est pas disponible dans la traduction hors ligne."
            return
        }
        if (!beginOperation("Préparation de la traduction...")) return

        viewModelScope.launch {
            var translator: Translator? = null
            try {
                val sourceTag = if (_language.value == "auto") {
                    val identifier = LanguageIdentification.getClient()
                    try {
                        identifier.identifyLanguage(sourceText).awaitResult()
                    } finally {
                        identifier.close()
                    }
                } else {
                    _language.value
                }

                if (sourceTag == "und" || sourceTag.isBlank()) {
                    throw IOException("La langue source n'a pas pu être détectée. Choisissez-la manuellement puis réessayez.")
                }
                val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceTag)
                    ?: throw IOException("La langue source '$sourceTag' ne possède pas de modèle de traduction compatible.")
                val targetLanguage = TranslateLanguage.fromLanguageTag(targetTag)
                    ?: throw IOException("La langue cible n'est pas prise en charge.")

                if (sourceLanguage == targetLanguage) {
                    _translatedText.value = "Le texte est déjà dans la langue cible choisie."
                    _statusText.value = "Aucune traduction nécessaire."
                    return@launch
                }

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
                translator = Translation.getClient(options)
                _statusText.value = "Vérification / téléchargement du modèle de traduction..."
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
                _statusText.value = "Traduction hors ligne en cours..."
                _translatedText.value = translator.translate(sourceText).awaitResult()
                _statusText.value = "Traduction terminée. Le modèle de langue reste sur l'appareil."
            } catch (error: Exception) {
                _translatedText.value = ""
                _statusText.value = "Traduction impossible : ${safeMessage(error)}"
            } finally {
                translator?.close()
                _isBusy.value = false
            }
        }
    }

    fun analyzeCurrent(kind: String) {
        val text = _currentText.value.trim()
        if (text.isBlank()) {
            _analysisText.value = "Transcrivez ou importez un audio avant de lancer cette analyse."
            return
        }
        val result = when (kind) {
            "summary" -> TranscriptFormats.summarize(text).ifBlank { "Aucune phrase exploitable." }
            "ideas" -> TranscriptFormats.extractKeyIdeas(text).joinToString("\n") { "• $it" }
            "tasks" -> TranscriptFormats.extractTasks(text).joinToString("\n") { "□ $it" }
                .ifBlank { "Aucune formulation de tâche détectée. Cette extraction locale est indicative." }
            "flashcards" -> TranscriptFormats.generateFlashcards(text).mapIndexed { index, card ->
                "${index + 1}. Question : ${card.question}\n   Réponse : ${card.answer}"
            }.joinToString("\n\n").ifBlank { "Ajoutez davantage de contenu pour générer des fiches de révision." }
            else -> ""
        }
        _analysisText.value = when (kind) {
            "summary" -> "Résumé automatique local (sélection de phrases, pas une génération IA)\n\n$result"
            "ideas" -> "Idées principales candidates\n\n$result"
            "tasks" -> "Tâches et décisions candidates\n\n$result"
            "flashcards" -> "Fiches de révision (génération heuristique locale)\n\n$result"
            else -> result
        }
    }

    fun runDiagnostics(context: Context) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val freeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (_: Exception) { -1L }
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val lines = listOf(
            "Moteur Whisper : ${if (_modelLoaded.value) "chargé" else "non chargé"}",
            "Modèle sélectionné : ${_selectedModelName.value.ifBlank { "aucun" }}",
            "Microphone : ${if (minBuffer > 0) "format 16 kHz mono disponible" else "format 16 kHz non disponible"}",
            "Autorisation microphone : ${if (permission) "accordée" else "à demander"}",
            "Stockage libre : ${if (freeBytes >= 0) "${freeBytes / (1024L * 1024L)} Mo" else "indisponible"}"
        )
        _statusText.value = lines.joinToString("\n")
    }

    fun saveCurrentTranscription(title: String, folder: String = "Général", notes: String = _personalNotes.value) {
        val text = _currentText.value.trim()
        if (text.isBlank()) return
        val parts = exportableSegments()
        viewModelScope.launch(Dispatchers.IO) {
            repository.insert(
                TranscriptionEntity(
                    title = title.ifBlank {
                        "Transcription " + java.text.SimpleDateFormat(
                            "dd/MM/yyyy HH:mm",
                            Locale.getDefault()
                        ).format(java.util.Date())
                    },
                    text = text,
                    language = _language.value,
                    favorite = false,
                    folder = folder.trim().ifBlank { "Général" },
                    notes = notes.trim(),
                    segments = TranscriptFormats.serialize(parts)
                )
            )
        }
    }

    fun loadTranscription(item: TranscriptionEntity) {
        _currentText.value = item.text
        _currentSegments.value = TranscriptFormats.deserialize(item.segments).ifEmpty {
            if (item.text.isBlank()) emptyList() else listOf(TranscriptSegment(0, 1_000, item.text))
        }
        _personalNotes.value = item.notes
        _analysisText.value = ""
        _translatedText.value = ""
        textWasEdited = false
        setLanguage(item.language.takeIf { it in SUPPORTED_LANGUAGES } ?: "auto")
        _statusText.value = "Transcription ouverte : ${item.title}"
    }

    fun toggleFavorite(item: TranscriptionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(item.copy(favorite = !item.favorite))
        }
    }

    fun deleteTranscription(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteAll() }
    }

    fun setStatus(msg: String) {
        _statusText.value = msg
    }

    private fun checkModelSize(size: Long) {
        if (size > MAX_MODEL_BYTES) {
            throw IOException("Modèle trop volumineux pour cette application (maximum 250 Mo).")
        }
    }

    private fun checkFreeSpace(additionalBytes: Long) {
        val available = StatFs(getApplication<Application>().filesDir.absolutePath).availableBytes
        if (available < additionalBytes + MIN_FREE_SPACE_BYTES) {
            throw IOException("Espace de stockage insuffisant pour installer le modèle.")
        }
    }

    private fun validateStagedModel(file: File) {
        if (!file.isFile || file.length() < MIN_MODEL_BYTES) {
            throw IOException("Le fichier est vide ou trop petit pour être un modèle Whisper.")
        }
    }

    private fun formatMegabytes(bytes: Long): Long = bytes / (1024L * 1024L)

    private fun freeNativeContext(pointer: Long) {
        if (pointer == 0L) return
        try {
            WhisperLib.freeContext(pointer)
        } catch (_: LinkageError) {
        } catch (_: Exception) {
        }
    }

    private fun safeMessage(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrBlank()) error.javaClass.simpleName.ifBlank { "erreur inconnue" }
        else message.take(180)
    }

    override fun onCleared() {
        try {
            engineExecutor.execute {
                freeNativeContext(ctx)
                ctx = 0L
            }
            engineExecutor.shutdown()
        } catch (_: RejectedExecutionException) {
            engineExecutor.shutdown()
        }
        super.onCleared()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SECONDS = 30
        private const val MIN_MODEL_BYTES = 1_000_000L
        private const val MAX_MODEL_BYTES = 250L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
        private const val MAX_DEMO_AUDIO_BYTES = 5L * 1024L * 1024L
        private const val DEMO_AUDIO_URL = "https://github.com/ggml-org/whisper.cpp/raw/refs/heads/master/samples/jfk.wav"
        val SUPPORTED_LANGUAGES = linkedSetOf("auto", "fr", "en", "es", "de", "pt", "ar", "ha", "sw")
        val TRANSLATION_LANGUAGES = linkedSetOf(
            "ar", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko",
            "nl", "pl", "pt", "ru", "th", "tr", "uk", "vi", "zh"
        )
    }
}
