package com.kidasniger.learn.ui

import com.kidasniger.learn.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    @Test
    fun `native segments produce a correct srt`() {
        val segments = listOf(
            WhisperSegment(0L, 2_500L, " Bonjour. "),
            WhisperSegment(2_600L, 5_100L, "Comment allez-vous ?")
        )
        val srt = subtitlesFromSegments(segments, webVtt = false)
        val expected = "1\n00:00:00,000 --> 00:00:02,500\nBonjour.\n\n" +
            "2\n00:00:02,600 --> 00:00:05,100\nComment allez-vous ?\n\n"
        assertEquals(expected, srt)
    }

    @Test
    fun `native segments produce webvtt with dot separator`() {
        val segments = listOf(WhisperSegment(3_661_000L, 3_662_000L, " Fin."))
        val vtt = subtitlesFromSegments(segments, webVtt = true)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("01:01:01.000 --> 01:01:02.000"))
        assertTrue(!vtt.contains(","))
    }

    @Test
    fun `blank segments are filtered out`() {
        val segments = listOf(
            WhisperSegment(0L, 1_000L, "   "),
            WhisperSegment(1_000L, 2_000L, "Présent.")
        )
        val srt = subtitlesFromSegments(segments, webVtt = false)
        assertTrue(srt.startsWith("1\n"))
        assertTrue(!srt.contains("2\n"))
    }

    @Test
    fun `estimated subtitles fall back to sentence timing`() {
        val estimated = makeEstimatedSubtitles("Bonjour. Comment allez-vous ?", webVtt = false)
        assertTrue(estimated.contains("00:00:00,000 --> 00:00:"))
        assertTrue(estimated.contains("Bonjour."))
        assertTrue(estimated.contains("Comment allez-vous ?"))
    }

    @Test
    fun `time formatting covers hours and milliseconds`() {
        assertEquals("00:00:00,000", formatSubtitleTime(0L, webVtt = false))
        assertEquals("00:00:01,500", formatSubtitleTime(1_500L, webVtt = false))
        assertEquals("00:01:01,001", formatSubtitleTime(61_001L, webVtt = false))
        assertEquals("01:00:00.000", formatSubtitleTime(3_600_000L, webVtt = true))
    }
}
