package com.nao.md.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fast artwork loader for Nao Music.
 *
 * The old loader pushed every thumbnail through one shared single-thread
 * executor, always fetched `maxresdefault.jpg` (huge, and a 404 for most
 * songs), decoded at full resolution and re-encoded a PNG to disk per item.
 * A list of 20 rows therefore rendered one image at a time, slowly.
 *
 * This loader:
 *  - keeps a memory LRU cache (instant re-render on scroll / revisit)
 *  - keeps a disk cache in cacheDir (instant on app restart)
 *  - downloads on a real thread pool (8 parallel) so rows render together
 *  - picks a thumbnail size that matches the view instead of always maxres
 *  - downsamples while decoding (inSampleSize) so decode cost stays low
 *  - guards against recycled views with a per-view tag
 */
object NaoArtworkLoader {

    private val main = Handler(Looper.getMainLooper())

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = ThreadPoolExecutor(
        8, 8, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
    ) { r ->
        Thread(r, "nao-artwork").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }.apply { allowCoreThreadTimeOut(true) }

    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap) -> Unit>>()

    private val TAG_KEY = R.id.nao_artwork_tag

    fun cached(key: String): Bitmap? = memory.get(key)

    /**
     * Loads artwork into [view]. [big] requests a high resolution frame (used by
     * the now-playing sheet); list thumbnails stay small on purpose.
     */
    fun load(
        view: ImageView,
        videoId: String,
        thumbnail: String,
        big: Boolean = false,
        onBitmap: ((Bitmap) -> Unit)? = null
    ) {
        val key = cacheKey(videoId, thumbnail, big)
        if (key.isBlank()) return
        view.setTag(TAG_KEY, key)

        memory.get(key)?.let {
            view.setImageBitmap(it)
            onBitmap?.invoke(it)
            return
        }

        val context = view.context.applicationContext
        val targetPx = if (big) 1080 else 320
        request(context, key, videoId, thumbnail, big, targetPx) { bmp ->
            if (view.getTag(TAG_KEY) == key) view.setImageBitmap(bmp)
            onBitmap?.invoke(bmp)
        }
    }

    /** Loads artwork without a view (e.g. media notification / Discord). */
    fun loadBitmap(
        context: android.content.Context,
        videoId: String,
        thumbnail: String,
        big: Boolean = false,
        onBitmap: (Bitmap) -> Unit
    ) {
        val key = cacheKey(videoId, thumbnail, big)
        if (key.isBlank()) return
        memory.get(key)?.let { onBitmap(it); return }
        request(context.applicationContext, key, videoId, thumbnail, big, if (big) 1080 else 320, onBitmap)
    }

    private fun request(
        context: android.content.Context,
        key: String,
        videoId: String,
        thumbnail: String,
        big: Boolean,
        targetPx: Int,
        callback: (Bitmap) -> Unit
    ) {
        val waiters = inFlight.getOrPut(key) { mutableListOf() }
        val first = synchronized(waiters) {
            waiters.add(callback)
            waiters.size == 1
        }
        if (!first) return // another worker is already fetching this key

        pool.execute {
            val bmp = decodeDisk(context, key, targetPx) ?: run {
                var result: Bitmap? = null
                for (candidate in candidates(videoId, thumbnail, big)) {
                    val bytes = download(candidate) ?: continue
                    result = decode(bytes, targetPx) ?: continue
                    writeDisk(context, key, bytes)
                    break
                }
                result
            }
            val listeners = inFlight.remove(key)?.let { synchronized(it) { it.toList() } }.orEmpty()
            if (bmp != null) {
                memory.put(key, bmp)
                main.post { listeners.forEach { it(bmp) } }
            }

        }
    }

    private fun cacheKey(videoId: String, thumbnail: String, big: Boolean): String {
        val base = videoId.ifBlank { thumbnail }
        if (base.isBlank()) return ""
        return md5(base) + if (big) "_hd" else "_sd"
    }

    /**
     * Small views never need maxresdefault: `mqdefault` is ~10x smaller and
     * always exists, so the grid fills almost immediately.
     */
    private fun candidates(videoId: String, thumbnail: String, big: Boolean): List<String> {
        val sized = resize(thumbnail, if (big) 1080 else 400)
        return if (big) {
            listOf(
                if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" else "",
                sized,
                if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
            )
        } else {
            listOf(
                sized,
                if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else "",
                if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
            )
        }.filter { it.isNotBlank() }.distinct()
    }

    /** YouTube Music thumbnails carry their size in the URL; ask for what we show. */
    private fun resize(url: String, px: Int): String {
        if (url.isBlank()) return url
        return Regex("=w\\d+-h\\d+").replace(url, "=w$px-h$px")
            .let { Regex("/w\\d+-h\\d+").replace(it, "/w$px-h$px") }
    }

    private fun download(url: String): ByteArray? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = true
        connection.useCaches = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NaoMD")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            null
        } else {
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            if (bytes.size < 512) null else bytes
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / 2 >= targetPx && sample < 16) {
            sample *= 2
            largest /= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (_: Throwable) {
        null
    }

    private fun diskFile(context: android.content.Context, key: String): File {
        val dir = File(context.cacheDir, "nao_artwork").apply { if (!exists()) mkdirs() }
        return File(dir, "$key.img")
    }

    private fun decodeDisk(context: android.content.Context, key: String, targetPx: Int): Bitmap? {
        val file = diskFile(context, key)
        if (!file.exists() || file.length() < 512L) return null
        return try {
            decode(file.readBytes(), targetPx)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(context: android.content.Context, key: String, bytes: ByteArray) {
        try {
            diskFile(context, key).outputStream().use { it.write(bytes) }
        } catch (_: Exception) {
        }
    }

    private fun md5(value: String): String = try {
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        value.hashCode().toString()
    }
}
