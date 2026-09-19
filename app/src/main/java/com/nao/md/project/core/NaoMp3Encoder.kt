package com.nao.md.project.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * Encoder MP3 100% di perangkat lewat LAME (native, via JNI/TAndroidLame),
 * menggantikan [NaoMp3RemoteEncoder] (ApyHub) yang butuh API key + internet
 * dan sering gagal kalau key habis kuota/kadaluarsa.
 *
 * Alurnya sama seperti [NaoAudioTranscoder]: decode trek audio sumber ke PCM
 * lewat MediaCodec decoder bawaan sistem, lalu suapkan tiap batch PCM ke LAME
 * untuk diencode jadi MP3 dan ditulis langsung ke berkas keluaran.
 *
 * Setelah kelas ini terpasang, panggilan ke [NaoMp3RemoteEncoder] di
 * MainActivity bisa dihapus sepenuhnya -- lihat transcodeAudioLocally().
 */
object NaoMp3Encoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Encode trek audio dari [source] menjadi MP3 di [target].
     * [bitRateKbps] default 192kbps (VBR/CBR standar, kualitas bagus untuk musik).
     * [onProgress] dipanggil berkala dengan (done, total) pada skala 0..100.
     */
    fun encode(
        source: File,
        target: File,
        bitRateKbps: Int = 192,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) { trackIndex = i; break }
        }
        if (trackIndex < 0) {
            extractor.release()
            throw NaoAudioExtractor.NoAudioTrack("Berkas ini tidak punya trek audio.")
        }
        extractor.selectTrack(trackIndex)

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        // LAME cuma mendukung mono/stereo -- clamp kalau sumbernya multichannel.
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        val durationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutChannels(channelCount)
            .setOutBitrate(bitRateKbps)
            .setOutSampleRate(sampleRate)
            .setQuality(3)
            .build()

        // Ukuran buffer output sesuai rekomendasi resmi LAME:
        // 1.25 * jumlah sample input + 7200 byte cadangan header/frame.
        val mp3Buf = ByteArray(1_048_576)
        val out = FileOutputStream(target)
        var totalWritten = 0L

        try {
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
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
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outIndex)!!
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val pcm = ShortArray(bufferInfo.size / 2)
                        outBuf.asShortBuffer().get(pcm)

                        val samplesPerChannel = pcm.size / channelCount
                        if (samplesPerChannel > 0) {
                            val left = ShortArray(samplesPerChannel)
                            val right: ShortArray
                            if (channelCount == 2) {
                                right = ShortArray(samplesPerChannel)
                                for (i in 0 until samplesPerChannel) {
                                    left[i] = pcm[i * 2]
                                    right[i] = pcm[i * 2 + 1]
                                }
                            } else {
                                right = left
                                System.arraycopy(pcm, 0, left, 0, samplesPerChannel)
                            }
                            val n = lame.encode(left, right, samplesPerChannel, mp3Buf)
                            if (n > 0) {
                                out.write(mp3Buf, 0, n)
                                totalWritten += n
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            val flushed = lame.flush(mp3Buf)
            if (flushed > 0) {
                out.write(mp3Buf, 0, flushed)
                totalWritten += flushed
            }
        } finally {
            try { out.close() } catch (_: Exception) {}
            try { lame.close() } catch (_: Exception) {}
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            extractor.release()
        }

        if (totalWritten <= 0L) {
            target.delete()
            throw NaoAudioExtractor.NoAudioTrack("Encoder MP3 tidak menghasilkan data.")
        }
        onProgress(100L, 100L)
    }
}
