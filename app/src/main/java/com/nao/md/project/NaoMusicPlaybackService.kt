package com.nao.md.project

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nao.md.project.music.InnerTubeClient
import java.util.concurrent.CopyOnWriteArraySet

@androidx.media3.common.util.UnstableApi
class NaoMusicPlaybackService : MediaSessionService() {
    // Notifies notification/QS media panel to refresh (buffering + tombol
    // Next/Previous nonaktif) tiap kali status NaoMediaLoadingGuard berubah.
    private val loadingListener: () -> Unit = { pushTransportState() }

    // Jaga-jaga: kalau MainActivity tidak pernah melepas status loading
    // (proses dibunuh sistem di tengah resolve, dsb), status ini tetap
    // otomatis pulih sendiri supaya notifikasi tidak nyangkut selamanya.
    private val loadingWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val loadingWatchdogRunnable = Runnable { NaoMediaLoadingGuard.finish() }

    companion object {
        const val ACTION_PREVIOUS = "com.nao.md.project.MEDIA_PREVIOUS"
        const val ACTION_NEXT = "com.nao.md.project.MEDIA_NEXT"

        private const val CMD_SHUFFLE = "com.nao.md.project.CMD_SHUFFLE"
        private const val CMD_REPEAT = "com.nao.md.project.CMD_REPEAT"
        private const val CMD_CLOSE = "com.nao.md.project.CMD_CLOSE"

        // Lets MainActivity ask the already-running service to redraw the
        // shuffle/repeat notification buttons after the user toggles them
        // from inside the app, so the notification stays in sync.
        @Volatile private var activeService: NaoMusicPlaybackService? = null

        fun refreshCustomLayout() {
            activeService?.pushCustomLayout()
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: Player
    private lateinit var session: MediaSession
    private val prefs by lazy { getSharedPreferences("nao_prefs", MODE_PRIVATE) }

    // Discord drops presences on reconnect; a light heartbeat republishes the
    // current track so the "Listening to Nao Music" activity stays visible.
    private val presenceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val presenceTicker = object : Runnable {
        override fun run() {
            publishPresence()
            presenceHandler.postDelayed(this, 20_000L)
        }
    }

    private fun publishPresence() {
        val item = player.currentMediaItem ?: return
        NaoDiscordPresenceBridge.update(
            this,
            item.mediaMetadata.title?.toString() ?: return,
            item.mediaMetadata.artist?.toString() ?: "Unknown",
            item.mediaMetadata.artworkUri?.toString().orEmpty(),
            player.duration.coerceAtLeast(0L),
            !player.isPlaying,
            player.currentPosition.coerceAtLeast(0L),
            item.mediaMetadata.albumTitle?.toString().orEmpty()
        )
    }

    /**
     * The app streams one resolved track at a time, so the ExoPlayer timeline
     * only ever holds a single item. Without this wrapper Media3 reports
     * "no next / previous item", and the notification, lock screen, Android
     * Auto and headset transport keys all hide skip controls.
     *
     * This forwarding player always advertises the skip commands and routes
     * them back to the Nao queue in MainActivity.
     */
    private inner class TransportPlayer(inner: Player) : ForwardingPlayer(inner) {
        // Media3 hanya mendorong ulang status ke notifikasi/panel media saat
        // player aslinya memicu event. Karena status "loading" di sini bukan
        // event ExoPlayer sungguhan, listener session harus ditangkap sendiri
        // supaya bisa dipanggil manual lewat publishLoadingState().
        private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            super.addListener(listener)
            sessionListeners.add(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(listener)
            sessionListeners.remove(listener)
        }

        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            if (NaoMediaLoadingGuard.isLoading()) {
                // Selagi lagu berikutnya sedang di-resolve, tombol
                // Next/Previous disembunyikan sebagai "nonaktif" dari sisi
                // sistem (notifikasi & panel media Android 14/15), supaya
                // tap kedua tidak diteruskan sama sekali — bukan cuma
                // diabaikan diam-diam di lapisan aplikasi.
                builder
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            return builder.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            val isSeekCommand = command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            if (isSeekCommand) return !NaoMediaLoadingGuard.isLoading()
            return super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = !NaoMediaLoadingGuard.isLoading()

        override fun hasPreviousMediaItem(): Boolean = !NaoMediaLoadingGuard.isLoading()

        // Selagi loading, laporkan status buffering ke notifikasi/panel
        // media supaya muncul indikator sedang memuat, bukan diam saja
        // seolah tidak merespons tap.
        override fun getPlaybackState(): Int =
            if (NaoMediaLoadingGuard.isLoading()) Player.STATE_BUFFERING else super.getPlaybackState()

        /** Paksa notifikasi/panel media membaca ulang playbackState +
         * availableCommands di atas — dipanggil tiap kali status loading
         * berubah lewat [pushTransportState]. */
        fun publishLoadingState() {
            val commands = availableCommands
            val state = playbackState
            val flags = FlagSet.Builder()
                .add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                .build()
            for (listener in sessionListeners) {
                listener.onAvailableCommandsChanged(commands)
                listener.onPlaybackStateChanged(state)
                listener.onEvents(this, Player.Events(flags))
            }
        }

        // These used to just forward to the inner (single-item) player,
        // which had nothing to skip to and silently did nothing — that is
        // why Previous/Next in the notification looked "broken" and only
        // Play/Pause worked. They now route the tap back into Nao Music's
        // real queue logic in MainActivity — gated by NaoMediaLoadingGuard
        // so a second tap while a track is still being resolved is ignored
        // (with a warning toast) instead of stealing the next queued song.
        override fun seekToNext() = attemptTransport(ACTION_NEXT)

        override fun seekToNextMediaItem() = attemptTransport(ACTION_NEXT)

        override fun seekToPrevious() = attemptTransport(ACTION_PREVIOUS)

        override fun seekToPreviousMediaItem() = attemptTransport(ACTION_PREVIOUS)

        private fun attemptTransport(action: String) {
            if (!NaoMediaLoadingGuard.begin()) {
                Toast.makeText(
                    this@NaoMusicPlaybackService,
                    NaoLang.t("Lagu sedang dimuat, tunggu sebentar\u2026"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // Jaga-jaga 20 detik kalau resolve tidak pernah selesai/gagal
            // melapor balik (proses dibunuh, dsb) — lihat NaoMediaLoadingGuard.
            loadingWatchdog.removeCallbacks(loadingWatchdogRunnable)
            loadingWatchdog.postDelayed(loadingWatchdogRunnable, 20_000L)
            pushTransportState()
            sendTransportAction(action)
        }
    }

    private fun pushTransportState() {
        try { (sessionPlayer as? TransportPlayer)?.publishLoadingState() } catch (_: Exception) {}
        if (!NaoMediaLoadingGuard.isLoading()) {
            loadingWatchdog.removeCallbacks(loadingWatchdogRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        NaoMediaLoadingGuard.addListener(loadingListener)
        // media3-datasource-okhttp was already a dependency in
        // build.gradle.kts but never actually wired up: ExoPlayer.Builder()
        // with no MediaSourceFactory falls back to a default HTTP data
        // source with a generic "AndroidXMedia3/..." User-Agent. That header
        // mismatch is one reason a resolved stream URL can validate fine on
        // its own but still fail to actually play — the googlevideo request
        // during real playback looks like it's coming from a different
        // client than the one that was used to obtain the URL. Using
        // Media3's own DefaultHttpDataSource here (rather than pulling in a
        // separate OkHttp client/stack) with the same User-Agent as
        // InnerTubeClient's URL-reachability probe keeps both requests
        // consistent, and setAllowCrossProtocolRedirects covers the
        // http->https redirects googlevideo CDNs sometimes issue.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(InnerTubeClient.STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                // handleAudioFocus = false: Nao Music must keep playing when
                // another app (video player, game) starts making sound, instead
                // of auto-pausing/ducking on audio-focus loss. The user only
                // wants playback interrupted when they pull the headset out
                // (handled below by setHandleAudioBecomingNoisy) or manually
                // swipe the app away from Recents (handled in onTaskRemoved).
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false
                )
                setHandleAudioBecomingNoisy(true)
            }
        sessionPlayer = TransportPlayer(player)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString() ?: return
                val artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"
                NaoDiscordPresenceBridge.update(
                    this@NaoMusicPlaybackService,
                    title,
                    artist,
                    mediaItem.mediaMetadata.artworkUri?.toString().orEmpty(),
                    player.duration.coerceAtLeast(0L),
                    !player.isPlaying,
                    player.currentPosition.coerceAtLeast(0L),
                    mediaItem.mediaMetadata.albumTitle?.toString().orEmpty()
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                presenceHandler.removeCallbacks(presenceTicker)
                presenceHandler.postDelayed(presenceTicker, 20_000L)
                player.currentMediaItem?.let {
                    NaoDiscordPresenceBridge.update(
                        this@NaoMusicPlaybackService,
                        it.mediaMetadata.title?.toString() ?: "Unknown",
                        it.mediaMetadata.artist?.toString() ?: "Unknown",
                        it.mediaMetadata.artworkUri?.toString().orEmpty(),
                        player.duration.coerceAtLeast(0L),
                        !isPlaying,
                        player.currentPosition.coerceAtLeast(0L),
                        it.mediaMetadata.albumTitle?.toString().orEmpty()
                    )
                }
            }
        })

        session = MediaSession.Builder(this, sessionPlayer)
            .setId("NaoMusic")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    controllerSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                            .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                            .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                            .add(SessionCommand(CMD_CLOSE, Bundle.EMPTY))
                            .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(controllerSession)
                        .setAvailableSessionCommands(sessionCommands)
                        .setCustomLayout(buildCustomLayout())
                        .build()
                }

                override fun onCustomCommand(
                    controllerSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_SHUFFLE -> {
                            if (!MainActivity.dispatchShuffleToggle()) {
                                // No live app instance (rare/cold case): flip
                                // the persisted flag so the app picks it up
                                // next time it restores its session state.
                                prefs.edit()
                                    .putBoolean("music_shuffle", !prefs.getBoolean("music_shuffle", false))
                                    .apply()
                            }
                            pushCustomLayout()
                        }
                        CMD_REPEAT -> {
                            if (!MainActivity.dispatchRepeatCycle()) {
                                val next = (prefs.getInt("music_repeat_mode", 0) + 1) % 3
                                prefs.edit().putInt("music_repeat_mode", next).apply()
                            }
                            pushCustomLayout()
                        }
                        CMD_CLOSE -> {
                            MainActivity.dispatchClosePlayback()
                            player.stop()
                            stopSelf()
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()

        val notificationProvider = DefaultMediaNotificationProvider(this)
        // Android 8 menolak adaptive icon (mipmap-anydpi-v26) sebagai small
        // icon notifikasi dan mematikan proses dengan "Bad notification".
        // Vector mono dipakai supaya aman di semua versi Android.
        notificationProvider.setSmallIcon(R.drawable.nao_music)
        setMediaNotificationProvider(notificationProvider)
    }

    /** Builds the close row shown under the notification's cover art.
     * Tombol acak (shuffle) dan ulangi (repeat) sengaja tidak lagi
     * ditampilkan di notifikasi — kontrolnya tetap ada di dalam app. */
    private fun buildCustomLayout(): List<CommandButton> {
        return listOf(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setSessionCommand(SessionCommand(CMD_CLOSE, Bundle.EMPTY))
                .setIconResId(R.drawable.close)
                .setDisplayName("Tutup")
                .setEnabled(true)
                .build()
        )
    }

    private fun pushCustomLayout() {
        try { session.setCustomLayout(buildCustomLayout()) } catch (_: Exception) {}
    }

    private fun sendTransportAction(action: String) {
        sendBroadcast(Intent(this, NaoMusicCommandReceiver::class.java).setAction(action))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Best-effort resilience: ask the system to restart this service if
        // it gets killed under memory pressure. Android can still kill it
        // (and nothing can make a service truly unkillable — see onTaskRemoved
        // below and the note in the reply), but START_STICKY at least gives
        // it a chance to come back instead of staying dead.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Per permintaan pengguna: swipe manual dari Recents TIDAK lagi
        // menghentikan playback. Satu-satunya cara resmi untuk menutup dari
        // dalam app/notifikasi sekarang hanya tombol X (CMD_CLOSE) di
        // notifikasi media. android:stopWithTask="false" di manifest sudah
        // menjaga OS agar tidak otomatis membunuh service ini saat task
        // dihapus; di sini kita sengaja TIDAK memanggil stopSelf()/player.stop().
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        NaoMediaLoadingGuard.removeListener(loadingListener)
        loadingWatchdog.removeCallbacks(loadingWatchdogRunnable)
        presenceHandler.removeCallbacks(presenceTicker)
        session.release()
        player.release()
        NaoDiscordRpc.disconnect()
        super.onDestroy()
    }
}
