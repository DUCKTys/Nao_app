package com.nao.md.project.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Konfigurasi Downloader.
 *
 * Backend Scrapr sudah dihapus sepenuhnya dari aplikasi ini. "Resolve URL"
 * sekarang langsung memanggil API pihak ketiga secara berurutan sampai salah
 * satu berhasil:
 *
 *  1. SaverAPI (https://saverapi.net) — butuh API key, disimpan lewat
 *     [DownloaderApiConfig.setSaverApiKey]. Tanpa key, provider ini dilewati.
 *  2. Omegatech All-downloader-v2.
 *  3. Nexray AIO downloader (fallback terakhir).
 */
object DownloaderApiConfig {
    private const val PREFS = "nao_prefs"
    private const val KEY_SAVERAPI_KEY = "saverapi_api_key"
    private const val KEY_SAVERAPI_ENDPOINT = "saverapi_endpoint"

    /** Endpoint default sesuai dokumentasi SaverAPI. Bisa diubah lewat pengaturan
     * kalau suatu saat dokumentasinya berubah, tanpa perlu update aplikasi. */
    const val DEFAULT_SAVERAPI_ENDPOINT = "https://saverapi.net/api/download"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun getSaverApiKey(context: android.content.Context): String =
        prefs(context).getString(KEY_SAVERAPI_KEY, "") ?: ""

    fun setSaverApiKey(context: android.content.Context, key: String) {
        prefs(context).edit().putString(KEY_SAVERAPI_KEY, key.trim()).apply()
    }

    fun getSaverApiEndpoint(context: android.content.Context): String =
        prefs(context).getString(KEY_SAVERAPI_ENDPOINT, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SAVERAPI_ENDPOINT

    fun setSaverApiEndpoint(context: android.content.Context, endpoint: String) {
        val clean = endpoint.trim()
        prefs(context).edit().putString(KEY_SAVERAPI_ENDPOINT, clean.ifBlank { null }).apply()
    }

    fun isSaverApiConfigured(context: android.content.Context): Boolean =
        getSaverApiKey(context).isNotBlank()
}

/**
 * Downloader transport: memanggil API downloader pihak ketiga dan
 * menormalkan hasilnya ke bentuk yang dipakai UI (`title`, `platform`,
 * `provider`, `thumbnail`, `downloads[]`).
 *
 * Transport ini terpisah dari Music/InnerTube — mengganti provider di sini
 * tidak memengaruhi runtime Music/InnerTube.
 */
class DownloaderClient(
    private val context: android.content.Context? = null
) {
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getConn(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 45000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NAO-MD-Downloader/4.0")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val data = ByteArrayOutputStream()
        input.use { it.copyTo(data) }
        val text = data.toString("UTF-8")
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optString("error", text.ifBlank { "HTTP $code" }) }
                catch (_: Exception) { text.ifBlank { "HTTP $code" } }
            throw Exception(msg)
        }
        return text
    }

    private fun <T> retry(label: String, attempts: Int = 2, block: () -> T): T {
        var last: Exception? = null
        for (attempt in 1..attempts) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (attempt < attempts) Thread.sleep(350L * attempt)
            }
        }
        throw Exception("$label gagal: ${last?.message ?: "unknown error"}", last)
    }

    // ------------------------------------------------------------ resolve

    /**
     * Kirim URL ke setiap provider secara berurutan (1 → 2 → 3) sampai ada
     * yang berhasil mengembalikan minimal satu media. Melempar exception
     * hanya kalau semua provider gagal.
     */
    fun resolve(url: String): JSONObject {
        val errors = StringBuilder()

        if (context != null && DownloaderApiConfig.isSaverApiConfigured(context)) {
            try {
                val result = resolveViaSaverApi(context, url)
                if ((result.optJSONArray("downloads")?.length() ?: 0) > 0) return result
            } catch (e: Exception) {
                errors.append("SaverAPI: ${e.message}\n")
            }
        }

        try {
            val result = resolveViaOmegatech(url)
            if ((result.optJSONArray("downloads")?.length() ?: 0) > 0) return result
        } catch (e: Exception) {
            errors.append("Omegatech: ${e.message}\n")
        }

        try {
            val result = resolveViaNexray(url)
            if ((result.optJSONArray("downloads")?.length() ?: 0) > 0) return result
        } catch (e: Exception) {
            errors.append("Nexray: ${e.message}\n")
        }

        throw Exception(errors.toString().trim().ifBlank { "Semua provider downloader gagal" })
    }

    /** API 1 — SaverAPI (butuh API key dari pengguna). */
    private fun resolveViaSaverApi(context: android.content.Context, url: String): JSONObject = retry("SaverAPI") {
        val key = DownloaderApiConfig.getSaverApiKey(context)
        val endpoint = DownloaderApiConfig.getSaverApiEndpoint(context)
        val full = "$endpoint?url=${encode(url)}"
        val c = getConn(full, mapOf("x-api-key" to key))
        val body: String
        try { body = readBody(c) } finally { c.disconnect() }
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: json
        normalizeGeneric(data, provider = "SaverAPI")
    }

    /**
     * Cek info akun/kredit SaverAPI — endpoint resmi sesuai dokumentasi
     * (`GET /api/infobalans`, header `x-api-key`). Dipakai di layar
     * pengaturan untuk memverifikasi API key valid dan menampilkan sisa
     * kredit, tanpa memakai kredit untuk resolve media.
     */
    fun checkSaverApiBalance(context: android.content.Context): JSONObject {
        val key = DownloaderApiConfig.getSaverApiKey(context)
        require(key.isNotBlank()) { "API key belum diisi" }
        val c = getConn("https://saverapi.net/api/infobalans", mapOf("x-api-key" to key))
        val body: String
        try { body = readBody(c) } finally { c.disconnect() }
        val json = JSONObject(body)
        if (!json.optBoolean("success", true)) throw Exception(json.optString("message", "API key tidak valid"))
        return json
    }

    /** API 2 — Omegatech All-downloader-v2. */
    private fun resolveViaOmegatech(url: String): JSONObject = retry("Omegatech") {
        val full = "https://omegatech-api.dixonomega.tech/api/download/All-downloader-v2" +
            "?action=download&url=${encode(url)}"
        val c = getConn(full)
        val body: String
        try { body = readBody(c) } finally { c.disconnect() }
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: throw Exception(json.optString("message", "Respons kosong"))
        normalizeOmegatech(data)
    }

    /** API 3 — Nexray AIO downloader. */
    private fun resolveViaNexray(url: String): JSONObject = retry("Nexray") {
        val full = "https://api.nexray.eu.cc/downloader/aio?url=${encode(url)}"
        val c = getConn(full)
        val body: String
        try { body = readBody(c) } finally { c.disconnect() }
        val json = JSONObject(body)
        if (!json.optBoolean("status", true) && json.optJSONObject("result") == null) {
            throw Exception(json.optString("message", "Respons kosong"))
        }
        val data = json.optJSONObject("result") ?: throw Exception("Respons kosong")
        normalizeNexray(data)
    }

    // --------------------------------------------------------- normalisasi

    private fun mediaEntry(url: String, type: String, quality: String, format: String?): JSONObject =
        JSONObject().apply {
            put("id", url)
            put("url", url)
            put("type", type)
            put("quality", quality)
            if (!format.isNullOrBlank()) put("format", format)
        }

    private fun normalizeOmegatech(data: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("title", data.optString("title", "Media"))
        out.put("platform", data.optString("source", "unknown").replaceFirstChar { it.uppercase() })
        out.put("provider", "Omegatech")
        out.put("thumbnail", data.optString("thumbnail", ""))
        val downloads = JSONArray()

        data.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                downloads.put(mediaEntry(v.optString("url"), "video", v.optString("quality", "Video"), v.optString("format")))
            }
        }
        data.optJSONArray("audios")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                downloads.put(mediaEntry(a.optString("url"), "audio", a.optString("quality", "Audio"), a.optString("format")))
            }
        }
        data.optJSONArray("photos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                downloads.put(mediaEntry(p.optString("url"), "image", p.optString("quality", "Foto"), p.optString("format")))
            }
        }
        out.put("downloads", downloads)
        return out
    }

    private fun normalizeNexray(data: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("title", data.optString("title", "Media"))
        out.put("platform", data.optString("source", "unknown").replaceFirstChar { it.uppercase() })
        out.put("provider", "Nexray")
        out.put("thumbnail", data.optString("thumbnail", ""))
        val downloads = JSONArray()
        data.optJSONArray("medias")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val type = when (m.optString("type").lowercase()) {
                    "image", "photo" -> "image"
                    "audio" -> "audio"
                    else -> "video"
                }
                downloads.put(mediaEntry(m.optString("url"), type, m.optString("quality", ""), m.optString("extension")))
            }
        }
        out.put("downloads", downloads)
        return out
    }

    /** Normalisasi generik untuk provider yang belum diketahui bentuk pastinya
     * (dipakai untuk SaverAPI selama bentuk responsnya mengikuti pola umum
     * download-api: title/thumbnail + daftar formats/videos/audios/medias). */
    private fun normalizeGeneric(data: JSONObject, provider: String): JSONObject {
        val out = JSONObject()
        out.put("title", data.optString("title", "Media"))
        out.put("platform", data.optString("source", data.optString("platform", "unknown")).replaceFirstChar { it.uppercase() })
        out.put("provider", provider)
        out.put("thumbnail", data.optString("thumbnail", ""))
        val downloads = JSONArray()
        fun collect(key: String, fallbackType: String) {
            data.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val type = m.optString("type", fallbackType).lowercase()
                    val u = m.optString("url")
                    if (u.isNotBlank()) downloads.put(mediaEntry(u, type, m.optString("quality", ""), m.optString("format", m.optString("extension"))))
                }
            }
        }
        collect("videos", "video")
        collect("audios", "audio")
        collect("photos", "image")
        collect("medias", "video")
        collect("formats", "video")
        // Sebagian API menaruh satu link tunggal langsung di root (mis. "url").
        if (downloads.length() == 0) {
            val direct = data.optString("url")
            if (direct.isNotBlank()) downloads.put(mediaEntry(direct, data.optString("type", "video"), data.optString("quality", ""), null))
        }
        out.put("downloads", downloads)
        return out
    }

    // ------------------------------------------------------------- lain2

    /**
     * [onProgress] dipanggil tiap kali ada data baru masuk (done, total).
     * `total` bisa -1 kalau server tidak mengirim Content-Length — dalam
     * kasus itu pemanggil sebaiknya menampilkan status "..." (indeterminate)
     * alih-alih menebak persentase, supaya tidak terlihat macet di angka tetap.
     */
    fun getUrlBytes(url: String, onProgress: ((Long, Long) -> Unit)? = null): ByteArray {
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL media tidak valid" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NAO-MD-Downloader/4.0")
        }
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val total = c.contentLengthLong
        val data = ByteArrayOutputStream()
        var done = 0L
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                data.write(buffer, 0, read)
                done += read
                onProgress?.invoke(done, total)
            }
        }
        c.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")
        return data.toByteArray()
    }

    fun directMediaFallback(url: String): JSONObject? {
        val lower = url.lowercase()
        val known = listOf(".mp4", ".webm", ".mov", ".mkv", ".mp3", ".m4a", ".aac", ".jpg", ".jpeg", ".png", ".webp")
        if (!known.any { lower.contains(it) }) return null
        val type = when {
            listOf(".mp3", ".m4a", ".aac").any { lower.contains(it) } -> "audio"
            listOf(".jpg", ".jpeg", ".png", ".webp").any { lower.contains(it) } -> "image"
            else -> "video"
        }
        return JSONObject().apply {
            put("title", "Direct media")
            put("platform", "Direct URL")
            put("provider", "Direct")
            put("downloads", JSONArray().put(JSONObject().apply {
                put("type", type)
                put("url", url)
                put("quality", "Direct")
            }))
        }
    }
}
