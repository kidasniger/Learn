package com.kidasniger.learn.ui

import android.app.Application
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kidasniger.learn.WhisperLib
import com.kidasniger.learn.data.AppDatabase
import com.kidasniger.learn.data.TranscriptionEntity
import com.kidasniger.learn.data.TranscriptionRepository
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TranscriptionRepository
    val transcriptions: StateFlow<List<TranscriptionEntity>>

    // All native model operations are serialized on this executor.
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-engine").apply { isDaemon = true }
    }
    private var ctx = 0L
    private val lang = "fr"

    val modelFile: File
        get() = File(getApplication<Application>().filesDir, "model.bin")

    private val pendingModelFile: File
        get() = File(getApplication<Application>().filesDir, "model.bin.pending")

    private val _statusText = MutableStateFlow(
        if (modelFile.exists()) "Vérification du modèle..." else "Aucun modèle chargé."
    )
    val statusText: StateFlow<String> = _statusText

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    init {
        val dao = AppDatabase.getDatabase(application).transcriptionDao()
        repository = TranscriptionRepository(dao)
        transcriptions = repository.allTranscriptions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        if (modelFile.exists()) {
            _isBusy.value = true
            engineExecutor.execute {
                try {
                    loadModelBlocking(modelFile)
                } finally {
                    _isBusy.value = false
                }
            }
        }
    }

    fun importModel(uri: Uri, context: android.content.Context) {
        if (!beginOperation("Copie du modèle...")) return

        viewModelScope.launch {
            var stagedFile: File? = null
            try {
                val readyFile = withContext(Dispatchers.IO) {
                    copyModelToPending(uri, context.applicationContext)
                }
                stagedFile = readyFile
                _statusText.value = "Vérification et chargement du modèle..."
                val installed = onEngine { installPendingModelBlocking(readyFile) }
                if (!installed && !_modelLoaded.value) {
                    _statusText.value = "Échec du chargement. Choisissez un fichier Whisper valide."
                }
            } catch (error: Exception) {
                _statusText.value = "Erreur d'importation : " + safeMessage(error)
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
        if (!beginOperation("Préparation du téléchargement...")) return

        viewModelScope.launch {
            var stagedFile: File? = null
            try {
                val readyFile = withContext(Dispatchers.IO) {
                    downloadModelToPending(urlStr)
                }
                stagedFile = readyFile
                _statusText.value = "Vérification et chargement du modèle..."
                val installed = onEngine { installPendingModelBlocking(readyFile) }
                if (!installed && !_modelLoaded.value) {
                    _statusText.value = "Échec du chargement. Le modèle téléchargé est invalide."
                }
            } catch (error: Exception) {
                _statusText.value = "Erreur de téléchargement : " + safeMessage(error)
            } catch (error: LinkageError) {
                _statusText.value = "Moteur natif indisponible : bibliothèques manquantes."
            } finally {
                stagedFile?.delete()
                pendingModelFile.delete()
                _isBusy.value = false
            }
        }
    }

    private fun beginOperation(status: String): Boolean {
        if (_isBusy.value) return false
        _isBusy.value = true
        _statusText.value = status
        return true
    }

    private suspend fun copyModelToPending(uri: Uri, context: android.content.Context): File {
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
        if (url.protocol != "https") {
            throw IOException("Le téléchargement doit utiliser HTTPS.")
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        val destination = pendingModelFile
        destination.delete()

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Le serveur a répondu HTTP " + connection.responseCode + ".")
            }
            if (connection.url.protocol != "https") {
                throw IOException("Redirection HTTPS non sécurisée.")
            }

            val expectedLength = connection.contentLengthLong
            if (expectedLength > MAX_MODEL_BYTES) {
                throw IOException("Le fichier du modèle est trop volumineux.")
            }
            if (expectedLength > 0L) {
                checkFreeSpace(expectedLength)
            }

            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var previousProgress = -1
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
                                _statusText.value = "Téléchargement du modèle (" + progress + "%)..."
                            }
                        } else {
                            _statusText.value = "Téléchargement du modèle (" + formatMegabytes(total) + " Mo)..."
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

    private fun checkModelSize(size: Long) {
        if (size > MAX_MODEL_BYTES) {
            throw IOException("Le modèle dépasse la taille maximale autorisée (2 Go).")
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

    private suspend fun <T> onEngine(block: () -> T): T =
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

    private fun createNativeContext(file: File): Long {
        return try {
            WhisperLib.initContext(file.absolutePath)
        } catch (error: LinkageError) {
            _statusText.value = "Moteur natif indisponible : bibliothèques manquantes."
            0L
        } catch (error: Exception) {
            _statusText.value = "Erreur de lecture du modèle : " + safeMessage(error)
            0L
        }
    }

    private fun loadModelBlocking(file: File): Boolean {
        _statusText.value = "Chargement du modèle..."
        if (!file.isFile || file.length() < MIN_MODEL_BYTES) {
            _modelLoaded.value = false
            _statusText.value = "Modèle absent ou invalide. Sélectionnez ou téléchargez un modèle."
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
        _statusText.value = "Modèle prêt."
        return true
    }

    private fun installPendingModelBlocking(stagedFile: File): Boolean {
        val hadPreviousModel = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES

        // Release the old native context before loading another large model to avoid
        // holding two model copies in memory at the same time.
        freeNativeContext(ctx)
        ctx = 0L
        _modelLoaded.value = false

        val candidateContext = createNativeContext(stagedFile)
        if (candidateContext == 0L) {
            val restored = hadPreviousModel && loadModelBlocking(modelFile)
            _statusText.value = if (restored) {
                "Nouveau modèle invalide. L'ancien modèle a été restauré."
            } else {
                "Échec du chargement : fichier modèle invalide ou incompatible."
            }
            return false
        }

        try {
            Files.move(
                stagedFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            freeNativeContext(candidateContext)
            val restored = hadPreviousModel && loadModelBlocking(modelFile)
            _statusText.value = if (restored) {
                "Installation impossible. L'ancien modèle a été restauré."
            } else {
                "Installation impossible : " + safeMessage(error)
            }
            return false
        }

        ctx = candidateContext
        _modelLoaded.value = true
        _statusText.value = "Modèle prêt."
        return true
    }

    private fun freeNativeContext(pointer: Long) {
        if (pointer == 0L) return
        try {
            WhisperLib.freeContext(pointer)
        } catch (_: LinkageError) {
            // The native library may not have loaded; there is then nothing safe to free.
        } catch (_: Exception) {
            // Cleanup must not crash the application.
        }
    }

    fun appendTranscriptionResult(newText: String) {
        if (newText.isBlank()) return
        val current = _currentText.value
        _currentText.value = if (current.isEmpty()) newText else current + "\n\n" + newText
    }

    fun transcribeAudio(audio: FloatArray, onDone: () -> Unit) {
        if (audio.isEmpty()) {
            _statusText.value = "Aucun audio à transcrire."
            onDone()
            return
        }
        if (!_modelLoaded.value) {
            _statusText.value = "Chargez un modèle avant de transcrire."
            onDone()
            return
        }
        if (!beginOperation("Transcription en cours...")) {
            onDone()
            return
        }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        viewModelScope.launch {
            try {
                val text = onEngine {
                    if (ctx == 0L) throw IllegalStateException("Le moteur Whisper n'est pas chargé.")
                    WhisperLib.transcribe(ctx, audio, lang, threads).trim()
                }
                if (text.isBlank()) {
                    _statusText.value = "Aucune parole détectée."
                } else {
                    appendTranscriptionResult(text)
                    _statusText.value = "Modèle prêt."
                }
            } catch (error: Exception) {
                _statusText.value = "Erreur de transcription : " + safeMessage(error)
            } catch (error: LinkageError) {
                _statusText.value = "Le moteur natif est indisponible."
            } finally {
                _isBusy.value = false
                onDone()
            }
        }
    }

    fun saveCurrentTranscription(title: String) {
        val text = _currentText.value
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insert(
                TranscriptionEntity(
                    title = if (title.isBlank()) {
                        "Transcription " + java.text.SimpleDateFormat(
                            "dd/MM/yyyy HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                    } else {
                        title
                    },
                    text = text,
                    language = lang
                )
            )
        }
    }

    fun deleteTranscription(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
        }
    }

    fun setStatus(msg: String) {
        _statusText.value = msg
    }

    private fun safeMessage(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrBlank()) "erreur inconnue" else message.take(180)
    }

    override fun onCleared() {
        try {
            engineExecutor.execute {
                freeNativeContext(ctx)
                ctx = 0L
            }
            // Queued work finishes before the cleanup task; no native call races with freeContext.
            engineExecutor.shutdown()
        } catch (_: RejectedExecutionException) {
            engineExecutor.shutdown()
        }
        super.onCleared()
    }

    companion object {
        private const val MIN_MODEL_BYTES = 1_000_000L
        private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
    }
}
