package com.nao.md.project.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Penyimpanan berkas ke folder Download/NAO yang aman untuk Android 8 ke atas.
 *
 * `MediaStore.Downloads` baru ada sejak Android 10 (API 29). Sebelumnya
 * referensi kelas itu langsung memicu NoSuchFieldError/NoClassDefFoundError,
 * sehingga fitur unduh selalu gagal (bahkan crash) di Android 8 dan 9.
 * Di bawah API 29 berkas ditulis langsung ke folder Download publik lalu
 * didaftarkan ke media scanner supaya tetap terlihat di galeri/manajer berkas.
 */
object NaoMediaStoreCompat {

    /** Tulis [write] menjadi berkas [filename] di Download/NAO. */
    fun saveToDownloads(
        context: Context,
        filename: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/NAO")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Tidak bisa membuat berkas di Download/NAO")
            context.contentResolver.openOutputStream(uri)?.use(write)
                ?: throw Exception("Tidak bisa menulis berkas di Download/NAO")
            return uri
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NAO"
        )
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw Exception("Tidak bisa membuat folder Download/NAO")
        }
        val target = uniqueFile(dir, filename)
        target.outputStream().use(write)
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(target.absolutePath),
                arrayOf(mimeType),
                null
            )
        } catch (_: Exception) {
        }
        return try {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                target
            )
        } catch (_: Exception) {
            Uri.fromFile(target)
        }
    }

    private fun uniqueFile(dir: File, filename: String): File {
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var candidate = File(dir, filename)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i)$ext")
            i++
        }
        return candidate
    }
}
