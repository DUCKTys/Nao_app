package com.nao.md.project.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Transcode audio 100% di perangkat, tanpa server.
 *
 * Menggantikan jalur lama yang mengunggah berkas ke server transcode
 * (`extractAudioOnServer` / `ApiClient` -> `176.100.37.77:30157`, sudah lama
 * mati). Alur di sini: decode trek audio sumber ke PCM lewat MediaCodec
 * decoder bawaan sistem, lalu:
 *  - "wav"  -> tulis PCM apa adanya + header WAV (tidak perlu encoder).
 *  - "m4a"  -> encode ke AAC-LC lewat MediaCodec encoder, mux ke MP4.
 *  - "ogg"  -> encode ke Opus lewat MediaCodec encoder (perlu Android 10+,
 *              API 29), mux ke kontainer OGG.
 *  - "mp3"  -> BELUM didukung di sini; lihat NaoMp3Encoder (encoder LAME
 *              lewat JNI) yang jadi pelengkapnya, karena Android tidak
 *              punya encoder MP3 bawaan.
 *
 * Semua path di atas tidak butuh dependency baru, tidak butuh internet,
 * dan tidak bergantung pada library pihak ketiga yang bisa mati sewaktu-waktu
 * (seperti kasus com.arthenica:ffmpeg-kit-* yang binary-nya sudah dicabut
 * dari Maven Central sejak awal 2025).
 */
object NaoAudioTranscoder {

    class UnsupportedTarget(message: String) : Exception(message)

    private const val TIMEOUT_US = 10_000L

    /**
     * Transcode trek audio dari [source] menjadi format [formatId]
     * ("wav", "m4a", "ogg") ke berkas [target]. [onProgress] dipanggil
     * berkala dengan (done, total) pada skala 0..100.
     */
    fun transcode(
        context: Context,
        source: File,
        target: File,
        formatId: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        when (formatId.lowercase()) {
            "wav" -> decodeToWav(source, target, onProgress)
            "m4a" -> encodeVia(source, target, "audio/mp4a-latm", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, bitRate = 192_000, onProgress)
            "ogg" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw UnsupportedTarget("Ekspor OGG butuh Android 10 ke atas di perangkat ini.")
                }
                encodeVia(source, target, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, bitRate = 160_000, onProgress)
            }
            "mp3" -> throw UnsupportedTarget("MP3 diproses lewat NaoMp3Encoder (LAME/JNI), bukan NaoAudioTranscoder.")
            else -> throw UnsupportedTarget("Format tidak dikenal: $formatId")
        }
    }

    private class TrackFormat(val sampleRate: Int, val channelCount: Int)

    private fun openAudioTrack(source: File): Pair<MediaExtractor, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                return extractor to i
            }
        }
        extractor.release()
        throw NaoAudioExtractor.NoAudioTrack("Berkas ini tidak punya trek audio.")
    }

    // ---------------- WAV (tanpa encoder, salin PCM apa adanya) ----------------

    private fun decodeToWav(source: File, target: File, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val (extractor, trackIndex) = openAudioTrack(source)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val raf = RandomAccessFile(target, "rw")
        var dataSize = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            raf.write(ByteArray(44)) // placeholder header, dipatch di akhir
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress((pts.coerceAtLeast(0L) * 100L / durationUs).coerceIn(0L, 99L), 100L)
                            }
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(chunk)
                        raf.write(chunk)
                        dataSize += chunk.size
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            writeWavHeader(raf, dataSize, sampleRate, channelCount)
        } finally {
            raf.close()
            decoder.stop(); decoder.release()
            extractor.release()
        }
        if (dataSize <= 0L) { target.delete(); throw NaoAudioExtractor.NoAudioTrack("Trek audio kosong.") }
        onProgress(100L, 100L)
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataSize: Long, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.write(intLE((36 + dataSize).toInt()))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intLE(16))
        raf.write(shortLE(1)) // PCM
        raf.write(shortLE(channels))
        raf.write(intLE(sampleRate))
        raf.write(intLE(byteRate))
        raf.write(shortLE(blockAlign))
        raf.write(shortLE(16)) // bit depth
        raf.writeBytes("data")
        raf.write(intLE(dataSize.toInt()))
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // ---------------- M4A (AAC) & OGG (Opus): decode PCM -> encode ----------------

    private fun encodeVia(
        source: File,
        target: File,
        outMime: String,
        muxerFormat: Int,
        bitRate: Int,
        onProgress: (Long, Long) -> Unit
    ) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val (extractor, trackIndex) = openAudioTrack(source)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val inMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }

        val decoder = MediaCodec.createDecoderByType(inMime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val outFormat = MediaFormat.createAudioFormat(outMime, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (outMime == "audio/mp4a-latm") {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val encoder: MediaCodec
        try {
            encoder = MediaCodec.createEncoderByType(outMime)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            decoder.stop(); decoder.release(); extractor.release()
            throw UnsupportedTarget("Perangkat ini tidak punya encoder $outMime: ${e.message}")
        }
        encoder.start()

        val muxer = MediaMuxer(target.absolutePath, muxerFormat)
        var muxerTrack = -1
        var muxerStarted = false
        val encInfo = MediaCodec.BufferInfo()
        val decInfo = MediaCodec.BufferInfo()
        var ptUs = 0L
        val bytesPerFrame = channelCount * 2 // PCM 16-bit

        fun drainEncoder(blockUntilEos: Boolean) {
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIndex)!!
                        if (encInfo.size > 0 && muxerStarted) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxerTrack, outBuf, encInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!blockUntilEos) return
                }
            }
        }

        fun feedEncoder(chunk: ByteArray, isLast: Boolean) {
            var offset = 0
            while (offset < chunk.size || isLast) {
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = encoder.getInputBuffer(inIndex)!!
                    val take = minOf(inBuf.capacity(), chunk.size - offset)
                    if (take > 0) {
                        inBuf.clear(); inBuf.put(chunk, offset, take)
                    }
                    val flags = if (isLast && offset + take >= chunk.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    encoder.queueInputBuffer(inIndex, 0, take, ptUs, flags)
                    ptUs += take.toLong() * 1_000_000L / (sampleRate.toLong() * bytesPerFrame)
                    offset += take
                    if (isLast && offset >= chunk.size) { drainEncoder(true); return }
                }
                drainEncoder(false)
                if (offset >= chunk.size && !isLast) return
            }
        }

        try {
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress((pts.coerceAtLeast(0L) * 100L / durationUs).coerceIn(0L, 98L), 100L)
                            }
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val isLastChunk = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (decInfo.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(decInfo.size)
                        outBuf.position(decInfo.offset)
                        outBuf.get(chunk)
                        feedEncoder(chunk, isLastChunk)
                    } else if (isLastChunk) {
                        feedEncoder(ByteArray(0), true)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (isLastChunk) sawOutputEos = true
                }
            }
        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            extractor.release()
        }
        if (!muxerStarted) { target.delete(); throw NaoAudioExtractor.NoAudioTrack("Encoder tidak menghasilkan data.") }
        onProgress(100L, 100L)
    }
}
