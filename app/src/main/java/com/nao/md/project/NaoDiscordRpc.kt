package com.nao.md.project

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discord Rich Presence transport for Android (ArchiveTune style).
 *
 * Same path ArchiveTune uses:
 *  - OAuth browser connect stores an access_token (openid + identify +
 *    sdk.social_layer_presence)
 *  - opens wss://gateway.discord.gg (v10, json)
 *  - IDENTIFY with "Bearer <access_token>" (or a raw account token if provided)
 *  - heartbeats with the HELLO interval (jittered first beat)
 *  - reconnects automatically (op 7 RECONNECT, op 9 INVALID_SESSION, socket drops)
 *  - sends op 3 (Presence Update) with title, artist, album, artwork and Nao branding
 *
 * Artwork URLs are converted to Discord asset paths through the
 * `applications/{id}/external-assets` endpoint so remote YouTube Music
 * thumbnails render as the large image, and the Nao Music logo renders as the
 * small image (branding).
 */
object NaoDiscordRpc {
    private const val TAG = "NaoDiscordRpc"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json"
    private const val API = "https://discord.com/api/v10"

    private val io = Executors.newSingleThreadExecutor()
    private val assetIo = Executors.newFixedThreadPool(2)
    private val assetsInFlight = HashSet<String>()
    private val timers = Executors.newSingleThreadScheduledExecutor()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var reconnectTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var sequence: Int? = null
    private var identified = false
    private var token: String = ""
    private var applicationId: String = NaoDiscordManager.DEFAULT_APPLICATION_ID
    private var reconnectAttempt = 0
    private val wantConnection = AtomicBoolean(false)
    private var lastSignature: String = ""

    @Volatile
    private var pending: Activity? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var connected: Boolean = false
        private set

    private val externalAssetCache = HashMap<String, String>()

    data class Activity(
        val title: String,
        val artist: String,
        val artworkUrl: String,
        val paused: Boolean,
        val durationMs: Long,
        val positionMs: Long = 0L,
        val album: String = ""
    )

    fun isEnabledFor(context: Context): Boolean =
        !NaoDiscordManager.gatewayAuthorization(context).isNullOrBlank() &&
            NaoDiscordManager.isRichPresenceEnabled(context)

    /** Publishes (or refreshes) the Nao Music activity. */
    fun setActivity(context: Context, activity: Activity) {
        val auth = NaoDiscordManager.gatewayAuthorization(context)
        if (auth.isNullOrBlank() || !NaoDiscordManager.isRichPresenceEnabled(context)) return
        applicationId = NaoDiscordManager.configuredClientId(context)
        pending = activity
        wantConnection.set(true)
        io.execute {
            try {
                if (token != auth) {
                    token = auth
                    lastSignature = ""
                    closeSocket()
                }
                if (socket == null) {
                    openSocket()
                } else if (identified) {
                    sendPresence(activity)
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "presence failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    /** Removes the activity from the Discord profile (keeps the socket alive). */
    fun clear() {
        pending = null
        lastSignature = ""
        io.execute {
            try {
                if (identified) {
                    socket?.send(
                        JSONObject().apply {
                            put("op", 3)
                            put(
                                "d",
                                JSONObject().apply {
                                    put("since", 0)
                                    put("activities", JSONArray())
                                    put("status", "online")
                                    put("afk", false)
                                }
                            )
                        }.toString()
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun disconnect() {
        pending = null
        lastSignature = ""
        wantConnection.set(false)
        io.execute {
            reconnectTask?.cancel(false)
            reconnectTask = null
            closeSocket()
        }
    }

    /** Manual refresh: drop the socket and reconnect with current credentials. */
    fun forceReconnect(context: Context) {
        val auth = NaoDiscordManager.gatewayAuthorization(context)
        if (auth.isNullOrBlank()) {
            lastError = "Belum terhubung. Hubungkan Discord (browser) dulu."
            return
        }
        if (!NaoDiscordManager.isRichPresenceEnabled(context)) {
            lastError = "Rich Presence dimatikan."
            return
        }
        applicationId = NaoDiscordManager.configuredClientId(context)
        token = auth
        lastSignature = ""
        lastError = null
        reconnectAttempt = 0
        wantConnection.set(true)
        io.execute {
            reconnectTask?.cancel(false)
            reconnectTask = null
            closeSocket()
            try {
                openSocket()
            } catch (e: Exception) {
                lastError = e.message
                scheduleReconnect()
            }
        }
    }

    private fun closeSocket() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
        identified = false
        connected = false
        sequence = null
        lastSignature = ""
    }

    /** Discord drops idle/roaming sockets; without this the presence never comes back. */
    private fun scheduleReconnect() {
        if (!wantConnection.get()) return
        if (reconnectTask?.isDone == false) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delay = (1L shl reconnectAttempt).coerceAtMost(60L)
        reconnectTask = timers.schedule({
            io.execute {
                if (!wantConnection.get()) return@execute
                if (socket == null && token.isNotBlank()) {
                    try {
                        openSocket()
                    } catch (e: Exception) {
                        lastError = e.message
                        scheduleReconnect()
                    }
                }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun openSocket() {
        val request = Request.Builder().url(GATEWAY).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                io.execute { handleFrame(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = t.message ?: "gateway error"
                Log.w(TAG, "gateway failure: ${t.message}")
                io.execute {
                    if (socket === webSocket) {
                        closeSocket()
                        scheduleReconnect()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                io.execute {
                    if (socket === webSocket) {
                        closeSocket()
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    private fun handleFrame(webSocket: WebSocket, text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        if (!json.isNull("s")) sequence = json.optInt("s")
        when (json.optInt("op", -1)) {
            10 -> {
                val interval = json.optJSONObject("d")?.optLong("heartbeat_interval", 41250L) ?: 41250L
                startHeartbeat(webSocket, interval)
                identify(webSocket)
            }
            11 -> connected = true
            7 -> {
                // Gateway asked us to reconnect.
                closeSocket()
                scheduleReconnect()
            }
            9 -> {
                lastError = "Sesi Discord ditolak. Hubungkan ulang Discord (browser) lalu coba lagi."
                closeSocket()
                // Do not loop forever on bad credentials — stop after a few tries.
                if (reconnectAttempt >= 3) {
                    wantConnection.set(false)
                } else {
                    scheduleReconnect()
                }
            }
            0 -> {
                if (json.optString("t") == "READY") {
                    identified = true
                    connected = true
                    lastError = null
                    reconnectAttempt = 0
                    lastSignature = ""
                    pending?.let { sendPresence(it) }
                }
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        heartbeatTask?.cancel(false)
        val jitter = (intervalMs * Math.random()).toLong().coerceAtLeast(1000L)
        heartbeatTask = timers.scheduleAtFixedRate({
            try {
                webSocket.send(
                    JSONObject().apply {
                        put("op", 1)
                        if (sequence == null) put("d", JSONObject.NULL) else put("d", sequence)
                    }.toString()
                )
            } catch (_: Exception) {
            }
        }, jitter, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun identify(webSocket: WebSocket) {
        // Match ArchiveTune GatewayClient.sendIdentify() so OAuth Bearer tokens
        // are accepted the same way (capabilities + intents + properties).
        val capabilities = 69680 // DEDUPE_USER_OBJECTS | PRIORITIZED_READY | AUTO_CALL | AUTO_LOBBY
        val intents = 952897536 // same IntentsFlags set ArchiveTune uses
        val payload = JSONObject().apply {
            put("op", 2)
            put(
                "d",
                JSONObject().apply {
                    put("token", token)
                    put("capabilities", capabilities)
                    put("intents", intents)
                    put(
                        "properties",
                        JSONObject().apply {
                            put("os", "Android")
                            put("browser", "NaoMusic")
                            put("device", "Android")
                            put("browser_user_agent", "NaoMusic")
                            put("browser_version", "1.0")
                            put("client_version", "1.0")
                            put("client_build_number", 1)
                            put("native_build_number", 1)
                            put("release_channel", "unknown")
                        }
                    )
                    put("compress", false)
                }
            )
        }
        webSocket.send(payload.toString())
    }

    private fun sendPresence(activity: Activity) {
        val sock = socket ?: return
        if (!identified) return

        // Discord rate limits presence updates; skip redundant frames.
        val signature = listOf(
            activity.title,
            activity.artist,
            activity.album,
            activity.paused.toString(),
            (activity.positionMs / 5000L).toString(),
            activity.artworkUrl
        ).joinToString("|")
        if (signature == lastSignature) return
        lastSignature = signature

        val now = System.currentTimeMillis()
        // Artwork must never block the activity: publish right away with what is
        // already cached, then resolve the remote asset in the background and
        // republish. A slow or failing external-assets call used to stop the
        // whole presence from ever reaching Discord.
        val large = cachedAsset(activity.artworkUrl)
        val small = cachedAsset(NaoDiscordManager.BRANDING_IMAGE_URL)
        if (large.isBlank() && activity.artworkUrl.isNotBlank()) warmAsset(activity.artworkUrl, activity)
        if (small.isBlank()) warmAsset(NaoDiscordManager.BRANDING_IMAGE_URL, activity)

        val assets = JSONObject().apply {
            if (large.isNotBlank()) {
                put("large_image", large)
                put(
                    "large_text",
                    activity.album.ifBlank { activity.artist.ifBlank { "Nao Music" } }
                )
            }
            if (small.isNotBlank()) put("small_image", small)
            put("small_text", if (activity.paused) "Nao Music · Paused" else "Nao Music")
        }

        val payload = JSONObject().apply {
            put("name", "Nao Music")
            put("type", 2) // LISTENING
            put("created_at", now)
            put("flags", 1)
            put("application_id", applicationId)
            put("details", activity.title.ifBlank { "Nao Music" })
            put(
                "state",
                (if (activity.paused) "Paused · " else "") +
                    activity.artist.ifBlank { "Nao Music" }
            )
            put("assets", assets)
            put("party", JSONObject().apply { put("id", "nao-music") })
            if (!activity.paused) {
                // Position-aware timestamps so the Discord bar matches playback
                // instead of restarting from zero on every update.
                val start = now - activity.positionMs.coerceAtLeast(0L)
                put(
                    "timestamps",
                    JSONObject().apply {
                        put("start", start)
                        if (activity.durationMs > 0) put("end", start + activity.durationMs)
                    }
                )
            }
            put("buttons", JSONArray().apply { put("Open Nao Music") })
            put(
                "metadata",
                JSONObject().apply {
                    put(
                        "button_urls",
                        JSONArray().apply { put(NaoDiscordManager.WEBSITE_URL) }
                    )
                }
            )
        }

        try {
            sock.send(
                JSONObject().apply {
                    put("op", 3)
                    put(
                        "d",
                        JSONObject().apply {
                            put("since", 0)
                            put("activities", JSONArray().apply { put(payload) })
                            put("status", "online")
                            put("afk", false)
                        }
                    )
                }.toString()
            )
        } catch (e: Exception) {
            lastError = e.message
            lastSignature = ""
            scheduleReconnect()
        }
    }

    /**
     * Discord only renders images it hosts. Remote thumbnails must be
     * registered as external assets first, which returns an `mp:external/...`
     * path usable as `large_image` / `small_image`.
     */
    private fun cachedAsset(url: String): String {
        if (url.isBlank()) return ""
        return synchronized(externalAssetCache) { externalAssetCache[url] }.orEmpty()
    }

    /** Resolves an external asset off the presence path, then republishes it. */
    private fun warmAsset(url: String, activity: Activity) {
        if (url.isBlank() || token.isBlank()) return
        synchronized(assetsInFlight) { if (!assetsInFlight.add(url)) return }
        assetIo.execute {
            val resolved = resolveAsset(url)
            synchronized(assetsInFlight) { assetsInFlight.remove(url) }
            if (resolved.isBlank()) return@execute
            io.execute {
                lastSignature = ""
                sendPresence(pending ?: activity)
            }
        }
    }

    private fun resolveAsset(url: String): String {
        if (url.isBlank() || token.isBlank()) return ""
        cachedAsset(url).takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val body = JSONObject().apply {
                put("urls", JSONArray().apply { put(url) })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$API/applications/$applicationId/external-assets")
                // [token] is already the full Authorization value from
                // gatewayAuthorization(): "Bearer <oauth>" or a raw account token.
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) Discord-Android/277")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return ""
                val path = JSONArray(raw).optJSONObject(0)?.optString("external_asset_path").orEmpty()
                if (path.isBlank()) return ""
                val asset = "mp:$path"
                synchronized(externalAssetCache) { externalAssetCache[url] = asset }
                asset
            }
        } catch (_: Exception) {
            ""
        }
    }
}
