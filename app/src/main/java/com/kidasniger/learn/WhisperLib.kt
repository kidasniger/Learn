package com.kidasniger.learn

object WhisperLib {
    private var loaded = false

    init {
        try {
            System.loadLibrary("whisper_jni")
            loaded = true
        } catch (e: Throwable) {
            loaded = false
        }
    }

    fun initContext(modelPath: String): Long {
        if (!loaded) return 1L
        return nativeInitContext(modelPath)
    }

    fun freeContext(ctx: Long) {
        if (!loaded) return
        nativeFreeContext(ctx)
    }

    fun transcribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String {
        if (!loaded) {
            return "Transcription simulée (mode démo Whisper): Audio capté avec succès (${audio.size} échantillons). Veuillez charger un modèle valide ou exécuter sur l'appareil cible avec NDK."
        }
        return nativeTranscribe(ctx, audio, lang, threads)
    }

    private external fun nativeInitContext(modelPath: String): Long
    private external fun nativeFreeContext(ctx: Long)
    private external fun nativeTranscribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String
}
