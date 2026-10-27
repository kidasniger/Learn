package com.kidasniger.learn.ui

import android.app.Application
import android.net.Uri
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TranscriptionRepository
    val transcriptions: StateFlow<List<TranscriptionEntity>>

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var ctx = 0L
    private val lang = "fr"

    val modelFile get() = File(getApplication<Application>().filesDir, "model.bin")

    // State flows declared BEFORE init block to avoid NullPointerException
    private val _statusText = MutableStateFlow(if (modelFile.exists()) "Modèle prêt." else "Aucun modèle chargé.")
    val statusText: StateFlow<String> = _statusText

    private val _modelLoaded = MutableStateFlow(modelFile.exists())
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
            loadModelInternal()
        }
    }

    fun importModel(uri: Uri, context: android.content.Context) {
        _isBusy.value = true
        _statusText.value = "Copie du modèle..."
        ioExecutor.execute {
            try {
                context.contentResolver.openInputStream(uri)?.use { i ->
                    modelFile.outputStream().use { o -> i.copyTo(o) }
                }
                loadModelInternal()
            } catch (e: Exception) {
                viewModelScope.launch {
                    _statusText.value = "Erreur copie : ${e.message}"
                    _isBusy.value = false
                }
            }
        }
    }

    fun downloadModel(urlStr: String) {
        _isBusy.value = true
        _statusText.value = "Téléchargement du modèle (0%)..."
        ioExecutor.execute {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()
                val length = connection.contentLength
                val input = java.io.BufferedInputStream(connection.inputStream)
                val output = java.io.FileOutputStream(modelFile)
                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    output.write(data, 0, count)
                    if (length > 0) {
                        val progress = (total * 100 / length).toInt()
                        if (progress % 5 == 0) {
                            viewModelScope.launch {
                                _statusText.value = "Téléchargement ($progress%)..."
                            }
                        }
                    }
                }
                output.flush()
                output.close()
                input.close()
                loadModelInternal()
            } catch (e: Exception) {
                viewModelScope.launch {
                    _statusText.value = "Erreur téléchargement : ${e.message}"
                    _isBusy.value = false
                }
            }
        }
    }

    private fun loadModelInternal() {
        _statusText.value = "Chargement du modèle..."
        ioExecutor.execute {
            if (ctx != 0L) {
                WhisperLib.freeContext(ctx)
                ctx = 0L
            }
            ctx = WhisperLib.initContext(modelFile.absolutePath)
            viewModelScope.launch {
                _isBusy.value = false
                if (ctx != 0L) {
                    _statusText.value = "Modèle prêt."
                    _modelLoaded.value = true
                } else {
                    _statusText.value = "Échec du chargement (fichier invalide ?)."
                    _modelLoaded.value = false
                }
            }
        }
    }

    fun appendTranscriptionResult(newText: String) {
        val current = _currentText.value
        _currentText.value = if (current.isEmpty()) newText else "$current\n\n$newText"
    }

    fun transcribeAudio(audio: FloatArray, onDone: () -> Unit) {
        _isBusy.value = true
        _statusText.value = "Transcription en cours..."
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        ioExecutor.execute {
            val text = WhisperLib.transcribe(ctx, audio, lang, threads).trim()
            viewModelScope.launch {
                appendTranscriptionResult(text)
                _statusText.value = "Modèle prêt."
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
                    title = if (title.isBlank()) "Transcription ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" else title,
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

    override fun onCleared() {
        super.onCleared()
        ioExecutor.shutdown()
        if (ctx != 0L) {
            WhisperLib.freeContext(ctx)
        }
    }
}
