package com.kidasniger.learn.ui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

object AudioFileDecoder {
    private const val RATE = 16_000
    private const val MAX_MINUTES = 20
    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i; sourceFormat = format; break
                }
            }
            if (track < 0 || sourceFormat == null) throw IOException("Aucune piste audio compatible.")
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Format audio non reconnu.")
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val output = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var framesRead = 0L
            var lastTarget = -1L
            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val maxSamples = RATE * MAX_MINUTES * 60
            while (!outputDone && output.size < maxSamples) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: throw IOException("Buffer audio indisponible.")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("Audio décodé inaccessible.")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val pcm = buffer.slice().order(ByteOrder.nativeOrder())
                            val bytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val frameBytes = channels * bytes
                            val count = if (frameBytes > 0) pcm.remaining() / frameBytes else 0
                            for (frame in 0 until count) {
                                val base = frame * frameBytes
                                var sum = 0f
                                for (channel in 0 until channels) {
                                    val off = base + channel * bytes
                                    sum += when (encoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> pcm.getFloat(off).coerceIn(-1f, 1f)
                                        AudioFormat.ENCODING_PCM_8BIT -> ((pcm.get(off).toInt() and 0xFF) - 128) / 128f
                                        else -> pcm.getShort(off).toInt() / 32768f
                                    }
                                }
                                val target = framesRead * RATE / sampleRate
                                if (target > lastTarget) { output.add(sum / channels); lastTarget = target }
                                framesRead++
                                if (output.size >= maxSamples) break
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            if (output.isEmpty()) throw IOException("Le fichier ne contient aucun son décodable.")
            if (output.size >= maxSamples) throw IOException("L'import audio est limité à ${MAX_MINUTES} minutes.")
            return output.toFloatArray()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Décodage audio impossible : ${error.message ?: "format non pris en charge"}.", error)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }
}
