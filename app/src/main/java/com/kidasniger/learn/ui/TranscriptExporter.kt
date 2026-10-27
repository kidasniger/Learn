package com.kidasniger.learn.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.kidasniger.learn.data.TranscriptFormats
import com.kidasniger.learn.data.TranscriptSegment
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object TranscriptExporter {
    fun write(
        context: Context,
        uri: Uri,
        format: String,
        title: String,
        text: String,
        segments: List<TranscriptSegment>
    ) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Impossible de créer le fichier de sortie.")
        output.use { stream ->
            when (format.lowercase(Locale.ROOT)) {
                "pdf" -> writePdf(stream, title, text, segments)
                "srt" -> stream.write(TranscriptFormats.toSrt(segments).toByteArray(StandardCharsets.UTF_8))
                "vtt" -> stream.write(TranscriptFormats.toVtt(segments).toByteArray(StandardCharsets.UTF_8))
                else -> {
                    val body = buildString {
                        append(title).append("\n")
                        append("=".repeat(title.length.coerceAtMost(80))).append("\n\n")
                        append(text.trim()).append("\n")
                    }
                    stream.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    }

    private fun writePdf(
        output: java.io.OutputStream,
        title: String,
        text: String,
        segments: List<TranscriptSegment>
    ) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val maxWidth = pageWidth - margin * 2
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val content = if (segments.isNotEmpty()) {
            segments.joinToString("\n") { "[${formatTime(it.startMs)}] ${it.text}" }
        } else text
        val wrapped = mutableListOf<String>()
        wrapped += wrap(title, titlePaint, maxWidth)
        wrapped += ""
        content.split('\n').forEach { line ->
            wrapped += wrap(line, bodyPaint, maxWidth).ifEmpty { listOf("") }
        }

        var pageNumber = 1
        var lineIndex = 0
        while (lineIndex < wrapped.size) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var y = margin + 4f
            var count = 0
            while (lineIndex < wrapped.size && y < pageHeight - margin && count < 48) {
                val line = wrapped[lineIndex]
                val paint = if (lineIndex == 0) titlePaint else bodyPaint
                page.canvas.drawText(line, margin, y, paint)
                y += if (lineIndex == 0) 28f else 17f
                lineIndex++
                count++
            }
            document.finishPage(page)
            pageNumber++
        }
        try {
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun wrap(value: String, paint: Paint, maxWidth: Float): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var remaining = value.trim()
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maxWidth, null)
            if (count <= 0) count = 1
            if (count < remaining.length) {
                val space = remaining.lastIndexOf(' ', count.coerceAtMost(remaining.lastIndex))
                if (space > 0) count = space
            }
            result += remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trimStart()
        }
        return result
    }

    private fun formatTime(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
