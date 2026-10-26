package com.kidasniger.learn

object WhisperLib {
    init { System.loadLibrary("whisper_jni") }
    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun transcribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String
}
