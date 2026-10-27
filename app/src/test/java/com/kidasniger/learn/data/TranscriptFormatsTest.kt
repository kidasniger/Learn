package com.kidasniger.learn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatsTest {
    @Test
    fun formatsSrtAndVttTimestamps() {
        val segments = listOf(
            TranscriptSegment(1250, 2850, "Bonjour."),
            TranscriptSegment(3600, 4900, "Deuxième phrase.")
        )
        val srt = TranscriptFormats.toSrt(segments)
        assertTrue(srt.contains("00:00:01,250 --> 00:00:02,850"))
        assertTrue(srt.contains("1\n"))
        val vtt = TranscriptFormats.toVtt(segments)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:03.600 --> 00:00:04.900"))
    }

    @Test
    fun segmentSerializationPreservesUnicodeAndSeparators() {
        val original = listOf(TranscriptSegment(10, 900, "Bonjour\tNiger — ligne 1\nligne 2"))
        assertEquals(original, TranscriptFormats.deserialize(TranscriptFormats.serialize(original)))
    }

    @Test
    fun silenceTrimmingKeepsSpeechAndContext() {
        val audio = FloatArray(16_000) { index ->
            if (index in 4_000..11_000) 0.2f else 0f
        }
        val trimmed = TranscriptFormats.trimSilence(audio, paddingMs = 100)
        assertTrue(trimmed.samples.size < audio.size)
        assertTrue(trimmed.leadingSamples > 0)
        assertTrue(TranscriptFormats.trimSilence(FloatArray(300)).samples.isEmpty())
    }

    @Test
    fun flashcardsAreBuiltFromSentencesWithAnswers() {
        val cards = TranscriptFormats.generateFlashcards(
            "La photosynthèse transforme la lumière en énergie chimique. Les plantes absorbent le dioxyde de carbone."
        )
        assertEquals(2, cards.size)
        assertTrue(cards.all { it.question.isNotBlank() && it.answer.isNotBlank() })
    }

    @Test
    fun taskExtractorFindsActionItems() {
        val tasks = TranscriptFormats.extractTasks(
            "La réunion commence. Nous devons envoyer le rapport demain. Le budget a été validé."
        )
        assertEquals(listOf("Nous devons envoyer le rapport demain."), tasks)
    }
}
