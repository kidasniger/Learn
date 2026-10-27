package com.kidasniger.learn.ui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes common Android-supported audio formats to mono 16 kHz float samples.
 * Only one 30-second chunk is retained at a time, so long imports avoid one giant PCM allocation.
 */
internal object AudioFileDecoder {
    private const val TARGET_RATE = 16_000
    private const val CHUNK_SECONDS = 30

    fun decode(
        context: Context,
        uri: Uri,
        onChunk: (samples: FloatArray, startMs: Long) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var descriptor: android.os.ParcelFileDescriptor? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Impossible d'ouvrir le fichier audio.")
            extractor.setDataSource(descriptor.fileDescriptor)

            var audioTrack = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    sourceFormat = format
                    break
                }
            }
            if (audioTrack < 0 || sourceFormat == null) {
                throw IOException("Aucune piste audio compatible n'a été trouvée.")
            }

            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Format audio inconnu.")
            val originalRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (originalRate <= 0) throw IOException("Fréquence audio invalide.")
            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            var outputFormat = sourceFormat
            var sampleRate = originalRate
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var sourceChunk = FloatArray((sampleRate * CHUNK_SECONDS).coerceAtLeast(1))
            var sourceCount = 0
            var outputCursorSamples = 0L
            var chunksEmitted = 0

            fun emitChunk() {
                if (sourceCount <= 0) return
                val nativeChunk = sourceChunk.copyOf(sourceCount)
                val converted = resample(nativeChunk, sampleRate, TARGET_RATE)
                if (converted.isNotEmpty()) {
                    val startMs = outputCursorSamples * 1000L / TARGET_RATE
                    outputCursorSamples += converted.size.toLong()
                    onChunk(converted, startMs)
                    chunksEmitted++
                }
                sourceCount = 0
            }

            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Tampon d'entrée audio indisponible.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        sourceChunk = FloatArray((sampleRate * CHUNK_SECONDS).coerceAtLeast(1))
                        sourceCount = 0
                    }
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> {
                                    val bytesPerFrame = channels * 2
                                    while (pcm.remaining() >= bytesPerFrame) {
                                        var mixed = 0f
                                        repeat(channels) { mixed += pcm.short / 32768f }
                                        pushSample(mixed / channels, sourceChunk, sourceCount).also {
                                            sourceCount = it.first
                                            if (it.second) emitChunk()
                                        }
                                    }
                                }
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val bytesPerFrame = channels * 4
                                    while (pcm.remaining() >= bytesPerFrame) {
                                        var mixed = 0f
                                        repeat(channels) { mixed += pcm.float.coerceIn(-1f, 1f) }
                                        pushSample(mixed / channels, sourceChunk, sourceCount).also {
                                            sourceCount = it.first
                                            if (it.second) emitChunk()
                                        }
                                    }
                                }
                                else -> throw IOException("Encodage PCM non pris en charge : $pcmEncoding.")
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEnded = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            emitChunk()
            if (chunksEmitted == 0) throw IOException("Le fichier ne contient pas d'audio décodable.")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Décodage audio impossible : ${error.message ?: "format non pris en charge"}.", error)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
            try { descriptor?.close() } catch (_: Exception) { }
        }
    }

    private fun pushSample(
        sample: Float,
        destination: FloatArray,
        currentCount: Int
    ): Pair<Int, Boolean> {
        if (currentCount >= destination.size) return currentCount to true
        destination[currentCount] = sample.coerceIn(-1f, 1f)
        val next = currentCount + 1
        return next to (next == destination.size)
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (fromRate == toRate) return input
        val outputSize = (input.size.toLong() * toRate / fromRate).coerceAtLeast(1L).toInt()
        val output = FloatArray(outputSize)
        for (index in output.indices) {
            val position = index.toDouble() * fromRate / toRate
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - left).toFloat()
            output[index] = input[left] * (1f - fraction) + input[right] * fraction
        }
        return output
    }
}
