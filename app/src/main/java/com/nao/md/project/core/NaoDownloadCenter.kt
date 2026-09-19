package com.nao.md.project.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pusat unduhan Nao MD (halaman "Berkas").
 *
 * Semua unduhan dari Downloader, "Download semua media" dan AI Upscaler
 * dialihkan ke sini supaya pengguna bisa memantau progres, jeda, lanjutkan,
 * dan menghapus media persis seperti manajer unduhan di browser.
 *
 * Berkas ditulis dulu ke cache (`downloads/`) agar bisa dilanjutkan dengan
 * HTTP Range, lalu dipindahkan ke MediaStore `Download/NAO` saat selesai.
 */
object NaoDownloadCenter {

    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    class Job(
        val id: String,
        val name: String,
        val type: String,
        val url: String,
        val backendId: String,
        val filename: String
    ) {
        @Volatile var total: Long = -1L
        @Volatile var done: Long = 0L
        @Volatile var state: State = State.QUEUED
        @Volatile var error: String = ""
        @Volatile var savedUri: String = ""
        @Volatile var startedAt: Long = System.currentTimeMillis()
        @Volatile var speedBps: Long = 0L
        /**
         * Dipanggil sekali di thread utama begitu unduhan ini selesai —
         * dipakai update aplikasi untuk langsung menawarkan pemasangan APK
         * begitu file-nya siap, tanpa pengguna harus bolak-balik ke tab Berkas.
         */
        @Volatile var onComplete: ((Job) -> Unit)? = null
        /**
         * Pekerjaan lokal (mis. ekstrak audio dari video di perangkat).
         * Kalau diisi, unduhan tidak lewat jaringan: lambda menulis hasilnya
         * ke berkas sementara yang diberikan lalu memanggil [report].
         */
        @Volatile var local: ((Job, File) -> Unit)? = null
        internal val cancelFlag = AtomicBoolean(false)

        /** Dipakai pekerjaan lokal untuk melaporkan progres. */
        fun report(done: Long, total: Long) {
            this.done = done
            this.total = total
        }

        val percent: Int
            get() = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else if (state == State.DONE) 100 else 0

        /** Perkiraan sisa waktu dalam detik, -1 kalau belum bisa dihitung. */
        val etaSeconds: Long
            get() {
                if (state != State.RUNNING || total <= 0 || speedBps <= 0) return -1
                return ((total - done) / speedBps).coerceAtLeast(0)
            }

        val resumable: Boolean get() = url.startsWith("http")
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val items = ArrayList<Job>()

    /** Dipanggil setiap ada perubahan progres/status supaya UI bisa refresh. */
    @Volatile var listener: (() -> Unit)? = null

    fun jobs(): List<Job> = synchronized(items) { items.toList() }

    fun activeCount(): Int = synchronized(items) {
        items.count { it.state == State.RUNNING || it.state == State.QUEUED }
    }

    fun enqueue(
        context: Context,
        name: String,
        type: String,
        url: String,
        backendId: String = "",
        onComplete: ((Job) -> Unit)? = null
    ): Job {
        val ext = extFor(type, url)
        val base = safeName(name)
        val filename = if (hasMediaExtension(base)) base else base + ext
        val job = Job(
            id = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
            name = base,
            type = type,
            url = url,
            backendId = backendId,
            filename = uniqueName(context, filename)
        )
        job.onComplete = onComplete
        synchronized(items) { items.add(0, job) }
        persist(context)
        start(context, job)
        return job
    }

    /**
     * Daftarkan pekerjaan lokal (tanpa jaringan) supaya tetap muncul di
     * indikator unduhan: contohnya ekstraksi audio dari video.
     */
    fun enqueueLocal(
        context: Context,
        name: String,
        type: String,
        filename: String,
        work: (Job, File) -> Unit
    ): Job {
        val job = Job(
            id = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
            name = safeName(name),
            type = type,
            url = "",
            backendId = "",
            filename = uniqueName(context, safeName(filename))
        )
        job.local = work
        synchronized(items) { items.add(0, job) }
        start(context, job)
        return job
    }

    fun pause(context: Context, job: Job) {
        if (job.state != State.RUNNING && job.state != State.QUEUED) return
        job.cancelFlag.set(true)
        job.state = State.PAUSED
        notifyChange(context)
    }

    fun resume(context: Context, job: Job) {
        if (job.state == State.RUNNING || job.state == State.DONE) return
        if (!job.resumable) {
            // Backend tanpa dukungan Range: mulai ulang dari awal.
            partFile(context, job).delete()
            job.done = 0L
        }
        job.error = ""
        start(context, job)
    }

    /** Hapus dari daftar sekaligus hapus berkas yang sudah tersimpan. */
    fun remove(context: Context, job: Job) {
        job.cancelFlag.set(true)
        synchronized(items) { items.remove(job) }
        try { partFile(context, job).delete() } catch (_: Exception) {}
        if (job.savedUri.isNotBlank()) {
            try { context.contentResolver.delete(Uri.parse(job.savedUri), null, null) } catch (_: Exception) {}
        }
        persist(context)
        notifyChange(context)
    }

    /** Hilangkan dari daftar tanpa menghapus berkasnya (auto-hide 100%). */
    fun dismiss(context: Context, job: Job) {
        synchronized(items) { items.remove(job) }
        persist(context)
        notifyChange(context)
    }

    fun clearFinished(context: Context) {
        synchronized(items) {
            items.removeAll { it.state == State.DONE || it.state == State.FAILED }
        }
        persist(context)
        notifyChange(context)
    }

    // ---------------------------------------------------------------- engine

    private fun start(context: Context, job: Job) {
        job.cancelFlag.set(false)
        job.state = State.QUEUED
        notifyChange(context)
        pool.execute {
            try {
                job.state = State.RUNNING
                job.startedAt = System.currentTimeMillis()
                notifyChange(context)
                val localWork = job.local
                when {
                    localWork != null -> {
                        partFile(context, job).parentFile?.mkdirs()
                        val target = partFile(context, job)
                        localWork(job, target)
                        val size = target.length()
                        if (size > 0) { job.done = size; job.total = size }
                        notifyChange(context)
                    }
                    else -> runDirect(context, job)
                }
                if (job.cancelFlag.get()) {
                    job.state = State.PAUSED
                    notifyChange(context)
                    return@execute
                }
                finish(context, job)
            } catch (e: Exception) {
                if (job.cancelFlag.get()) {
                    job.state = State.PAUSED
                } else {
                    job.state = State.FAILED
                    job.error = e.message ?: "Unduhan gagal"
                }
                notifyChange(context)
            }
        }
    }

    /** Unduhan langsung dari URL media: mendukung jeda & lanjut lewat header Range. */
    private fun runDirect(context: Context, job: Job) {
        val part = partFile(context, job)
        var offset = if (part.exists()) part.length() else 0L
        val conn = (URL(job.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            // googlevideo (YouTube CDN) URLs are validated against the
            // client identity that requested them; a generic downloader
            // User-Agent gets a 403 even though the same URL plays fine in
            // NaoMusicPlaybackService. Match the UA InnerTubeClient's
            // resolved stream actually expects for those URLs only, so
            // unrelated downloads keep the app's own identifying UA.
            val ua = if (job.url.contains("googlevideo.com")) {
                com.nao.md.project.music.InnerTubeClient.STREAM_USER_AGENT
            } else {
                "NAO-MD-Downloader/3.0"
            }
            setRequestProperty("User-Agent", ua)
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        val code = conn.responseCode
        if (code == 200 && offset > 0) {
            // Server mengabaikan Range: mulai dari nol.
            part.delete()
            offset = 0L
        }
        if (code !in 200..299) throw Exception("HTTP $code")
        val remaining = conn.contentLengthLong
        job.total = if (remaining >= 0) offset + remaining else -1L
        job.done = offset
        copyStream(context, job, conn.inputStream, part, offset > 0)
        conn.disconnect()
    }

    private fun copyStream(
        context: Context,
        job: Job,
        input: java.io.InputStream,
        part: File,
        append: Boolean
    ) {
        part.parentFile?.mkdirs()
        val buffer = ByteArray(64 * 1024)
        var lastTick = System.currentTimeMillis()
        var lastDone = job.done
        java.io.FileOutputStream(part, append).use { out ->
            input.use { stream ->
                while (true) {
                    if (job.cancelFlag.get()) return
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    job.done += read
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 400) {
                        job.speedBps = ((job.done - lastDone) * 1000L) / (now - lastTick)
                        lastTick = now
                        lastDone = job.done
                        notifyChange(context)
                    }
                }
            }
        }
    }

    private fun finish(context: Context, job: Job) {
        val part = partFile(context, job)
        val uri = NaoMediaStoreCompat.saveToDownloads(
            context,
            job.filename,
            mimeFor(job.filename)
        ) { out -> part.inputStream().use { it.copyTo(out) } }
        part.delete()
        job.savedUri = uri.toString()
        job.total = if (job.total > 0) job.total else job.done
        job.state = State.DONE
        job.speedBps = 0L
        // Update aplikasi (APK) sengaja TIDAK auto-hilang dari daftar —
        // pengguna perlu tetap melihatnya di halaman "Cek Download" sampai
        // benar-benar dipasang, beda dari unduhan lagu/media biasa yang
        // langsung hilang begitu 100% (filenya tetap ada di "Media tersimpan").
        if (job.type != "apk") {
            scheduler.schedule({ dismiss(context, job) }, 2200L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        persist(context)
        notifyChange(context)
        job.onComplete?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(job) }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun notifyChange(context: Context) {
        try { listener?.invoke() } catch (_: Exception) {}
    }

    private fun partFile(context: Context, job: Job) =
        File(File(context.cacheDir, "downloads").apply { mkdirs() }, job.id + ".part")

    fun safeName(s: String) =
        s.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().ifEmpty { "media" }.take(100)

    private fun extFor(type: String, url: String): String {
        val lower = url.lowercase()
        listOf(".mp4", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".aac", ".apk", ".jpg", ".jpeg", ".png", ".webp")
            .firstOrNull { lower.contains(it) }?.let { return it }
        return when (type) {
            "audio" -> ".mp3"
            "image" -> ".jpg"
            "apk" -> ".apk"
            else -> ".mp4"
        }
    }

    /** Ekstensi media yang dikenali aplikasi (dipakai untuk deteksi "nama sudah
     * punya ekstensi"). Sengaja TIDAK memakai `name.contains('.')` karena judul
     * lagu/video sering mengandung titik (mis. "Mr. Rain", "Vol. 2", "feat.")
     * yang membuat ekstensi audio/video gagal ditambahkan sama sekali —
     * itulah sebab unduhan audio tersimpan sebagai berkas generik. */
    private val KNOWN_MEDIA_EXTS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
        "mp4", "webm", "mkv", "mov",
        "jpg", "jpeg", "png", "webp",
        "apk"
    )

    fun hasMediaExtension(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e.isNotEmpty() && e in KNOWN_MEDIA_EXTS
    }

    fun mimeFor(filename: String) = when (filename.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun uniqueName(context: Context, filename: String): String {
        val existing = jobs().map { it.filename }.toSet()
        if (filename !in existing) return filename
        val stem = filename.substringBeforeLast('.', filename)
        val ext = filename.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val candidate = if (ext.isBlank()) "$stem ($i)" else "$stem ($i).$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    // -------------------------------------------------------- persistence

    fun restore(context: Context) {
        synchronized(items) { if (items.isNotEmpty()) return }
        val raw = context.getSharedPreferences("nao_prefs", Context.MODE_PRIVATE)
            .getString("nao_download_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            synchronized(items) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val job = Job(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("type"),
                        o.optString("url"),
                        o.optString("backendId"),
                        o.optString("filename")
                    )
                    job.total = o.optLong("total", -1L)
                    job.done = o.optLong("done", 0L)
                    job.savedUri = o.optString("savedUri")
                    job.state = if (job.savedUri.isNotBlank()) State.DONE else State.PAUSED
                    // Pekerjaan lokal tidak bisa dilanjutkan setelah aplikasi
                    // ditutup, dan yang sudah selesai tidak perlu ditampilkan
                    // lagi di daftar unduhan aktif.
                    if (job.state == State.DONE || (job.url.isBlank() && job.backendId.isBlank())) continue
                    items.add(job)
                }
            }
        } catch (_: Exception) {}
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        jobs().take(60).forEach { job ->
            arr.put(JSONObject().apply {
                put("id", job.id)
                put("name", job.name)
                put("type", job.type)
                put("url", job.url)
                put("backendId", job.backendId)
                put("filename", job.filename)
                put("total", job.total)
                put("done", job.done)
                put("savedUri", job.savedUri)
            })
        }
        context.getSharedPreferences("nao_prefs", Context.MODE_PRIVATE)
            .edit().putString("nao_download_history", arr.toString()).apply()
    }
}
