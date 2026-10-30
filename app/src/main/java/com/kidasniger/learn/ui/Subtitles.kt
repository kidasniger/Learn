package com.kidasniger.learn.ui

import com.kidasniger.learn.WhisperSegment
import java.util.Locale

/**
 * Génération de sous-titres : à partir des segments natifs horodatés du
 * moteur Whisper, ou par estimation par phrases en secours.
 */
internal fun formatSubtitleTime(ms: Long, webVtt: Boolean): String {
    val safeMs = ms.coerceAtLeast(0L)
    val h = safeMs / 3_600_000
    val m = (safeMs / 60_000) % 60
    val s = (safeMs / 1_000) % 60
    val millis = safeMs % 1_000
    return "%02d:%02d:%02d%s%03d".format(Locale.ROOT, h, m, s, if (webVtt) "." else ",", millis)
}

/** Sous-titres construits depuis les horodatages natifs renvoyés par whisper.cpp. */
internal fun subtitlesFromSegments(segments: List<WhisperSegment>, webVtt: Boolean): String {
    val cleaned = segments.filter { it.text.isNotBlank() }
    val out = StringBuilder()
    if (webVtt) out.append("WEBVTT\n\n")
    cleaned.forEachIndexed { index, segment ->
        if (!webVtt) out.append(index + 1).append('\n')
        val start = segment.t0Ms.coerceAtLeast(0L)
        val end = maxOf(segment.t1Ms, start)
        out.append(formatSubtitleTime(start, webVtt)).append(" --> ")
            .append(formatSubtitleTime(end, webVtt)).append('\n')
            .append(segment.text.trim()).append("\n\n")
    }
    return out.toString()
}

/** Sous-titres estimés (secours quand le moteur ne renvoie pas de segments). */
internal fun makeEstimatedSubtitles(text: String, webVtt: Boolean): String {
    val sentences = text.replace("\r", " ").replace("\n", " ")
        .split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val out = StringBuilder()
    if (webVtt) out.append("WEBVTT\n\n")
    var timeMs = 0L
    sentences.forEachIndexed { index, sentence ->
        val duration = maxOf(1800L, sentence.split("\\s+".toRegex()).size * 320L)
        if (!webVtt) out.append(index + 1).append('\n')
        out.append(formatSubtitleTime(timeMs, webVtt)).append(" --> ")
            .append(formatSubtitleTime(timeMs + duration, webVtt)).append('\n')
            .append(sentence).append("\n\n")
        timeMs += duration
    }
    return out.toString()
}
