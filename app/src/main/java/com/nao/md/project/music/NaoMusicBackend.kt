package com.nao.md.project.music

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Backend musik Nao MD berbasis layanan ytmusicapi (lihat folder
 * `ytmusic-service/` di repo web project).
 *
 * Menggantikan PipedClient sepenuhnya:
 * - metadata (pencarian, radio, beranda, playlist akun) diambil dari
 *   ytmusicapi di server,
 * - URL audio/video diambil dari endpoint /stream yang memakai cookie akun,
 *   sehingga stream-nya bebas iklan (sama seperti pengalaman Premium).
 *
 * Alamat layanan dan token bisa diatur lewat SharedPreferences
 * "nao_music_backend" (key: base_url, token) atau dengan mengubah
 * DEFAULT_BASE_URL / DEFAULT_TOKEN di bawah.
 */
object NaoMusicBackend {
    // TODO: ganti dengan alamat layanan ytmusicapi milik Anda.
    private const val DEFAULT_BASE_URL = "https://ytmusic.example.com"
    private const val DEFAULT_TOKEN = ""
    private const val PREFS = "nao_music_backend"

    const val STREAM_USER_AGENT = InnerTubeClient.STREAM_USER_AGENT

    data class Stream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val width: Int = 0,
        val height: Int = 0
    )

    @Volatile private var baseUrl: String = DEFAULT_BASE_URL
    @Volatile private var token: String = DEFAULT_TOKEN

    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamCache = ConcurrentHashMap<String, Pair<Long, List<Stream>>>()
    private val streamTtlMs = 15 * 60_000L

    /** Baca konfigurasi tersimpan. Panggil sekali di MainActivity.onCreate(). */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl = prefs.getString("base_url", DEFAULT_BASE_URL)?.trim()?.trimEnd('/')
            .orEmpty().ifBlank { DEFAULT_BASE_URL }
        token = prefs.getString("token", DEFAULT_TOKEN).orEmpty()
    }

    fun configure(context: Context, url: String, apiToken: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", url.trim().trimEnd('/'))
            .putString("token", apiToken.trim())
            .apply()
        init(context)
    }

    fun isConfigured(): Boolean =
        baseUrl.startsWith("http") && !baseUrl.contains("example.com")

    private fun get(path: String): JSONObject = call(path, post = false)

    private fun call(path: String, post: Boolean): JSONObject {
        if (!isConfigured()) throw IOException("Layanan ytmusicapi belum dikonfigurasi")
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
        if (token.isNotBlank()) builder.header("X-Nao-Token", token)
        if (post) builder.post("".toRequestBody(null)) else builder.get()

        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("ytmusicapi HTTP ${response.code}")
            if (raw.isBlank()) error("ytmusicapi mengembalikan respons kosong")
            return JSONObject(raw)
        }
    }

    private fun track(item: JSONObject): InnerTubeClient.Track = InnerTubeClient.Track(
        videoId = item.optString("videoId"),
        title = item.optString("title"),
        artist = item.optString("artist"),
        album = item.optString("album"),
        duration = item.optString("duration"),
        thumbnail = item.optString("thumbnail")
    )

    private fun trackList(array: JSONArray?): List<InnerTubeClient.Track> {
        val out = ArrayList<InnerTubeClient.Track>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val t = track(item)
            if (t.videoId.isNotBlank() && t.title.isNotBlank()) out.add(t)
        }
        return out.distinctBy { it.videoId }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    // ---------- Metadata (ytmusicapi) ----------

    fun search(query: String, filter: String = ""): List<InnerTubeClient.Track> {
        val extra = if (filter.isBlank()) "" else "&filter=${encode(filter)}"
        return trackList(get("/search?q=${encode(query)}$extra").optJSONArray("items"))
    }

    fun suggestions(query: String): List<String> {
        val array = get("/suggestions?q=${encode(query)}").optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun radio(videoId: String): List<InnerTubeClient.Track> =
        trackList(get("/radio/$videoId").optJSONArray("items"))

    fun related(videoId: String): List<InnerTubeClient.Track> =
        trackList(get("/related/$videoId").optJSONArray("items"))

    data class Shelf(val title: String, val items: List<InnerTubeClient.Track>)

    private fun shelves(path: String): List<Shelf> {
        val array = get(path).optJSONArray("shelves") ?: return emptyList()
        val out = ArrayList<Shelf>()
        for (i in 0 until array.length()) {
            val shelf = array.optJSONObject(i) ?: continue
            val items = trackList(shelf.optJSONArray("items"))
            if (items.isNotEmpty()) out.add(Shelf(shelf.optString("title"), items))
        }
        return out
    }

    fun home(): List<Shelf> = shelves("/home")

    fun charts(country: String = "ID"): List<Shelf> = shelves("/charts?country=${encode(country)}")

    fun playlist(playlistId: String): List<InnerTubeClient.Track> =
        trackList(get("/playlist/$playlistId").optJSONArray("items"))

    data class LibraryPlaylist(val title: String, val playlistId: String, val thumbnail: String)

    fun libraryPlaylists(): List<LibraryPlaylist> {
        val array = get("/library/playlists").optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("playlistId")
            if (id.isBlank()) null
            else LibraryPlaylist(obj.optString("title"), id, obj.optString("thumbnail"))
        }
    }

    fun likedSongs(): List<InnerTubeClient.Track> =
        trackList(get("/library/liked").optJSONArray("items"))

    fun history(): List<InnerTubeClient.Track> =
        trackList(get("/library/history").optJSONArray("items"))

    fun lyrics(videoId: String): String = get("/lyrics/$videoId").optString("lyrics")

    // ---------- Stream (tanpa iklan) ----------

    /** URL audio langsung, urut dari bitrate tertinggi. */
    fun resolveAudio(videoId: String): List<Stream> {
        require(videoId.isNotBlank()) { "videoId kosong" }
        streamCache[videoId]?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < streamTtlMs && cached.isNotEmpty()) return cached
        }
        val array = get("/stream/$videoId").optJSONArray("audio")
            ?: error("Layanan tidak mengembalikan audio")
        val out = ArrayList<Stream>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            out.add(
                Stream(
                    url = url,
                    mimeType = item.optString("mimeType", "audio/mp4"),
                    bitrate = item.optInt("bitrate")
                )
            )
        }
        if (out.isEmpty()) error("Stream audio tidak tersedia untuk lagu ini")
        streamCache[videoId] = System.currentTimeMillis() to out
        return out
    }

    /** Kompatibel dengan pemakaian lama PipedClient.resolve(). */
    fun resolve(videoId: String): List<Stream> = resolveAudio(videoId)

    fun resolveVideo(videoId: String): List<Stream> {
        val array = get("/stream/$videoId/video").optJSONArray("video")
            ?: error("Layanan tidak mengembalikan video")
        val out = ArrayList<Stream>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            out.add(
                Stream(
                    url = url,
                    mimeType = item.optString("mimeType", "video/mp4"),
                    bitrate = 0,
                    width = item.optInt("width"),
                    height = item.optInt("height")
                )
            )
        }
        if (out.isEmpty()) error("Stream video tidak tersedia")
        return out
    }

    /** Panaskan cache stream lagu berikutnya supaya tombol Next terasa instan. */
    fun prefetch(videoId: String) {
        if (videoId.isBlank() || !isConfigured()) return
        runCatching { resolveAudio(videoId) }
        runCatching { call("/prefetch/$videoId", post = true) }
    }

    /** Bayar handshake TCP/TLS lebih awal. */
    fun warmUp() {
        if (!isConfigured()) return
        Thread { runCatching { get("/health") } }.start()
    }
}
