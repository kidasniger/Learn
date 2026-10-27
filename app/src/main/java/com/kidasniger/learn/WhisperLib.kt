package com.kidasniger.learn

object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun transcribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String

    /**
     * Each row is startMs<US>endMs<US>text. Results are returned after one bounded
     * audio chunk completes so the UI can progressively display longer files.
     */
    external fun transcribeSegments(
        ctx: Long,
        audio: FloatArray,
        lang: String,
        threads: Int,
        highAccuracy: Boolean
    ): Array<String>
}
