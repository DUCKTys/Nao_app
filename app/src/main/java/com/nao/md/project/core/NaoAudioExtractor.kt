package com.nao.md.project.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Ekstraksi audio dari video langsung di perangkat.
 *
 * Versi lama mengunggah seluruh video ke server lalu mengunduh hasilnya —
 * lambat dan sering gagal (timeout / server sibuk), sehingga baru berhasil
 * setelah beberapa kali percobaan. Di sini trek audio disalin apa adanya
 * (tanpa encode ulang) memakai MediaExtractor + MediaMuxer, jadi prosesnya
 * hitungan detik, tanpa internet, dan hasilnya identik dengan audio aslinya.
 */
object NaoAudioExtractor {

    class NoAudioTrack(message: String) : Exception(message)

    /** Ekstensi keluaran yang cocok untuk trek audio di [source]. */
    fun outputExtension(context: Context, source: Uri): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val index = audioTrack(extractor) ?: return ".m4a"
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            return extFor(mime)
        } catch (_: Exception) {
            return ".m4a"
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Salin trek audio ke [output]. [onProgress] menerima (bytesDone, bytesTotal)
     * berdasarkan durasi yang sudah diproses agar bar progres bergerak halus.
     */
    fun extract(
        context: Context,
        source: Uri,
        output: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)
            val index = audioTrack(extractor)
                ?: throw NoAudioTrack("Video ini tidak punya trek audio.")
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val container = containerFor(mime)
            extractor.selectTrack(index)

            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, container)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            val maxInput = try {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } catch (_: Exception) { 512 * 1024 }
            val buffer = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            var written = 0L

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = sampleFlags(extractor)
                muxer.writeSampleData(outTrack, buffer, info)
                written += size
                if (durationUs > 0) {
                    val pct = (info.presentationTimeUs.coerceAtLeast(0L) * 100L / durationUs).coerceIn(0L, 99L)
                    onProgress(pct, 100L)
                }
                extractor.advance()
            }
            muxer.stop()
            onProgress(100L, 100L)
            if (written <= 0L) throw NoAudioTrack("Trek audio kosong.")
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun sampleFlags(extractor: MediaExtractor): Int {
        var flags = 0
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    private fun audioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun extFor(mime: String): String = when {
        mime.contains("mp4a") || mime.contains("aac") -> ".m4a"
        mime.contains("opus") -> ".ogg"
        mime.contains("vorbis") -> ".ogg"
        mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
        mime.contains("amr") -> ".amr"
        mime.contains("flac") -> ".flac"
        else -> ".m4a"
    }

    private fun containerFor(mime: String): Int = when {
        mime.contains("opus") || mime.contains("vorbis") ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    }
}
