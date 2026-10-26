package com.kidasniger.learn

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var ctx = 0L
    private val lang = "fr"

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var recBtn: Button
    private lateinit var modelBtn: Button

    private var recorder: AudioRecord? = null
    private var recThread: Thread? = null
    @Volatile private var recording = false
    private val chunks = ArrayList<ShortArray>()

    private val modelFile get() = File(filesDir, "model.bin")

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else status.text = "Permission micro refusée."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 14f; text = "Aucun modèle chargé." }
        modelBtn = Button(this).apply { text = "Choisir le modèle (.bin)"; setOnClickListener { pickModel.launch(arrayOf("*/*")) } }
        recBtn = Button(this).apply { text = "● Enregistrer"; isEnabled = false; setOnClickListener { toggleRecording() } }
        val copyBtn = Button(this).apply { text = "Copier le texte"; setOnClickListener { copyText() } }
        result = TextView(this).apply { textSize = 18f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(result) }

        root.addView(status)
        root.addView(modelBtn)
        root.addView(recBtn)
        root.addView(copyBtn)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (modelFile.exists()) loadModel()
    }

    private fun importModel(uri: Uri) {
        setBusy("Copie du modèle…")
        io.execute {
            try {
                contentResolver.openInputStream(uri)!!.use { i -> modelFile.outputStream().use { o -> i.copyTo(o) } }
                loadModel()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Erreur copie : ${e.message}"; modelBtn.isEnabled = true }
            }
        }
    }

    private fun loadModel() {
        runOnUiThread { setBusy("Chargement du modèle…") }
        io.execute {
            if (ctx != 0L) { WhisperLib.freeContext(ctx); ctx = 0L }
            ctx = WhisperLib.initContext(modelFile.absolutePath)
            runOnUiThread {
                modelBtn.isEnabled = true
                if (ctx != 0L) { status.text = "Modèle prêt."; recBtn.isEnabled = true }
                else status.text = "Échec du chargement (fichier invalide ?)."
            }
        }
    }

    private fun setBusy(msg: String) { status.text = msg; recBtn.isEnabled = false; modelBtn.isEnabled = false }

    private fun toggleRecording() {
        if (recording) { stopRecording(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        recorder = rec
        chunks.clear()
        rec.startRecording()
        recording = true
        recBtn.text = "■ Arrêter et transcrire"
        status.text = "Enregistrement…"
        recThread = Thread {
            val buf = ShortArray(4096)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(chunks) { chunks.add(buf.copyOf(n)) }
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        recording = false
        recThread?.join()
        recorder?.stop(); recorder?.release(); recorder = null
        val audio: FloatArray
        synchronized(chunks) {
            val total = chunks.sumOf { it.size }
            audio = FloatArray(total)
            var k = 0
            for (c in chunks) for (s in c) audio[k++] = s / 32768f
            chunks.clear()
        }
        recBtn.text = "● Enregistrer"
        setBusy("Transcription en cours…")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        io.execute {
            val text = WhisperLib.transcribe(ctx, audio, lang, threads).trim()
            runOnUiThread {
                result.text = if (result.text.isEmpty()) text else result.text.toString() + "\n\n" + text
                status.text = "Modèle prêt."
                recBtn.isEnabled = true; modelBtn.isEnabled = true
            }
        }
    }

    private fun copyText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcription", result.text))
        status.text = "Texte copié."
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        if (ctx != 0L) WhisperLib.freeContext(ctx)
    }
}
