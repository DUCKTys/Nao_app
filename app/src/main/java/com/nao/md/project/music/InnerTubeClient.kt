package com.nao.md.project.music

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Native YouTube Music InnerTube client.
 *
 * This is deliberately a small Android implementation of the public behaviour
 * exposed by the Python innertube package: it talks to InnerTube directly and
 * never embeds YouTube/YouTube Music in a WebView.
 */
class InnerTubeClient {
    data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String = "",
        val duration: String = "",
        val thumbnail: String = ""
    )
    data class Stream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val width: Int,
        val height: Int
    )

    // Music must fail fast enough to let the next provider take over.
    // The previous 20/30/40s chain could make one search feel frozen.
    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val endpoint = "https://music.youtube.com/youtubei/v1/"
    @Volatile private var bootstrapped = false
    @Volatile private var apiKey = ""
    @Volatile private var clientVersion = "1.20260707.12.00"

    private fun bootstrap() {
        if (bootstrapped) return
        synchronized(this) {
            if (bootstrapped) return
            try {
                val req = Request.Builder()
                    .url("https://music.youtube.com/")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                    .build()
                http.newCall(req).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    Regex("""INNERTUBE_API_KEY["']?\s*[:=]\s*["']([^"']+)["']""")
                        .find(html)?.groupValues?.get(1)?.let { apiKey = it }
                    Regex("""INNERTUBE_CONTEXT_CLIENT_VERSION["']?\s*[:=]\s*["']([^"']+)["']""")
                        .find(html)?.groupValues?.get(1)?.let { clientVersion = it }
                }
            } catch (_: Exception) {
                // Search will still try the client fallback. Do not keep the
                // app in a permanent "bootstrapped but unusable" state.
            }
            bootstrapped = true
        }
    }

    private data class ClientSpec(
        val name: String,
        val version: String,
        val sdk: Int? = null,
        val platform: String? = null,
        // Real client User-Agent to present when actually fetching the
        // resolved googlevideo URL (see STREAM_USER_AGENT below). Only set
        // where it matters; other specs fall back to the historic default.
        val userAgent: String? = null
    )

    private val webMusic = ClientSpec("WEB_REMIX", clientVersion, platform = "DESKTOP")
    private val webYouTube = ClientSpec("WEB", "2.20260820.08.00", platform = "DESKTOP")
    private val androidCurrent = ClientSpec("ANDROID", "20.10.38", sdk = 35, platform = "MOBILE")
    private val iosCurrent = ClientSpec("IOS", "20.10.4", platform = "MOBILE")
    private val androidVr = ClientSpec("ANDROID_VR", "1.60.19", sdk = 32, platform = "MOBILE")
    private val androidMusic = ClientSpec("ANDROID_MUSIC", "7.27.52", sdk = 35, platform = "MOBILE")
    private val android = ClientSpec("ANDROID", "21.03.37", sdk = 35, platform = "MOBILE")

    // Fixes "ERROR_CODE_IO_BAD_HTTP_STATUS" (HTTP 403) on audio-only
    // playback: every other ANDROID*/ANDROID_MUSIC/ANDROID_VR spec above
    // sends "androidSdkVersion" in its InnerTube context, and YouTube now
    // treats that field as a signal that the client must also present a
    // PO (proof-of-origin) token. Since this app never generates one, those
    // clients still return a plain "url" (looking playable, even passing
    // the isUrlReachable() Range probe once) but the real googlevideo CDN
    // request for the full stream is rejected. Dropping androidSdkVersion
    // — same client name/version, otherwise identical to yt-dlp's known
    // working "android_sdkless" client — avoids that requirement and keeps
    // returning direct, unciphered URLs that actually play.
    private val androidSdkless = ClientSpec(
        "ANDROID", "20.10.38", sdk = null, platform = "MOBILE",
        userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
    )

    // Short-lived search cache: repeated taps/returning to the same query
    // should not trigger a new network round-trip.
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<Track>>>()
    private val searchCacheTtlMs = 90_000L

    private fun context(spec: ClientSpec): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", spec.name)
            put("clientVersion", if (spec.name == "WEB_REMIX") clientVersion else spec.version)
            put("hl", "en")
            put("gl", "US")
            spec.sdk?.let { put("androidSdkVersion", it) }
            spec.platform?.let { put("platform", it) }
        })
    }

    private fun post(method: String, body: JSONObject, spec: ClientSpec): JSONObject {
        bootstrap()
        val payload = JSONObject(body.toString()).apply { put("context", context(spec)) }
        val request = Request.Builder()
            .url(endpoint + method + if (apiKey.isNotBlank()) "?key=$apiKey&prettyPrint=false" else "?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", spec.userAgent ?: "com.google.android.youtube/19.29.37 (Linux; Android 15)")
            .header("Accept", "application/json")
            .header("X-YouTube-Client-Name", if (spec.name == "WEB_REMIX") "67" else "3")
            .header("X-YouTube-Client-Version", if (spec.name == "WEB_REMIX") clientVersion else spec.version)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("InnerTube HTTP ${response.code}")
            if (raw.isBlank()) error("InnerTube returned an empty response")
            val json = JSONObject(raw)
            val status = json.optJSONObject("playabilityStatus")
            val reason = status?.optString("reason").orEmpty()
            if (status != null && status.optString("status").equals("ERROR", true) && reason.isNotBlank()) {
                error(reason)
            }
            return json
        }
    }

    fun search(query: String): List<Track> {
        require(query.isNotBlank()) { "Search query is empty" }
        val q = query.trim()
        val cacheKey = q.lowercase()
        val cached = searchCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < searchCacheTtlMs) {
            return cached.second
        }
        bootstrap()
        var lastError: Throwable? = null

        // Sumber utama: layanan ytmusicapi (hasil sama dengan YouTube Music
        // milik akun pengguna). InnerTube/NewPipe hanya cadangan offline.
        runCatching {
            val fromService = NaoMusicBackend.search(q)
            if (fromService.isNotEmpty()) {
                searchCache[cacheKey] = System.currentTimeMillis() to fromService
                return fromService
            }
        }.onFailure { lastError = it }

        runCatching {
            val data = post("search", JSONObject().put("query", q), webMusic)
            val out = ArrayList<Track>()
            walk(data, out)
            val clean = out.distinctBy { it.videoId }
                .filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
                .take(50)
            if (clean.isNotEmpty()) {
                searchCache[cacheKey] = System.currentTimeMillis() to clean
                return clean
            }
        }.onFailure { lastError = it }

        runCatching {
            val data = post(
                "search",
                JSONObject().put("query", q).put("params", ""),
                webYouTube
            )
            val out = ArrayList<Track>()
            walk(data, out)
            val clean = out.distinctBy { it.videoId }
                .filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
                .take(50)
            if (clean.isNotEmpty()) {
                searchCache[cacheKey] = System.currentTimeMillis() to clean
                return clean
            }
        }.onFailure { lastError = it }

        // One NewPipe fallback only. Calling two NewPipe query modes in
        // sequence made a failed search take tens of seconds.
        runCatching {
            val factory = YoutubeSearchQueryHandlerFactory.getInstance()
            val queryHandler = factory.fromQuery(
                q,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                null
            )
            val info = SearchInfo.getInfo(ServiceList.YouTube, queryHandler)
            val clean = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { item ->
                    val id = extractVideoId(item.url)
                    if (id.isBlank() || item.name.isBlank()) return@mapNotNull null
                    Track(
                        videoId = id,
                        title = item.name,
                        artist = item.uploaderName.orEmpty(),
                        duration = item.duration.takeIf { it >= 0 }?.let { formatDuration(it) }.orEmpty(),
                        thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty()
                    )
                }
                .distinctBy { it.videoId }
                .take(50)
            if (clean.isNotEmpty()) {
                searchCache[cacheKey] = System.currentTimeMillis() to clean
                return clean
            }
        }.onFailure { lastError = it }

        throw IllegalStateException(
            lastError?.message ?: "Tidak ada hasil dari InnerTube/NewPipe"
        )
    }

    private fun extractVideoId(url: String): String {
        Regex("""[?&]v=([^&]+)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""youtu\.be/([^?&#/]+)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return url.substringAfter("/watch/").substringBefore("?").substringBefore("&")
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val sec = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }

    // Used only to fan the /player attempts below out in parallel so total
    // wait time is bounded by the slowest useful client, not the sum of all
    // of them. Small and reused across calls, not per-request.
    private val playerExec = Executors.newFixedThreadPool(5)

    /**
     * Try clients that normally expose playable audio URLs. We only return
     * direct URLs here; a signatureCipher without its decipher function is not
     * falsely advertised as playable.
     *
     * YouTube has been tightening things so that ANDROID/ANDROID_MUSIC often
     * come back with only a ciphered URL (dropped by addDirectFormats), which
     * used to mean this whole call failed every time it happened, sending
     * every single track through the much slower NewPipe fallback and
     * regularly surfacing "stream tidak tersedia" when that fallback also
     * struggled. ANDROID_VR and IOS are included too since they frequently
     * keep exposing direct URLs after the others stop, and all specs are
     * queried at once (instead of one-by-one) so a slow/hanging client can't
     * hold up one that would have answered quickly.
     */
    fun player(videoId: String): List<Stream> {
        require(videoId.isNotBlank()) { "videoId is empty" }
        // androidSdkless first: it is the one spec here that does not
        // trigger YouTube's PO-token requirement (see its definition
        // above), so it is the most likely to still be actually playable
        // rather than just "present" in the /player response. The rest are
        // kept as a fallback chain in case YouTube changes behaviour again.
        val specs = listOf(androidSdkless, iosCurrent, androidMusic, android, androidVr, webMusic)
        val completion = java.util.concurrent.ExecutorCompletionService<Result<List<Stream>>>(playerExec)
        val futures = specs.map { spec ->
            completion.submit {
                runCatching {
                    val data = post(
                        "player",
                        JSONObject()
                            .put("videoId", videoId)
                            .put("contentCheckOk", true)
                            .put("racyCheckOk", true),
                        spec
                    )
                    val streaming = data.optJSONObject("streamingData")
                        ?: error("No streamingData for ${spec.name}")
                    val formats = ArrayList<Stream>()
                    addDirectFormats(formats, streaming.optJSONArray("adaptiveFormats"))
                    addDirectFormats(formats, streaming.optJSONArray("formats"))
                    formats.filter { it.mimeType.startsWith("audio/") && it.url.isNotBlank() }
                        .sortedByDescending { it.bitrate }
                        .ifEmpty { error("${spec.name} returned no direct audio URL") }
                }
            }
        }
        var lastError: Throwable? = null
        try {
            repeat(specs.size) {
                val result = try {
                    completion.take().get()
                } catch (e: Exception) {
                    Result.failure(e)
                }
                result.onSuccess { streams ->
                    // The /player response looking clean (a plain "url", no
                    // signatureCipher) is not proof it will actually stream:
                    // YouTube can still reject the real media request (403)
                    // for some clients even though the metadata call
                    // succeeded. Racing all clients means the fastest one
                    // to answer — not necessarily one whose URL is really
                    // fetchable — used to win, so this probes each
                    // candidate for real before accepting it.
                    val playable = streams.firstOrNull { isUrlReachable(it.url) }
                    if (playable != null) {
                        return listOf(playable) + streams.filterNot { it === playable }
                    }
                    lastError = IllegalStateException("Stream URL(s) returned but none were reachable")
                }
                result.onFailure { lastError = it }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        error(lastError?.message ?: "InnerTube did not return a direct playable audio URL")
    }

    /** Short, cheap check that a candidate stream URL is actually fetchable
     * (not just present in the metadata) before committing to it. */
    private fun isUrlReachable(url: String): Boolean = try {
        val probe = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .header("User-Agent", STREAM_USER_AGENT)
            .get()
            .build()
        http.newCall(probe).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    private fun addDirectFormats(out: MutableList<Stream>, array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            // signatureCipher/cipher needs YouTube's current JS decipher
            // algorithm. Do not feed the undeciphered URL to Media3.
            if (url.isBlank()) continue
            out.add(
                Stream(
                    url = url,
                    mimeType = item.optString("mimeType"),
                    bitrate = item.optInt("bitrate"),
                    width = item.optInt("width"),
                    height = item.optInt("height")
                )
            )
        }
    }

    private fun walk(value: Any?, out: MutableList<Track>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("musicResponsiveListItemRenderer")?.let { parseMusicItem(it, out) }
                value.optJSONObject("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it, out) }
                value.optJSONObject("videoRenderer")?.let { parseVideo(it, out) }
                val keys = value.keys()
                while (keys.hasNext()) walk(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), out)
        }
    }

    private fun text(value: JSONObject?): String {
        if (value == null) return ""
        value.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val runs = value.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private fun thumbnail(root: JSONObject?): String {
        if (root == null) return ""
        val renderer = root.optJSONObject("musicThumbnailRenderer")
            ?: root.optJSONObject("thumbnail")
        val list = renderer?.optJSONArray("thumbnails")
            ?: renderer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return list?.optJSONObject(list.length() - 1)?.optString("url").orEmpty()
    }

    private fun parseMusicItem(renderer: JSONObject, out: MutableList<Track>) {
        val id = renderer.optString("videoId").ifBlank {
            renderer.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        }.ifBlank {
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        val flex = renderer.optJSONArray("flexColumns")
        val title = text(
            flex?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        val subtitle = text(
            flex?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        if (id.isNotBlank() && title.isNotBlank()) {
            val parts = subtitle.split(" • ").map { it.trim() }.filter { it.isNotBlank() }
            out.add(
                Track(
                    videoId = id,
                    title = title,
                    artist = parts.firstOrNull().orEmpty(),
                    album = parts.drop(1).firstOrNull().orEmpty(),
                    thumbnail = thumbnail(renderer.optJSONObject("thumbnail"))
                )
            )
        }
    }

    private fun parseTwoRowItem(renderer: JSONObject, out: MutableList<Track>) {
        val id = renderer.optString("videoId").ifBlank {
            renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        val title = text(renderer.optJSONObject("title"))
        val subtitle = text(renderer.optJSONObject("subtitle"))
        if (id.isNotBlank() && title.isNotBlank()) {
            out.add(Track(id, title, subtitle, thumbnail = thumbnail(renderer.optJSONObject("thumbnail"))))
        }
    }

    private fun parseVideo(renderer: JSONObject, out: MutableList<Track>) {
        val id = renderer.optString("videoId")
        val title = text(renderer.optJSONObject("title"))
        val artist = text(renderer.optJSONObject("ownerText"))
        if (id.isNotBlank() && title.isNotBlank()) {
            out.add(Track(id, title, artist, thumbnail = thumbnail(renderer.optJSONObject("thumbnail"))))
        }
    }

    // ------------------------------------------------------------------
    // ArchiveTune-class YouTube Music browse: home, explore, charts,
    // moods, artist/album/playlist, radio (next), filtered search.
    // ------------------------------------------------------------------

    data class Chip(
        val title: String,
        val browseId: String = "",
        val params: String = ""
    )

    data class MusicItem(
        val title: String,
        val subtitle: String = "",
        val thumbnail: String = "",
        val videoId: String = "",
        val browseId: String = "",
        val playlistId: String = "",
        val params: String = "",
        val kind: String = "song",
        val duration: String = "",
        val artist: String = "",
        val album: String = ""
    ) {
        fun toTrack(): Track = Track(
            videoId = videoId.ifBlank { playlistId },
            title = title,
            artist = artist.ifBlank { subtitle },
            album = album,
            duration = duration,
            thumbnail = thumbnail
        )
    }

    data class Shelf(
        val title: String,
        val items: List<MusicItem>,
        val browseId: String = "",
        val params: String = ""
    )

    data class HomeFeed(
        val chips: List<Chip> = emptyList(),
        val shelves: List<Shelf> = emptyList()
    )

    data class CollectionPage(
        val title: String,
        val subtitle: String = "",
        val thumbnail: String = "",
        val description: String = "",
        val kind: String = "playlist",
        val browseId: String = "",
        val playlistId: String = "",
        val tracks: List<Track> = emptyList(),
        val shelves: List<Shelf> = emptyList()
    )

    companion object {
        // Shared with NaoMusicPlaybackService's ExoPlayer HTTP data source.
        // The actual media (googlevideo) fetch can be rejected with a
        // generic/default User-Agent even when the /player metadata call
        // that produced the URL succeeded — so ExoPlayer must present the
        // same client identity used to validate the URL in isUrlReachable(),
        // or a URL that "resolves" can still fail to actually play.
        // Kept in sync with androidSdkless's userAgent above, since that is
        // now the primary client player() tries first.
        const val STREAM_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
        const val FILTER_SONG = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_VIDEO = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        const val FILTER_ALBUM = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        const val FILTER_ARTIST = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        const val FILTER_PLAYLIST = "EgeKAQQoAEABagoQAxAEEAoQCRAF"
        const val BROWSE_HOME = "FEmusic_home"
        const val BROWSE_EXPLORE = "FEmusic_explore"
        const val BROWSE_CHARTS = "FEmusic_charts"
        const val BROWSE_MOODS = "FEmusic_moods_and_genres"
        const val BROWSE_NEW_RELEASES = "FEmusic_new_releases"
    }

    fun home(): HomeFeed = parseHome(post("browse", JSONObject().put("browseId", BROWSE_HOME), webMusic))

    fun explore(): HomeFeed = parseHome(post("browse", JSONObject().put("browseId", BROWSE_EXPLORE), webMusic))

    fun charts(): HomeFeed = parseHome(post("browse", JSONObject().put("browseId", BROWSE_CHARTS), webMusic))

    fun moods(): HomeFeed = parseHome(post("browse", JSONObject().put("browseId", BROWSE_MOODS), webMusic))

    fun newReleases(): HomeFeed = parseHome(post("browse", JSONObject().put("browseId", BROWSE_NEW_RELEASES), webMusic))

    fun browsePage(browseId: String, params: String = ""): CollectionPage {
        require(browseId.isNotBlank()) { "browseId is empty" }
        val body = JSONObject().put("browseId", browseId)
        if (params.isNotBlank()) body.put("params", params)
        val data = post("browse", body, webMusic)
        return parseCollection(data, browseId)
    }

    fun searchFiltered(query: String, filter: String = ""): List<MusicItem> {
        require(query.isNotBlank()) { "Search query is empty" }
        val body = JSONObject().put("query", query.trim())
        if (filter.isNotBlank()) body.put("params", filter)
        val data = post("search", body, webMusic)
        val items = ArrayList<MusicItem>()
        collectItems(data, items)
        return items.distinctBy { it.videoId.ifBlank { it.browseId } + it.kind }
            .filter { it.title.isNotBlank() }
            .take(50)
    }

    fun suggestions(query: String): List<String> {
        runCatching {
            val fromService = NaoMusicBackend.suggestions(query)
            if (fromService.isNotEmpty()) return fromService
        }
        if (query.isBlank()) return emptyList()
        return try {
            val data = post("get_search_suggestions", JSONObject().put("input", query.trim()), webMusic)
            val out = ArrayList<String>()
            walkSuggestions(data, out)
            out.distinct().take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun nextRadio(videoId: String): List<Track> {
        runCatching {
            val fromService = NaoMusicBackend.radio(videoId)
            if (fromService.isNotEmpty()) return fromService
        }
        require(videoId.isNotBlank()) { "videoId is empty" }
        return try {
            // playlistId "RDAMVM<videoId>" memicu YouTube membuat mix/radio
            // penuh untuk video ini (bukan cuma "up next" tunggal yang sering
            // kosong/sedikit tanpa playlistId ini).
            val data = post(
                "next",
                JSONObject()
                    .put("videoId", videoId)
                    .put("playlistId", "RDAMVM$videoId")
                    .put("isAudioOnly", true)
                    .put("enablePersistentPlaylistPanel", true)
                    .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL"),
                webMusic
            )
            val out = ArrayList<Track>()
            walk(data, out)
            walkPlaylistPanel(data, out)
            out.distinctBy { it.videoId }
                .filter { it.videoId.isNotBlank() && it.videoId != videoId && it.title.isNotBlank() }
                .take(40)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun related(videoId: String): List<Track> = nextRadio(videoId)

    private fun parseHome(data: JSONObject): HomeFeed {
        val chips = ArrayList<Chip>()
        val shelves = ArrayList<Shelf>()
        collectChips(data, chips)
        collectShelves(data, shelves)
        return HomeFeed(
            chips = chips.distinctBy { it.title }.take(16),
            shelves = shelves.filter { it.items.isNotEmpty() }.take(18)
        )
    }

    private fun parseCollection(data: JSONObject, browseId: String): CollectionPage {
        val header = findHeader(data)
        val title = header?.let { text(it.optJSONObject("title")).ifBlank { text(it.optJSONObject("header")) } }.orEmpty()
            .ifBlank { browseId }
        val subtitle = header?.let {
            text(it.optJSONObject("subtitle")).ifBlank { text(it.optJSONObject("strapline")) }
        }.orEmpty()
        val description = header?.let { text(it.optJSONObject("description")) }.orEmpty()
        val thumb = header?.let { thumbnail(it.optJSONObject("thumbnail")) }.orEmpty()
        val tracks = ArrayList<Track>()
        walk(data, tracks)
        val shelves = ArrayList<Shelf>()
        collectShelves(data, shelves)
        val kind = when {
            browseId.startsWith("MPREb") -> "album"
            browseId.startsWith("UC") -> "artist"
            else -> "playlist"
        }
        val playlistId = when {
            browseId.startsWith("VL") -> browseId.removePrefix("VL")
            browseId.startsWith("PL") -> browseId
            else -> ""
        }
        return CollectionPage(
            title = title.ifBlank { kind.replaceFirstChar { it.uppercase() } },
            subtitle = subtitle,
            thumbnail = thumb,
            description = description,
            kind = kind,
            browseId = browseId,
            playlistId = playlistId,
            tracks = tracks.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() },
            shelves = shelves.filter { it.items.isNotEmpty() }
        )
    }

    private fun findHeader(value: Any?): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("musicDetailHeaderRenderer")?.let { return it }
                value.optJSONObject("musicImmersiveHeaderRenderer")?.let { return it }
                value.optJSONObject("musicVisualHeaderRenderer")?.let { return it }
                value.optJSONObject("musicResponsiveHeaderRenderer")?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findHeader(value.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findHeader(value.opt(i))?.let { return it }
            }
        }
        return null
    }

    private fun collectChips(value: Any?, out: MutableList<Chip>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("chipCloudChipRenderer")?.let { chip ->
                    val title = text(chip.optJSONObject("text"))
                    val ep = chip.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    if (title.isNotBlank()) {
                        out.add(Chip(title, ep?.optString("browseId").orEmpty(), ep?.optString("params").orEmpty()))
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) collectChips(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectChips(value.opt(i), out)
        }
    }

    private fun collectShelves(value: Any?, out: MutableList<Shelf>) {
        when (value) {
            is JSONObject -> {
                val carousel = value.optJSONObject("musicCarouselShelfRenderer")
                val shelf = value.optJSONObject("musicShelfRenderer")
                val grid = value.optJSONObject("gridRenderer")
                val renderer = carousel ?: shelf ?: grid
                if (renderer != null) {
                    val header = renderer.optJSONObject("header")
                        ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        ?: renderer.optJSONObject("header")
                    val title = text(header?.optJSONObject("title")).ifBlank {
                        text(renderer.optJSONObject("title"))
                    }
                    val items = ArrayList<MusicItem>()
                    collectItems(renderer.optJSONArray("contents") ?: renderer.optJSONArray("items"), items)
                    if (title.isNotBlank() && items.isNotEmpty()) {
                        val ep = header?.optJSONObject("moreContentButton")
                            ?.optJSONObject("buttonRenderer")
                            ?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                        out.add(Shelf(title, items.take(20), ep?.optString("browseId").orEmpty(), ep?.optString("params").orEmpty()))
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "musicCarouselShelfRenderer" || k == "musicShelfRenderer" || k == "gridRenderer") continue
                    collectShelves(value.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectShelves(value.opt(i), out)
        }
    }

    private fun collectItems(value: Any?, out: MutableList<MusicItem>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("musicTwoRowItemRenderer")?.let { parseRichItem(it, out) }
                value.optJSONObject("musicResponsiveListItemRenderer")?.let { parseRichListItem(it, out) }
                value.optJSONObject("musicNavigationButtonRenderer")?.let { parseMoodButton(it, out) }
                value.optJSONObject("playlistPanelVideoRenderer")?.let { parsePanelVideo(it, out) }
                val keys = value.keys()
                while (keys.hasNext()) collectItems(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectItems(value.opt(i), out)
        }
    }

    private fun parseRichItem(renderer: JSONObject, out: MutableList<MusicItem>) {
        val watch = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val browse = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val videoId = renderer.optString("videoId").ifBlank { watch?.optString("videoId").orEmpty() }
        val browseId = browse?.optString("browseId").orEmpty()
        val playlistId = watch?.optString("playlistId").orEmpty()
            .ifBlank { browseId.removePrefix("VL") }
        val title = text(renderer.optJSONObject("title"))
        val subtitle = text(renderer.optJSONObject("subtitle"))
        if (title.isBlank()) return
        val kind = classify(videoId, browseId)
        if (kind == "unknown") return
        out.add(
            MusicItem(
                title = title,
                subtitle = subtitle,
                thumbnail = thumbnail(renderer.optJSONObject("thumbnail")),
                videoId = videoId,
                browseId = browseId,
                playlistId = playlistId,
                params = browse?.optString("params").orEmpty(),
                kind = kind,
                artist = subtitle.substringBefore("•").trim(),
                album = if (kind == "album") title else ""
            )
        )
    }

    private fun parseRichListItem(renderer: JSONObject, out: MutableList<MusicItem>) {
        val flex = renderer.optJSONArray("flexColumns")
        val title = text(
            flex?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        val subtitle = text(
            flex?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
        val videoId = renderer.optString("videoId").ifBlank {
            renderer.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        }.ifBlank {
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        val browse = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val browseId = browse?.optString("browseId").orEmpty()
        if (title.isBlank()) return
        val kind = classify(videoId, browseId)
        if (kind == "unknown") return
        val parts = subtitle.split("•").map { it.trim() }.filter { it.isNotBlank() }
        out.add(
            MusicItem(
                title = title,
                subtitle = subtitle,
                thumbnail = thumbnail(renderer.optJSONObject("thumbnail")),
                videoId = videoId,
                browseId = browseId,
                playlistId = browseId.removePrefix("VL"),
                params = browse?.optString("params").orEmpty(),
                kind = kind,
                duration = parts.lastOrNull()?.takeIf { it.contains(":") }.orEmpty(),
                artist = parts.firstOrNull().orEmpty(),
                album = parts.drop(1).firstOrNull().orEmpty()
            )
        )
    }

    private fun parseMoodButton(renderer: JSONObject, out: MutableList<MusicItem>) {
        val title = text(renderer.optJSONObject("buttonText")).ifBlank { text(renderer.optJSONObject("text")) }
        val browse = renderer.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint")
            ?: renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val browseId = browse?.optString("browseId").orEmpty()
        if (title.isBlank() || browseId.isBlank()) return
        out.add(
            MusicItem(
                title = title,
                thumbnail = thumbnail(renderer.optJSONObject("solid") ?: renderer),
                browseId = browseId,
                params = browse?.optString("params").orEmpty(),
                kind = "mood"
            )
        )
    }

    private fun parsePanelVideo(renderer: JSONObject, out: MutableList<MusicItem>) {
        val videoId = renderer.optString("videoId")
        val title = text(renderer.optJSONObject("title"))
        val artist = text(renderer.optJSONObject("shortBylineText")).ifBlank {
            text(renderer.optJSONObject("longBylineText"))
        }
        if (videoId.isBlank() || title.isBlank()) return
        out.add(
            MusicItem(
                title = title,
                subtitle = artist,
                thumbnail = thumbnail(renderer.optJSONObject("thumbnail")),
                videoId = videoId,
                kind = "song",
                artist = artist
            )
        )
    }

    private fun walkPlaylistPanel(value: Any?, out: MutableList<Track>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("playlistPanelVideoRenderer")?.let { r ->
                    val id = r.optString("videoId")
                    val title = text(r.optJSONObject("title"))
                    val artist = text(r.optJSONObject("shortBylineText"))
                    if (id.isNotBlank() && title.isNotBlank()) {
                        out.add(Track(id, title, artist, thumbnail = thumbnail(r.optJSONObject("thumbnail"))))
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) walkPlaylistPanel(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) walkPlaylistPanel(value.opt(i), out)
        }
    }

    private fun walkSuggestions(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("searchSuggestionRenderer")?.let { r ->
                    val s = text(r.optJSONObject("suggestion"))
                    if (s.isNotBlank()) out.add(s)
                }
                val keys = value.keys()
                while (keys.hasNext()) walkSuggestions(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) walkSuggestions(value.opt(i), out)
        }
    }

    private fun classify(videoId: String, browseId: String): String = when {
        videoId.isNotBlank() -> "song"
        browseId.startsWith("MPREb") -> "album"
        browseId.startsWith("UC") -> "artist"
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("OLAK5uy") -> "playlist"
        browseId.startsWith("FEmusic") || browseId.isNotBlank() -> "mood"
        else -> "unknown"
    }
}

