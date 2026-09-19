package com.nao.md.project.music

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
 * Real YouTube stream extraction backend.
 *
 * NewPipeExtractor is itself an InnerTube-based extractor.  It is used here
 * only for the stream-resolution step, because YouTube's current player
 * responses may require signature/n-parameter handling and PO-token-aware
 * client selection.  The Nao Music UI and player remain fully native.
 */
object YouTubeStreamResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
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
            Localization("en", "US"),
            ContentCountry("US")
        )
        initialized = true
    }

    data class ResolvedAudio(
        val url: String,
        val mimeType: String,
        val bitrate: Int
    )

    data class ResolvedVideo(
        val url: String,
        val mimeType: String,
        val width: Int,
        val height: Int
    )

    /**
     * Old Android versions (8-12) lack some java.* methods the extractor calls,
     * e.g. URLDecoder.decode(String, Charset) (API 33+). Core library desugaring
     * backports them, but a device/build combination that still misses one would
     * throw a LinkageError, which is an Error and therefore not caught by the
     * `catch (e: Exception)` fallbacks in the UI. Translate it into a normal
     * exception so playback falls back instead of crashing the app.
     */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IOException(
            "Ekstraksi YouTube tidak didukung di versi Android ini: ${e.message}",
            e
        )
    }

    data class SearchVideo(
        val videoId: String,
        val title: String,
        val author: String,
        val lengthSeconds: Long,
        val viewCount: Long,
        val publishedText: String,
        val thumbnail: String
    )

    /** Pencarian video YouTube langsung lewat NewPipe (cadangan bila Invidious gagal). */
    fun searchVideos(query: String): List<SearchVideo> = guarded {
        require(query.isNotBlank()) { "query is empty" }
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
                val id = Regex("""[?&]v=([^&]+)""").find(item.url)?.groupValues?.getOrNull(1)
                    ?: item.url.substringAfter("youtu.be/", "").substringBefore("?").substringBefore("&")
                if (id.isBlank() || item.name.isNullOrBlank()) return@mapNotNull null
                SearchVideo(
                    videoId = id,
                    title = item.name.orEmpty(),
                    author = item.uploaderName.orEmpty(),
                    lengthSeconds = item.duration.coerceAtLeast(0L),
                    viewCount = item.viewCount.coerceAtLeast(0L),
                    publishedText = item.textualUploadDate.orEmpty(),
                    thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty()
                )
            }
            .distinctBy { it.videoId }
            .take(40)
    }

    fun resolveVideo(videoId: String): List<ResolvedVideo> = guarded {
        require(videoId.isNotBlank()) { "videoId is empty" }
        ensureInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        extractor.getVideoStreams()
            .filter { !it.url.isNullOrBlank() && !it.isVideoOnly() }
            .map { stream: VideoStream ->
                ResolvedVideo(
                    stream.url.orEmpty(),
                    stream.format?.mimeType.orEmpty(),
                    stream.getWidth(),
                    stream.getHeight()
                )
            }
            .sortedWith(compareByDescending<ResolvedVideo> { it.width <= 480 }.thenByDescending { it.height })
            .take(3)
    }

    fun resolve(videoId: String): List<ResolvedAudio> = guarded {
        require(videoId.isNotBlank()) { "videoId is empty" }
        ensureInitialized()

        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        extractor.getAudioStreams()
            .filter { !it.url.isNullOrBlank() }
            .map { stream: AudioStream ->
                ResolvedAudio(
                    stream.url.orEmpty(),
                    stream.format?.mimeType.orEmpty(),
                    stream.getAverageBitrate()
                )
            }
            .sortedByDescending { it.bitrate }
    }
}
