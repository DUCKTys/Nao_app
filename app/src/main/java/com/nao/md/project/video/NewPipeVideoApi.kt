package com.nao.md.project.video

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client API video berbasis NewPipeExtractor (https://github.com/TeamNewPipe/NewPipe).
 *
 * Menggunakan NewPipeExtractor secara langsung seperti aplikasi NewPipe native:
 *  - Tidak bergantung instance Invidious eksternal
 *  - Ekstraksi stream YouTube langsung (signature, n-parameter, PO-token)
 *  - Pencarian, trending, detail video, related videos, channel info
 *  - Stream gabungan (video+audio) siap putar ExoPlayer
 *
 * UI tetap native (lihat NaoVideoPages), tidak ada WebView.
 */
class NewPipeVideoApi(private val context: Context) {

    companion object {
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }

    @Volatile
    var lastError: String = ""
        private set

    data class VideoItem(
        val videoId: String,
        val title: String,
        val author: String = "",
        val authorId: String = "",
        val authorUrl: String = "",
        val lengthSeconds: Long = 0L,
        val viewCount: Long = 0L,
        val publishedText: String = "",
        val thumbnail: String = "",
        val isLive: Boolean = false
    )

    data class Stream(
        val url: String,
        val mimeType: String = "video/mp4",
        val resolution: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val itag: Int = 0,
        val fps: Int = 0,
        val bitrate: Long = 0,
        val isAudioOnly: Boolean = false,
        val isVideoOnly: Boolean = false
    )

    data class ChannelInfo(
        val channelId: String,
        val name: String,
        val avatarUrl: String = "",
        val subscriberCount: Long = 0,
        val subscriberText: String = "",
        val description: String = "",
        val verified: Boolean = false
    )

    data class VideoDetail(
        val video: VideoItem,
        val description: String = "",
        val streams: List<Stream> = emptyList(),
        val audioStreams: List<Stream> = emptyList(),
        val related: List<VideoItem> = emptyList(),
        val channel: ChannelInfo? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        val downloader = object : Downloader() {
            override fun execute(request: Request): Response {
                val builder = OkRequest.Builder().url(request.url())
                request.headers().forEach { (key, values) ->
                    if (values.isNotEmpty()) builder.header(key, values.joinToString(","))
                }

                val bodyBytes = request.dataToSend()
                val method = request.httpMethod().uppercase()
                if (bodyBytes != null && method != "GET" && method != "HEAD") {
                    builder.method(method, bodyBytes.toRequestBody(null))
                } else {
                    builder.method(method, null)
                }

                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val headers = response.headers.toMultimap()
                    return Response(
                        response.code,
                        response.message,
                        headers,
                        body,
                        response.request.url.toString()
                    )
                }
            }
        }

        NewPipe.init(
            downloader,
            Localization("id", "ID"),
            ContentCountry("ID")
        )
        initialized = true
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        lastError = "Ekstraksi YouTube tidak didukung di versi Android ini: ${e.message}"
        throw IOException(lastError, e)
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        throw IOException(lastError, e)
    }

    private fun extractVideoId(url: String): String {
        return Regex("""[?&]v=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: url.substringAfter("youtu.be/", "").substringBefore("?").substringBefore("&")
    }

    /** Cari video via NewPipe (YouTube search). */
    fun search(query: String): List<VideoItem> = guarded {
        require(query.isNotBlank()) { "Query pencarian kosong" }
        ensureInitialized()
        val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
            query.trim(),
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            null
        )
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = extractVideoId(item.url)
                if (id.isBlank() || item.name.isNullOrBlank()) return@mapNotNull null
                VideoItem(
                    videoId = id,
                    title = item.name.orEmpty(),
                    author = item.uploaderName.orEmpty(),
                    authorId = item.uploaderUrl?.substringAfterLast("/").orEmpty(),
                    authorUrl = item.uploaderUrl.orEmpty(),
                    lengthSeconds = item.duration.coerceAtLeast(0L),
                    viewCount = item.viewCount.coerceAtLeast(0L),
                    publishedText = item.textualUploadDate.orEmpty(),
                    thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty()
                )
            }
            .distinctBy { it.videoId }
            .take(40)
    }

    /** Trending via pencarian kategori (NewPipe tidak punya endpoint trending khusus). */
    fun trending(type: String = "video"): List<VideoItem> = guarded {
        val fallbackQuery = when (type) {
            "music" -> "top music videos Indonesia"
            "gaming" -> "gaming highlights Indonesia"
            "movies" -> "official movie trailers"
            "news" -> "berita terkini Indonesia"
            else -> "trending video Indonesia"
        }
        search(fallbackQuery)
    }

    /** Detail video lengkap + stream + related + channel. */
    fun video(videoId: String): VideoDetail = guarded {
        require(videoId.isNotBlank()) { "videoId kosong" }
        ensureInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val info = extractor.infoItems
        val title = info.name.orEmpty()
        val author = info.uploaderName.orEmpty()
        val authorUrl = info.uploaderUrl.orEmpty()
        val authorId = authorUrl.substringAfterLast("/")
        val duration = info.duration.coerceAtLeast(0L)
        val viewCount = info.viewCount.coerceAtLeast(0L)
        val publishedText = info.textualUploadDate.orEmpty()
        val thumbnail = info.thumbnails.firstOrNull()?.url.orEmpty()
        val description = info.description.orEmpty()
        val isLive = info.isLiveStream

        // Stream video gabungan (video+audio) - sorted by quality desc
        val videoStreams = extractor.getVideoStreams()
            .filter { !it.url.isNullOrBlank() && !it.isVideoOnly() }
            .map { stream ->
                Stream(
                    url = stream.url.orEmpty(),
                    mimeType = stream.format?.mimeType.orEmpty(),
                    resolution = "${stream.getWidth()}x${stream.getHeight()}",
                    width = stream.getWidth(),
                    height = stream.getHeight(),
                    itag = (stream.format?.id as? String)?.toIntOrNull() ?: (stream.format?.id as? Int) ?: 0,
                    fps = stream.fps,
                    bitrate = stream.averageBitrate.toLong(),
                    isAudioOnly = false,
                    isVideoOnly = false
                )
            }
            .sortedWith(compareByDescending<Stream> { it.height }.thenByDescending { it.width })
            .distinctBy { it.resolution }

        // Stream audio-only (untuk background play / audio only mode)
        val audioStreams = extractor.getAudioStreams()
            .filter { !it.url.isNullOrBlank() }
            .map { stream ->
                Stream(
                    url = stream.url.orEmpty(),
                    mimeType = stream.format?.mimeType.orEmpty(),
                    resolution = "Audio only",
                    width = 0,
                    height = 0,
                    itag = (stream.format?.id as? String)?.toIntOrNull() ?: (stream.format?.id as? Int) ?: 0,
                    bitrate = stream.getAverageBitrate().toLong(),
                    isAudioOnly = true,
                    isVideoOnly = false
                )
            }
            .sortedByDescending { it.bitrate }

        // Related videos
        val related = extractor.relatedContent
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = extractVideoId(item.url)
                if (id.isBlank() || item.name.isNullOrBlank()) return@mapNotNull null
                VideoItem(
                    videoId = id,
                    title = item.name.orEmpty(),
                    author = item.uploaderName.orEmpty(),
                    authorId = item.uploaderUrl?.substringAfterLast("/").orEmpty(),
                    authorUrl = item.uploaderUrl.orEmpty(),
                    lengthSeconds = item.duration.coerceAtLeast(0L),
                    viewCount = item.viewCount.coerceAtLeast(0L),
                    publishedText = item.textualUploadDate.orEmpty(),
                    thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty()
                )
            }
            .distinctBy { it.videoId }
            .take(20)

        // Channel info
        val channel = if (authorUrl.isNotBlank()) {
            try {
                val channelExtractor = ServiceList.YouTube.getChannelExtractor(authorUrl)
                channelExtractor.fetchPage()
                val cInfo = channelExtractor.infoItems
                ChannelInfo(
                    channelId = authorId,
                    name = author,
                    avatarUrl = cInfo.avatarUrl.orEmpty(),
                    subscriberCount = cInfo.subscriberCount.coerceAtLeast(0L),
                    subscriberText = formatSubCount(cInfo.subscriberCount.coerceAtLeast(0L)),
                    description = cInfo.description.orEmpty(),
                    verified = cInfo.verified
                )
            } catch (_: Exception) {
                ChannelInfo(
                    channelId = authorId,
                    name = author,
                    subscriberText = ""
                )
            }
        } else null

        val videoItem = VideoItem(
            videoId = videoId,
            title = title,
            author = author,
            authorId = authorId,
            authorUrl = authorUrl,
            lengthSeconds = duration,
            viewCount = viewCount,
            publishedText = publishedText,
            thumbnail = thumbnail,
            isLive = isLive
        )

        VideoDetail(
            video = videoItem,
            description = description,
            streams = videoStreams,
            audioStreams = audioStreams,
            related = related,
            channel = channel
        )
    }

    /** Resolve stream video gabungan untuk pemutaran langsung. */
    fun resolveVideoStreams(videoId: String): List<Stream> = guarded {
        require(videoId.isNotBlank()) { "videoId kosong" }
        ensureInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        return extractor.getVideoStreams()
            .filter { !it.url.isNullOrBlank() && !it.isVideoOnly() }
            .map { stream ->
                Stream(
                    url = stream.url.orEmpty(),
                    mimeType = stream.format?.mimeType.orEmpty(),
                    resolution = "${stream.getWidth()}x${stream.getHeight()}",
                    width = stream.getWidth(),
                    height = stream.getHeight(),
                    itag = (stream.format?.id as? String)?.toIntOrNull() ?: (stream.format?.id as? Int) ?: 0,
                    fps = stream.fps,
                    bitrate = stream.averageBitrate.toLong()
                )
            }
            .sortedWith(compareByDescending<Stream> { it.height }.thenByDescending { it.width })
            .distinctBy { it.resolution }
            .take(5)
    }

    private fun formatSubCount(count: Long): String = when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}