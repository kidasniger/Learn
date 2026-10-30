package com.kidasniger.learn

/**
 * Un segment horodaté renvoyé nativement par le moteur Whisper.
 * Les horaires sont exprimés en millisecondes depuis le début de l'audio.
 */
data class WhisperSegment(
    val t0Ms: Long,
    val t1Ms: Long,
    val text: String
)

/**
 * Callback invoqué par le moteur natif à chaque nouveau segment décodé,
 * ce qui permet d'afficher le texte au fil de l'eau pendant la transcription.
 */
fun interface SegmentCallback {
    fun onNewSegment(text: String, t0Ms: Long, t1Ms: Long)
}

object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(modelPath: String): Long

    /**
     * Variante permettant d'activer l'accélération GPU côté ggml.
     * Sans backend GPU compilé, ggml retombe proprement sur le CPU.
     */
    external fun initContextEx(modelPath: String, useGpu: Boolean): Long

    external fun freeContext(ctx: Long)
    external fun transcribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String

    /**
     * Transcription détaillée : applique les paramètres avancés (beam search,
     * température, prompt initial) et renvoie une chaîne JSON de la forme
     * {"segments":[{"t0":ms,"t1":ms,"text":"..."},...]}.
     * [callback] (optionnel) est invoqué à chaque segment décodé.
     */
    external fun transcribeDetailed(
        ctx: Long,
        audio: FloatArray,
        lang: String,
        threads: Int,
        beamSize: Int,
        temperature: Float,
        initialPrompt: String,
        callback: SegmentCallback?
    ): String
}
