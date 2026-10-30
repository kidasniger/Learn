package com.kidasniger.learn.ui

import android.content.Context

/**
 * Réglages avancés du moteur Whisper, persistés dans les préférences.
 * [beamSize] = 1 signifie échantillonnage glouton ; > 1 active le beam search.
 */
data class WhisperSettings(
    val threads: Int = 4,
    val beamSize: Int = 1,
    val temperature: Float = 0.0f,
    val initialPrompt: String = "",
    val useGpu: Boolean = false
) {
    companion object {
        const val PREFS_NAME = "learn_whisper_params"
        const val KEY_THREADS = "threads"
        const val KEY_BEAM = "beam_size"
        const val KEY_TEMPERATURE_BITS = "temperature_bits"
        const val KEY_PROMPT = "initial_prompt"
        const val KEY_USE_GPU = "use_gpu"

        fun load(context: Context): WhisperSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val threads = prefs.getInt(KEY_THREADS, 4)
            val beam = prefs.getInt(KEY_BEAM, 1)
            val temperature = Float.fromBits(prefs.getInt(KEY_TEMPERATURE_BITS, 0.0f.toRawBits()))
            val prompt = prefs.getString(KEY_PROMPT, "").orEmpty()
            val gpu = prefs.getBoolean(KEY_USE_GPU, false)
            return WhisperSettings(
                threads = threads.coerceIn(1, 8),
                beamSize = beam.coerceIn(1, 8),
                temperature = if (temperature.isNaN()) 0f else temperature.coerceIn(0f, 1f),
                initialPrompt = prompt.take(500),
                useGpu = gpu
            )
        }

        fun save(context: Context, settings: WhisperSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_THREADS, settings.threads.coerceIn(1, 8))
                .putInt(KEY_BEAM, settings.beamSize.coerceIn(1, 8))
                .putInt(KEY_TEMPERATURE_BITS, settings.temperature.coerceIn(0f, 1f).toRawBits())
                .putString(KEY_PROMPT, settings.initialPrompt.take(500))
                .putBoolean(KEY_USE_GPU, settings.useGpu)
                .apply()
        }
    }
}
