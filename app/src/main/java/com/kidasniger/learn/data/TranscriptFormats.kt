package com.kidasniger.learn.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class TrimmedAudio(
    val samples: FloatArray,
    val leadingSamples: Int
)

/**
 * Pure local helpers used by the transcript editor and export actions.
 * The summaries and task extraction are deliberately heuristic, not generative AI.
 */
object TranscriptFormats {
    fun plainText(segments: List<TranscriptSegment>): String =
        segments.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun toSrt(segments: List<TranscriptSegment>): String =
        segments.filter { it.text.isNotBlank() }.mapIndexed { index, segment ->
            "${index + 1}\n${timestamp(segment.startMs, ',')} --> ${timestamp(segment.endMs.coerceAtLeast(segment.startMs + 1), ',')}\n${segment.text.trim()}\n"
        }.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }

    fun toVtt(segments: List<TranscriptSegment>): String =
        "WEBVTT\n\n" + segments.filter { it.text.isNotBlank() }.map { segment ->
            "${timestamp(segment.startMs, '.')} --> ${timestamp(segment.endMs.coerceAtLeast(segment.startMs + 1), '.')}\n${segment.text.trim()}\n"
        }.joinToString("\n")

    fun serialize(segments: List<TranscriptSegment>): String =
        segments.joinToString("\n") { segment ->
            val encoded = Base64.getEncoder().encodeToString(segment.text.toByteArray(StandardCharsets.UTF_8))
            "${segment.startMs}\t${segment.endMs}\t$encoded"
        }

    fun deserialize(value: String): List<TranscriptSegment> {
        if (value.isBlank()) return emptyList()
        return value.lineSequence().mapNotNull { line ->
            val columns = line.split('\t', limit = 3)
            if (columns.size != 3) return@mapNotNull null
            try {
                TranscriptSegment(
                    startMs = columns[0].toLong(),
                    endMs = columns[1].toLong(),
                    text = String(Base64.getDecoder().decode(columns[2]), StandardCharsets.UTF_8)
                )
            } catch (_: Exception) {
                null
            }
        }.toList()
    }

    fun trimSilence(
        input: FloatArray,
        sampleRate: Int = 16_000,
        threshold: Float = 0.006f,
        paddingMs: Int = 220
    ): TrimmedAudio {
        if (input.isEmpty()) return TrimmedAudio(input, 0)
        var first = -1
        var last = -1
        for (index in input.indices) {
            if (kotlin.math.abs(input[index]) >= threshold) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first < 0) return TrimmedAudio(FloatArray(0), 0)
        val padding = (sampleRate * paddingMs / 1000).coerceAtLeast(0)
        val start = (first - padding).coerceAtLeast(0)
        val endExclusive = (last + padding + 1).coerceAtMost(input.size)
        return TrimmedAudio(input.copyOfRange(start, endExclusive), start)
    }

    fun summarize(text: String, maxSentences: Int = 3): String {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return ""
        if (sentences.size <= maxSentences) return sentences.joinToString(" ")
        val chosen = linkedSetOf<Int>()
        chosen.add(0)
        if (sentences.size > 3) chosen.add(sentences.size / 2)
        chosen.add(sentences.lastIndex)
        sentences.filterIndexed { index, sentence ->
            sentence.length >= 45 && index !in chosen
        }.take((maxSentences - chosen.size).coerceAtLeast(0)).forEach { sentence ->
            chosen.add(sentences.indexOf(sentence))
        }
        return chosen.sorted().take(maxSentences).map { sentences[it] }.joinToString(" ")
    }

    fun extractKeyIdeas(text: String, maxItems: Int = 8): List<String> {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return emptyList()
        return sentences
            .filter { it.length >= 35 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedByDescending { scoreIdea(it) }
            .take(maxItems)
            .ifEmpty { sentences.take(maxItems) }
    }

    fun extractTasks(text: String, maxItems: Int = 12): List<String> {
        val markers = listOf(
            "il faut", "nous devons", "je dois", "on doit", "à faire",
            "action:", "décision:", "nous allons", "je vais", "prévoir",
            "planifier", "responsable de", "faire le suivi"
        )
        return splitSentences(text)
            .filter { sentence -> markers.any { sentence.lowercase(Locale.ROOT).contains(it) } }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(maxItems)
    }

    private fun scoreIdea(sentence: String): Int {
        val lower = sentence.lowercase(Locale.ROOT)
        val markers = listOf("important", "objectif", "résultat", "problème", "solution", "décision", "conclusion", "priorité")
        return markers.count { lower.contains(it) } * 10 + (sentence.length.coerceAtMost(300) / 50)
    }

    private fun splitSentences(text: String): List<String> =
        text.replace('\n', ' ')
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun timestamp(milliseconds: Long, separator: Char): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, millis)
    }
}
