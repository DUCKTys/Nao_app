package com.nao.md.project.video

import android.content.Context
import com.nao.md.project.music.YouTubeStreamResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Client API Invidious (https://github.com/iv-org/invidious) untuk halaman Video.
 *
 * Menangani:
 *  - penemuan instance (daftar statis + refresh dari api.invidious.io, disimpan
 *    di SharedPreferences agar instan saat aplikasi dibuka lagi),
 *  - rotasi instance (coba instance terakhir yang berhasil dulu, lalu hasil
 *    refresh, lalu daftar statis) sampai salah satu menanggapi,
 *  - metadata video (cari, trending, detail + video terkait),
 *  - resolusi stream untuk diputar lewat /api/v1/videos/{id}?local=true (URL
 *    diproksikan instance, bukan URL googlevideo yang terikat IP server).
 *    Bila instance gagal / tanpa formatStreams, dipakai YouTubeStreamResolver
 *    (basis NewPipeExtractor, sudah dipakai Nao Music) sebagai fallback.
 *  - pencarian & trending juga punya fallback NewPipe bila API instance
 *    dimatikan atau diblokir CAPTCHA, supaya UI tidak menggantung.
 *
 * UI-nya tetap native (lihat NaoVideoPages), tidak ada WebView.
 */
class InvidiousApi(private val context: Context) {

    companion object {
        /**
         * User-Agent untuk panggilan API & stream lewat instance. SENGAJA tidak
         * diawali "Mozilla/": instance publik seperti inv.nadeko.net memasang
         * CAPTCHA/anti-bot (Go-away) yang menantang semua klien ber-UA browser
         * dengan halaman HTML (401/403). Klien API non-browser tidak ditantang.
         */
        const val API_USER_AGENT = "NaoMD/2.0 (Android; Invidious API client)"

        /** Total waktu maksimum merotasi instance sebelum jatuh ke cadangan. */
        private const val REQUEST_BUDGET_MS = 14_000L
    }

    /** Ringkasan kegagalan permintaan Invidious terakhir (untuk ditampilkan/diagnosis). */
    @Volatile
    var lastError: String = ""
        private set

    // Instance yang paling mungkin hidup saat ini, dipakai sebagai cadangan
    // ketika refresh dari api.invidious.io gagal atau tidak mengembalikan
    // instance dengan API aktif. Urutan menentukan prioritas pencobaan.
    private val defaultInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.f5.si",
        "https://invidious.site",
        "https://invidious.tiekoetter.com",
        "https://invidious.nerdvpn.de",
        "https://yt.chocolatemoo53.com"
    )

    data class VideoItem(
        val videoId: String,
        val title: String,
        val author: String = "",
        val lengthSeconds: Long = 0L,
        val viewCount: Long = 0L,
        val publishedText: String = "",
        val thumbnail: String = ""
    )

    data class Stream(
        val url: String,
        val mimeType: String = "video/mp4",
        val resolution: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val itag: Int = 0,
        /** "invidious" (lewat instance) atau "youtube" (NewPipe langsung). */
        val source: String = "invidious"
    )

    data class VideoDetail(
        val video: VideoItem,
        val description: String = "",
        val subCountText: String = "",
        val streams: List<Stream> = emptyList(),
        val related: List<VideoItem> = emptyList()
    )

    private val prefs =
        context.applicationContext.getSharedPreferences("nao_video_prefs", Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val cacheTtlMs = 3 * 60_000L

    // ---------- Konfigurasi instance ----------

    /** Instance kustom yang dipilih pengguna; kosong artinya "otomatis". */
    fun customInstance(): String = prefs.getString("invidious_custom", "").orEmpty()

    fun setCustomInstance(value: String) {
        val clean = normalize(value)
        prefs.edit().putString("invidious_custom", if (clean.isBlank()) null else clean).apply()
    }

    fun currentInstance(): String {
        customInstance().takeIf { it.isNotBlank() }?.let { return it }
        return prefs.getString("invidious_last_ok", "").orEmpty()
            .ifBlank { defaultInstances.firstOrNull().orEmpty() }
    }

    /** Refresh daftar instance dari api.invidious.io (di luar thread utama). */
    fun refreshInstances(): List<String> = try {
        val raw = httpGet("https://api.invidious.io/instances.json", 10_000,
            listOf("application/json", "text/json", "text/plain"))
        val arr = JSONArray(raw)
        val https = ArrayList<String>()
        val api = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val pair = runCatching { arr.getJSONArray(i) }.getOrNull() ?: continue
            val meta = runCatching { pair.getJSONObject(1) }.getOrNull() ?: continue
            if (meta.optString("type") != "https") continue
            val uri = meta.optString("uri").trim()
            if (!uri.startsWith("https://")) continue
            https.add(uri)
            if (meta.optBoolean("api")) api.add(uri)
        }
        val merged = (if (api.isNotEmpty()) api else https)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .plus(defaultInstances)
            .distinct()
        prefs.edit().putString("invidious_instances", JSONArray(merged).toString()).apply()
        merged
    } catch (e: Exception) {
        savedInstances()
    }

    fun savedInstances(): List<String> = try {
        val raw = prefs.getString("invidious_instances", "").orEmpty()
        if (raw.isBlank()) defaultInstances
        else {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                .ifEmpty { defaultInstances }
        }
    } catch (_: Exception) {
        defaultInstances
    }

    /** Urutan instance yang akan dicoba untuk satu request. */
    private fun candidates(): List<String> {
        val out = ArrayList<String>()
        customInstance().takeIf { it.isNotBlank() }?.let { out.add(it) }
        prefs.getString("invidious_last_ok", "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { out.add(it) }
        out += savedInstances()
        return out.filter { it.isNotBlank() }.distinct()
    }

    private fun normalize(value: String): String {
        var v = value.trim().trimEnd('/')
        if (v.isBlank()) return ""
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        return v.trimEnd('/')
    }

    // ---------- Transport ----------

    private class Reply(val base: String, val body: String)

    private fun host(base: String): String = base.removePrefix("https://").removePrefix("http://")

    /** GET tunggal; melempar IOException untuk non-2xx atau body kosong. */
    private fun httpGet(target: String, timeoutMs: Int, accept: List<String>): String {
        val connection = URL(target).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = minOf(5_000, timeoutMs)
            connection.readTimeout = minOf(9_000, timeoutMs)
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", API_USER_AGENT)
            connection.setRequestProperty("Accept", accept.joinToString(", "))
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = if (code == 401 || code == 403 || code == 429) " (kemungkinan diblokir anti-bot / rate limit)" else ""
                throw IOException("HTTP $code$hint")
            }
            if (body.isBlank()) throw IOException("respons kosong")
            return body
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Meminta `/api/v1$path` ke instance secara bergiliran sampai ada yang
     * memberi JSON valid (diawali [expect]: '{' atau '['). Respons HTML
     * (halaman CAPTCHA/error yang berstatus 200) dianggap gagal dan dilewati,
     * bukan dikembalikan ke parser JSON. Dibatasi oleh [budgetMs] total agar UI
     * tidak menggantung saat banyak instance mati.
     */
    private fun request(path: String, expect: Char, budgetMs: Long = REQUEST_BUDGET_MS): Reply {
        val started = System.currentTimeMillis()
        val failures = ArrayList<String>()
        for (base in candidates().take(6)) {
            val remaining = budgetMs - (System.currentTimeMillis() - started)
            if (failures.isNotEmpty() && remaining < 2_000L) break
            try {
                val body = httpGet(
                    "$base/api/v1$path",
                    remaining.coerceAtLeast(2_500L).toInt(),
                    listOf("application/json")
                )
                if (body.trimStart().firstOrNull() != expect) {
                    failures += "${host(base)}: respons bukan JSON (API dimatikan / halaman CAPTCHA)"
                    continue
                }
                prefs.edit().putString("invidious_last_ok", base).apply()
                lastError = ""
                return Reply(base, body)
            } catch (e: Exception) {
                failures += "${host(base)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        lastError = failures.joinToString("; ")
        throw IOException(lastError.ifBlank { "Tidak ada instance Invidious" })
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun jsonObject(path: String): JSONObject {
        cache[path]?.let { (at, value) ->
            if (System.currentTimeMillis() - at < cacheTtlMs && value is JSONObject) return value
        }
        val reply = request(path, '{')
        val parsed = JSONObject(reply.body)
        // Simpan instance yang menjawab supaya URL relatif bisa dijadikan absolut.
        parsed.put("_nao_base", reply.base)
        cache[path] = System.currentTimeMillis() to parsed
        return parsed
    }

    private fun jsonArray(path: String): JSONArray {
        cache[path]?.let { (at, value) ->
            if (System.currentTimeMillis() - at < cacheTtlMs && value is JSONArray) return value
        }
        val parsed = JSONArray(request(path, '[').body)
        cache[path] = System.currentTimeMillis() to parsed
        return parsed
    }

    // ---------- Metadata ----------

    /**
     * Cari video. Invidious dicoba dulu; bila semua instance gagal / API-nya
     * dimatikan / diblokir CAPTCHA, dipakai pencarian NewPipe langsung ke
     * YouTube sehingga hasil tetap muncul (tidak menggantung di "Mencari...").
     */
    fun search(query: String): List<VideoItem> {
        require(query.isNotBlank()) { "Query pencarian kosong" }
        val q = query.trim()
        val viaInvidious = runCatching {
            parseItems(jsonArray("/search?q=${encode(q)}&type=video"), currentInstance())
        }.getOrDefault(emptyList())
        if (viaInvidious.isNotEmpty()) return viaInvidious
        return searchViaYoutube(q)
    }

    /** Pesan gabungan bila Invidious DAN cadangan NewPipe sama-sama gagal. */
    private fun <T> withDiagnostics(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        val invidious = lastError.ifBlank { "tidak ada hasil" }
        throw IOException("${e.message ?: e.javaClass.simpleName} | Invidious: $invidious", e)
    }

    /** Kategori trending didukung Invidious: video, music, gaming, movies, news. */
    fun trending(type: String = "video"): List<VideoItem> {
        val kind = type.ifBlank { "video" }
        val viaInvidious = runCatching {
            parseItems(jsonArray("/trending?type=${encode(kind)}"), currentInstance())
        }.getOrDefault(emptyList())
        if (viaInvidious.isNotEmpty()) return viaInvidious
        // Trending YouTube sudah tidak punya endpoint publik; pakai pencarian
        // per kategori sebagai pengganti terdekat.
        val fallbackQuery = when (kind) {
            "music" -> "top music videos"
            "gaming" -> "gaming highlights"
            "movies" -> "official movie trailers"
            "news" -> "news today"
            else -> "popular videos this week"
        }
        return searchViaYoutube(fallbackQuery)
    }

    private fun searchViaYoutube(query: String): List<VideoItem> = withDiagnostics {
        YouTubeStreamResolver.searchVideos(query).map {
            VideoItem(
                videoId = it.videoId,
                title = it.title,
                author = it.author,
                lengthSeconds = it.lengthSeconds,
                viewCount = it.viewCount,
                publishedText = it.publishedText,
                thumbnail = it.thumbnail.ifBlank { "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg" }
            )
        }
    }

    /**
     * Detail video. `local=true` WAJIB: tanpa itu URL stream adalah URL
     * googlevideo yang terikat ke IP server instance (bukan IP ponsel) dan
     * ditolak 403 saat diputar. Dengan `local=true` URL diarahkan lewat
     * /videoplayback milik instance (sama seperti pemutar web Invidious).
     */
    fun video(videoId: String): VideoDetail {
        require(videoId.isNotBlank()) { "videoId kosong" }
        val obj = jsonObject("/videos/$videoId?local=true")
        val base = obj.optString("_nao_base").ifBlank { currentInstance() }
        val item = VideoItem(
            videoId = videoId,
            title = obj.optString("title"),
            author = obj.optString("author"),
            lengthSeconds = obj.optLong("lengthSeconds"),
            viewCount = obj.optLong("viewCount"),
            publishedText = obj.optString("publishedText"),
            thumbnail = pickThumb(obj.optJSONArray("videoThumbnails"), videoId, base)
        )
        val streams = parseStreams(obj, base)
        val related = parseItems(obj.optJSONArray("recommendedVideos"), base)
            .ifEmpty { parseItems(obj.optJSONArray("relatedVideos"), base) }
        return VideoDetail(
            video = item,
            description = obj.optString("description"),
            subCountText = obj.optString("subCountText"),
            streams = streams,
            related = related
        )
    }

    /** Fallback resolusi stream lewat YouTubeStreamResolver (basis NewPipe). */
    fun resolveViaYoutube(videoId: String): List<Stream> {
        require(videoId.isNotBlank()) { "videoId kosong" }
        val resolved = YouTubeStreamResolver.resolveVideo(videoId)
        return resolved.map {
            Stream(
                url = it.url,
                mimeType = it.mimeType.ifBlank { "video/mp4" },
                resolution = if (it.width > 0 && it.height > 0) "${it.width}x${it.height}" else "",
                width = it.width,
                height = it.height,
                source = "youtube"
            )
        }.distinctBy { it.url }
    }

    /**
     * Stream gabungan (audio+video) yang siap putar, berurutan sebagai kandidat:
     *  1. formatStreams dari /api/v1/videos/{id}?local=true (diproksikan instance),
     *  2. bila instance hidup tapi formatStreams kosong: /latest_version?...&local=true,
     *  3. bila instance sama sekali tak bisa dipakai: NewPipe langsung ke YouTube.
     * Pemutar akan mencoba kandidat berikutnya (lalu NewPipe) bila ada yang gagal.
     */
    fun streams(videoId: String): List<Stream> {
        require(videoId.isNotBlank()) { "videoId kosong" }
        val out = ArrayList<Stream>()
        val detail = runCatching { video(videoId) }.getOrNull()
        if (detail != null) {
            out += detail.streams.filter { it.url.isNotBlank() }
            out += latestVersionStreams(videoId, currentInstance())
        }
        if (out.isEmpty()) out += resolveViaYoutube(videoId)
        return out.distinctBy { it.url }
    }

    /** itag 22 = mp4 720p, itag 18 = mp4 360p; keduanya gabungan audio+video. */
    private fun latestVersionStreams(videoId: String, base: String): List<Stream> {
        if (base.isBlank()) return emptyList()
        return listOf(22 to "1280x720", 18 to "640x360").map { (itag, size) ->
            Stream(
                url = "$base/latest_version?id=$videoId&itag=$itag&local=true",
                mimeType = "video/mp4",
                resolution = size,
                width = size.substringBefore("x").toInt(),
                height = size.substringAfter("x").toInt(),
                itag = itag
            )
        }
    }

    private fun parseItems(array: JSONArray?, base: String): List<VideoItem> {
        if (array == null) return emptyList()
        val out = ArrayList<VideoItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (!obj.optString("type").equals("video", true)) continue
            val id = obj.optString("videoId")
            if (id.isBlank() || obj.optString("title").isBlank()) continue
            out.add(
                VideoItem(
                    videoId = id,
                    title = obj.optString("title"),
                    author = obj.optString("author"),
                    lengthSeconds = obj.optLong("lengthSeconds"),
                    viewCount = obj.optLong("viewCount"),
                    publishedText = obj.optString("publishedText"),
                    thumbnail = pickThumb(obj.optJSONArray("videoThumbnails"), id, base)
                )
            )
        }
        return out.distinctBy { it.videoId }.take(40)
    }

    private fun absolute(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") && base.isNotBlank() -> base.trimEnd('/') + url
        else -> url
    }

    private fun pickThumb(videoThumbnails: JSONArray?, videoId: String, base: String): String {
        var best = ""
        if (videoThumbnails != null) {
            for (i in 0 until videoThumbnails.length()) {
                val t = videoThumbnails.optJSONObject(i) ?: continue
                val quality = t.optString("quality")
                val url = t.optString("url").trim()
                if (url.isBlank()) continue
                if (best.isBlank() ||
                    quality == "maxres" || quality == "maxresdefault" || quality == "sddefault"
                ) best = absolute(url, base)
            }
        }
        return best.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    }

    /**
     * Hanya formatStreams (gabungan audio+video, bisa langsung diputar ExoPlayer).
     * adaptiveFormats sengaja TIDAK dipakai: isinya video-only atau audio-only,
     * jadi memutarnya sendirian menghasilkan gambar tanpa suara / suara tanpa gambar.
     */
    private fun parseStreams(obj: JSONObject, base: String): List<Stream> {
        val out = LinkedHashSet<Stream>()
        parseFormatList(obj.optJSONArray("formatStreams"), base, out)
        return out.toList().sortedWith(
            compareByDescending<Stream> { it.height }.thenBy { it.width }
        )
    }

    private fun parseFormatList(array: JSONArray?, base: String, out: MutableSet<Stream>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val rawUrl = item.optString("url").trim()
            val type = item.optString("type")
            if (rawUrl.isBlank()) continue
            if (!type.contains("video")) continue
            val size = item.optString("size") // bentuk "1920x1080"
            val width = runCatching { size.substringBefore("x").trim().toInt() }.getOrDefault(0)
            val height = runCatching { size.substringAfter("x").trim().toInt() }.getOrDefault(0)
            out.add(
                Stream(
                    url = absolute(rawUrl, base),
                    mimeType = type,
                    resolution = item.optString("resolution"),
                    width = width,
                    height = height,
                    itag = item.optInt("itag")
                )
            )
        }
    }
}
