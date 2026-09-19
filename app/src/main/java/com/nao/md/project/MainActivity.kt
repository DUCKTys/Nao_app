package com.nao.md.project


import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.provider.MediaStore
import android.content.ContentUris
import java.net.HttpURLConnection
import android.view.ViewGroup.LayoutParams
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.content.ComponentName
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.nao.md.project.core.NaoAudioExtractor
import com.nao.md.project.core.NaoAudioTranscoder
import com.nao.md.project.core.NaoDownloadCenter
import com.nao.md.project.music.InnerTubeClient
import com.nao.md.project.music.NaoMusicBackend
import com.nao.md.project.music.YouTubeStreamResolver
import com.nao.md.project.music.NaoLyricsClient
import com.nao.md.project.music.MusicStore
import com.nao.md.project.music.NaoMusicPages
import com.nao.md.project.video.NewPipeVideoApi
import com.nao.md.project.video.NaoVideoPages
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    // Batasi skala teks sistem agar layout tidak pecah di HP dengan
    // pengaturan "ukuran font" besar.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(NaoUiCompat.wrapDisplay(newBase))
    }
    companion object {
        private const val REQ_DISCORD_TOKEN = 4210
        private const val PREF_LYRICS_PANEL_ON = "nao_music_lyrics_panel_on"

        // Lets the notification / lock-screen previous, next, shuffle and
        // repeat buttons act on the already-running app instance directly,
        // in memory, without ever bringing the app's UI to the foreground
        // (which used to interrupt whatever video/game the user was in).
        // Only used when the Activity object is still alive; if the whole
        // app process was killed, callers fall back to starting it.
        @Volatile private var activeInstance: MainActivity? = null

        fun dispatchTransportCommand(action: String): Boolean {
            val instance = activeInstance ?: return false
            instance.runOnUiThread { instance.handleMusicTransportIntent(Intent(action)) }
            return true
        }

        fun dispatchShuffleToggle(): Boolean {
            val instance = activeInstance ?: return false
            instance.runOnUiThread { instance.toggleMusicShuffle() }
            return true
        }

        fun dispatchRepeatCycle(): Boolean {
            val instance = activeInstance ?: return false
            instance.runOnUiThread { instance.cycleMusicRepeatMode() }
            return true
        }

        fun dispatchClosePlayback(): Boolean {
            val instance = activeInstance ?: return false
            instance.runOnUiThread { instance.finishAffinity() }
            return true
        }
    }
    // Was a single-thread executor shared by EVERYTHING: search, downloads,
    // uploads, media scanning, lyrics fetch, video-preview resolution, artwork
    // loading, etc. Because there was only one thread, any of those unrelated
    // background jobs still running would force a brand new "next/previous
    // song" request to sit in the queue behind it — this was the main cause
    // of playback feeling stuck on "Menyiapkan lagu..." for a long time.
    // A small pool lets unrelated jobs run without blocking each other.
    private val exec = Executors.newFixedThreadPool(4)
    // Track resolution/playback gets its own dedicated thread so it is NEVER
    // queued behind lyrics/video-preview/downloads/uploads/search, even if
    // the pool above is momentarily busy with several of those at once.
    private val musicResolveExec = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private var processingOverlay: FrameLayout? = null
    private val downloader = com.nao.md.project.core.DownloaderClient(this)
    private val innerTube = InnerTubeClient()
    private val musicStore by lazy { MusicStore(this) }
    private var naoMusicPages: NaoMusicPages? = null
    private var naoVideoPages: NaoVideoPages? = null
    private val newPipeVideoApi by lazy { NewPipeVideoApi(this) }
    private var musicPlayer: Player? = null
    private var musicNow: InnerTubeClient.Track? = null
    private var musicResults = emptyList<InnerTubeClient.Track>()
    private val musicQueue = ArrayList<InnerTubeClient.Track>()
    private var musicMiniPlayer: LinearLayout? = null
    private var musicMiniTitle: TextView? = null
    private var musicMiniArtist: TextView? = null
    private var musicMiniPlay: ImageView? = null
    private var musicMiniProgress: ProgressBar? = null
    private var musicCurrentArtwork: ImageView? = null
    private var musicMiniVideo: VideoView? = null
    private var musicMiniMedia: FrameLayout? = null
    private var musicProgressRunnable: Runnable? = null
    // Full-player sheet: the mini-player expands into a native, YouTube-Music-style now-playing surface.
    private var musicPlayerSheet: FrameLayout? = null
    private var musicSheetArtwork: ImageView? = null
    private var musicSheetVideo: VideoView? = null
    private var musicSheetTitle: TextView? = null
    private var musicSheetArtist: TextView? = null
    private var musicSheetProgress: SeekBar? = null
    private var musicSheetElapsed: TextView? = null
    private var musicSheetDuration: TextView? = null
    private var musicSheetMiniLyricScroll: ScrollView? = null
    private var musicSheetMiniLyricBox: LinearLayout? = null
    private var musicLyricsToggleBtn: TextView? = null
    private var musicLyricsToggleOn = true
    private var musicSheetPlay: ImageView? = null
    private var musicSheetQueue: LinearLayout? = null
    private var musicSheetStage: FrameLayout? = null
    private var musicSheetLyricsScroll: ScrollView? = null
    private var musicSheetLyricsBox: LinearLayout? = null
    private var musicSheetLyricsStatus: TextView? = null
    private var musicTabPreview: TextView? = null
    private var musicTabLyrics: TextView? = null
    private var musicLyricsVisible = false
    private var musicLyricsVideoId = ""
    private var musicLyricsLines: List<NaoLyricsClient.Line> = emptyList()
    private val musicLyricsViews = ArrayList<TextView>()
    private var musicLyricsActiveIndex = -1
    private var musicLyricsLoading = false
    private var musicLyricsOffsetMs = 0L
    private var musicLyricsOffsetLabel: TextView? = null
    private var musicLyricsTicker: Runnable? = null
    private var musicUiResumed = true
    private var musicRecommendationsLoaded = false
    private var musicRecommendationBusy = false
    // Nao Music Lab: experimental controls inspired by modern Android music players.
    private var musicSleepTimer: CountDownTimer? = null
    private var musicControllerFuture: ListenableFuture<MediaController>? = null
    private var musicAudioOnly = true
    // Modern mini-player playback modes: shuffle + repeat off/all/one.
    private var musicShuffleEnabled = false
    private var musicRepeatMode = 0 // 0 = off, 1 = repeat queue, 2 = repeat one
    private var musicMiniShuffle: TextView? = null
    private var musicMiniRepeat: TextView? = null
    private var musicMiniPrevious: ImageView? = null
    private var musicMiniNext: ImageView? = null
    private var musicSheetShuffle: ImageView? = null
    private var musicSheetRepeat: ImageView? = null
    private var musicRepeatPool = ArrayList<InnerTubeClient.Track>()
    // Urutan antrean asli sebelum diacak, dipakai untuk memulihkan urutan
    // ketika mode acak dimatikan kembali.
    private var musicQueueOrder = ArrayList<InnerTubeClient.Track>()
    private var musicCyclingRepeatQueue = false
    // Mode "putar playlist": hanya lagu dalam playlist yang diputar, lalu berhenti.
    private var musicPlaylistOnlyMode = false
    private var musicLyricsPending = false
    // musicSheetScroll dihapus: area tengah (artwork/judul/progress) sudah
    // tidak lagi dibungkus ScrollView-nya sendiri — lihat instalMusicPlayerSheet()
    // untuk deteksi "atas" gesture tutup, yang sekarang membaca scrollY
    // panel lirik (musicSheetMiniLyricScroll) langsung.
    private var musicRestorePosition = 0L
    private var musicRestoreWasPlaying = false
    // Session persistence guards: the saved queue must never be wiped by a
    // save that happens before the previous session has been restored, and a
    // freshly connected (empty) media session must not be treated as
    // "track finished".
    private var musicSessionRestored = false
    private var musicPlaybackStarted = false
    private var musicSubPage = false
    private val musicControlLock = AtomicBoolean(false)
    private val musicSwitchLock = AtomicBoolean(false)
    private val musicPreviousStack = ArrayList<InnerTubeClient.Track>()
    private var musicPlaybackGeneration = 0L
    // Other resolved stream URLs for the currently-playing track, in case
    // the one actually handed to ExoPlayer fails to play (see
    // handleMusicPlaybackError). Cleared/replaced every time a new track
    // starts resolving.
    private var musicStreamCandidates: List<String> = emptyList()
    private var musicStreamCandidateIndex: Int = 0
    private var pendingMusicTransportAction: String? = null
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var pickedFile: File? = null
    private var pendingPick: ((Uri) -> Unit)? = null
    private var currentTool: String? = null
    private var selectedNav: String = "Home"
    private val navItems = mutableMapOf<String, LinearLayout>()
    private val navIcons = mutableMapOf<String, ImageView>()
    private val prefs by lazy { getSharedPreferences("nao_prefs", MODE_PRIVATE) }
    private var pendingAfterPermission: (() -> Unit)? = null
    private val bootstrapPermissionRequestCode = 7001
    private val purple: Int get() = NaoThemeManager.current(this).bright
    // Warna aksen khusus untuk teks/ikon yang duduk DI ATAS kotak/card (yang sudah
    // ditintBox jadi pastel lembut untuk tema softCard seperti Pink Soft). Kalau
    // dipaksa pakai `purple` (varian terang) di atas card yang juga terang, teksnya
    // jadi nyaris tak kelihatan. Untuk tema softCard, pakai varian gelap supaya
    // kontras — card tetap pink soft, tapi tulisannya pink gelap.
    private val accentOnCard: Int get() = with(NaoThemeManager.current(this)) { if (softCard) dark else bright }
    private val bg = Color.rgb(8, 10, 16)
    private val surface = Color.rgb(13, 17, 25)
    private val text = Color.rgb(245, 247, 251)
    private val muted = Color.rgb(178, 187, 204)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Sesuaikan tinggi kotak preview (foto/video) dengan rasio aspek media
     * aslinya, supaya video/foto vertikal tidak dipotong jadi kotak dan
     * video/foto horizontal tidak diberi ruang kosong berlebih. Lebar kotak
     * mengikuti lebar layar (dikurangi padding kartu), tinggi dihitung dari
     * rasio lalu dibatasi supaya tetap wajar di layar kecil maupun besar.
     */
    private fun applyAspectHeight(view: View, mediaWidth: Int, mediaHeight: Int, minDp: Int = 200, maxDp: Int = 560) {
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        val availableWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(200))
        val ratio = mediaHeight.toFloat() / mediaWidth.toFloat()
        val newHeight = (availableWidth * ratio).toInt().coerceIn(dp(minDp), dp(maxDp))
        val lp = view.layoutParams ?: return
        if (lp.height == newHeight) return
        lp.height = newHeight
        view.layoutParams = lp
    }
    private fun tv(s: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = NaoLang.t(s)
        setTextColor(this@MainActivity.text)
        textSize = size
        if (bold) setTypeface(typeface, 1)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    private fun btn(s: String, primary: Boolean = false) = Button(this).apply {
        text = NaoLang.t(s)
        setTextColor(if (primary) bg else this@MainActivity.text)
        textSize = 13f
        isAllCaps = false
        minHeight = dp(46)
        setPadding(dp(16), dp(2), dp(16), dp(2))
        background = rounded(if (primary) NaoThemeManager.current(this@MainActivity).veryLight else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(23, 27, 38)), 20, true).apply {
            if (primary) setStroke(dp(1), Color.argb(180, 255, 255, 255))
        }
        stateListAnimator = null
        includeFontPadding = false
    }
    private fun rounded(color: Int = surface, radius: Int = 18, stroke: Boolean = true) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), Color.argb(24, 255, 255, 255))
    }

    /** Interpolasi linear antar dua warna RGB. ratio 0 = [from] penuh, ratio 1 = [to] penuh. */
    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        return Color.rgb(
            (Color.red(from) * inv + Color.red(to) * r).toInt(),
            (Color.green(from) * inv + Color.green(to) * r).toInt(),
            (Color.blue(from) * inv + Color.blue(to) * r).toInt(),
        )
    }

    /**
     * Latar utama aplikasi: dulu selalu [bg] polos apa pun temanya. Sekarang
     * jadi gradasi diagonal dari warna gelap tema aktif (pojok kiri-atas —
     * mengikuti mood "semprotan di tembok malam" di NaoThemeManager) yang
     * meluruh halus ke [bg] netral, supaya Ungu Neon / Pink Soft / Merah
     * Maroon benar-benar terasa di seluruh latar, bukan cuma di tombol.
     * Peluruhannya sengaja tuntas sebelum 2/3 layar supaya kontras teks putih
     * pada konten yang di-scroll tetap aman di mana pun.
     */
    private fun pageBackground(): GradientDrawable {
        val theme = NaoThemeManager.current(this)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                theme.dark,
                blend(theme.dark, bg, 0.5f),
                blend(theme.dark, bg, 0.85f),
                bg,
            )
        )
    }
    private fun applyMusicThumbnailShape(view: ImageView, radiusDp: Int = 14) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val r = dp(radiusDp).toFloat()
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
    }

    private fun modernMusicSeekDrawable(): LayerDrawable {
        // Deliberately thin: the mini-player should read like a subtle progress
        // line inside the rounded surface, not a large classic Android seek bar.
        val track = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(48, 53, 67)), 1, false)
        val progress = ClipDrawable(
            rounded(purple, 1, false),
            Gravity.LEFT,
            ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(track, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /**
     * Garis progress tipis untuk full player — setipis mini player.
     * Lapisan track & progress dibatasi tingginya lalu ditaruh di tengah,
     * jadi SeekBar boleh punya area sentuh besar tapi garisnya tetap halus.
     */
    private fun slimMusicSeekDrawable(): LayerDrawable {
        val lineHeight = dp(3)
        val track = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(48, 53, 67)), 1, false)
        val progress = ClipDrawable(
            rounded(purple, 1, false),
            Gravity.LEFT,
            ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(track, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerHeight(0, lineHeight)
            setLayerHeight(1, lineHeight)
            setLayerGravity(0, Gravity.CENTER_VERTICAL)
            setLayerGravity(1, Gravity.CENTER_VERTICAL)
        }
    }

    /** Bulatan kecil sebagai thumb garis progress full player. */
    private fun slimMusicSeekThumb(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(purple)
            setSize(dp(10), dp(10))
        }

    private fun modernMusicControl(label: String, size: Float = 20f, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(this@MainActivity.text)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(NaoThemeManager.current(this@MainActivity).mid, 20, true))
            addState(intArrayOf(), rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 28, 39)), 20, true))
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))
        stateListAnimator = null
        setOnClickListener {
            if (!musicControlLock.compareAndSet(false, true)) return@setOnClickListener
            animate().scaleX(.90f).scaleY(.90f).alpha(.72f).setDuration(70L).withEndAction {
                animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110L).start()
            }.start()
            action()
            postDelayed({ musicControlLock.set(false) }, 650L)
        }
    }

    /**
     * Tombol pemutar modern berbasis ikon: bulat penuh, tanpa glyph teks klasik.
     * `primary` dipakai untuk tombol play/pause utama (aksen ungu penuh).
     */
    private fun modernMusicIcon(
        icon: Int,
        iconSize: Int = 22,
        primary: Boolean = false,
        action: () -> Unit
    ) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setColorFilter(if (primary) bg else text, android.graphics.PorterDuff.Mode.SRC_IN)
        val pad = ((iconSize / 2f).toInt()).coerceAtLeast(8)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(if (primary) NaoThemeManager.current(this@MainActivity).bright else NaoThemeManager.current(this@MainActivity).dark, 999, false)
            )
            addState(
                intArrayOf(),
                rounded(if (primary) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false)
            )
        }
        elevation = if (primary) dp(3).toFloat() else 0f
        stateListAnimator = null
        isClickable = true
        setOnClickListener {
            if (!musicControlLock.compareAndSet(false, true)) return@setOnClickListener
            animate().scaleX(.90f).scaleY(.90f).alpha(.75f).setDuration(70L).withEndAction {
                animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110L).start()
            }.start()
            action()
            postDelayed({ musicControlLock.set(false) }, 650L)
        }
    }

    private fun roundedMusicArtwork(source: Bitmap, radiusPx: Float = 34f): Bitmap {
        val side = minOf(source.width, source.height).coerceAtLeast(1)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, left, top, side, side)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, side.toFloat(), side.toFloat())
        canvas.save()
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, null, rect, paint)
        paint.xfermode = null
        canvas.restore()
        if (square !== source && !square.isRecycled) square.recycle()
        return out
    }

    private fun loadMusicArtworkBytesBlocking(track: InnerTubeClient.Track): ByteArray? {
        val candidates = listOf(
            "https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg",
            track.thumbnail,
            "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg"
        ).filter { it.isNotBlank() }.distinct()
        for (candidate in candidates) {
            try {
                val connection = URL(candidate).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) NaoMD")
                connection.connect()
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val artwork = roundedMusicArtwork(bmp)
                try {
                    File(filesDir, "nao_music_artwork_${track.videoId}.png").outputStream().use { out ->
                        artwork.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    File(filesDir, "nao_music_artwork.png").outputStream().use { out ->
                        artwork.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (_: Exception) {}
                val out = ByteArrayOutputStream()
                artwork.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (artwork !== bmp && !artwork.isRecycled) artwork.recycle()
                if (!bmp.isRecycled) bmp.recycle()
                return out.toByteArray()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun loadMusicArtworkHd(view: ImageView, track: InnerTubeClient.Track) {
        applyMusicThumbnailShape(view, 14)
        // Fast path: memory/disk cache first, size-matched download in parallel.
        NaoArtworkLoader.load(view, track.videoId, track.thumbnail, big = false)
    }

    /** High resolution artwork for the now-playing surface. */
    private fun loadMusicArtworkBig(view: ImageView, track: InnerTubeClient.Track) {
        applyMusicThumbnailShape(view, 14)
        NaoArtworkLoader.load(view, track.videoId, track.thumbnail, big = true)
    }

    private fun loadMusicArtwork(view: ImageView, url: String, fallbackUrl: String = "") {
        applyMusicThumbnailShape(view, 14)
        val primary = url.ifBlank { fallbackUrl }
        if (primary.isBlank()) return
        val videoId = Regex("/vi/([^/]+)/").find(primary)?.groupValues?.getOrNull(1).orEmpty()
        NaoArtworkLoader.load(view, videoId, fallbackUrl.ifBlank { primary }, big = false)
    }

    private fun remoteImage(url: String, height: Int = 220, onBitmap: ((Int, Int) -> Unit)? = null) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundDrawable(rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(12, 15, 23)), 16))
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(-1, dp(height)).apply { setMargins(0, dp(10), 0, dp(10)) }
        if (url.isNotBlank()) Thread {
            try {
                val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                post {
                    if (bmp != null) {
                        setImageBitmap(bmp)
                        onBitmap?.invoke(bmp.width, bmp.height)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
    private fun previewVideo(url: String, height: Int = 220, onAspect: ((Int, Int) -> Unit)? = null) = FrameLayout(this).apply {
        val container = this
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(-1, dp(height)).apply {
            setMargins(0, dp(10), 0, dp(10))
        }
        clipChildren = true

        val thumbnail = ImageView(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "Video preview"
        }

        val video = VideoView(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            setBackgroundColor(Color.TRANSPARENT)
            setMediaController(null)
            visibility = View.VISIBLE
        }

        val loading = TextView(this@MainActivity).apply {
            text = NaoLang.t("Menyiapkan preview…")
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 188, 205))
            background = rounded(NaoThemeManager.tintBoxArgb(this@MainActivity, 175, 8, 10, 16), 12, false)
            layoutParams = FrameLayout.LayoutParams(
                dp(150), dp(38), Gravity.CENTER
            )
        }

        val play = TextView(this@MainActivity).apply {
            text = NaoLang.t("▶")
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(NaoThemeManager.tintBoxArgb(this@MainActivity, 210, 10, 12, 18), 22, false)
            layoutParams = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER)
            visibility = View.GONE
        }

        addView(video)
        addView(thumbnail)
        addView(loading)
        addView(play)

        fun showReady() {
            loading.visibility = View.GONE
            play.visibility = View.VISIBLE
            thumbnail.visibility = View.VISIBLE
            play.text = NaoLang.t("▶")
        }

        fun attachLocalFile(file: File) {
            try {
                // Decode a real frame first. This guarantees the preview is not
                // an empty black surface while MediaPlayer is preparing.
                Thread {
                    var bmp: android.graphics.Bitmap? = null
                    try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(file.absolutePath)
                        bmp = mmr.getFrameAtTime(
                            0L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        mmr.release()
                    } catch (_: Exception) {}

                    post {
                        if (bmp != null) {
                            thumbnail.setImageBitmap(bmp)
                            applyAspectHeight(container, bmp.width, bmp.height)
                            onAspect?.invoke(bmp.width, bmp.height)
                        }
                        try {
                            video.setVideoPath(file.absolutePath)
                            video.setOnPreparedListener { mp ->
                                mp.isLooping = false
                                mp.setVolume(1f, 1f)
                                showReady()
                            }
                            video.setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                    thumbnail.visibility = View.GONE
                                    play.visibility = View.VISIBLE
                                    play.text = NaoLang.t("Ⅱ")
                                }
                                false
                            }
                            video.setOnCompletionListener {
                                thumbnail.visibility = View.VISIBLE
                                play.visibility = View.VISIBLE
                                play.text = NaoLang.t("▶")
                            }
                            video.setOnErrorListener { _, what, extra ->
                                loading.visibility = View.GONE
                                play.visibility = View.GONE
                                Toast.makeText(this@MainActivity, NaoLang.t("Video tidak dapat diputar ($what/$extra)"),
                                    Toast.LENGTH_SHORT
                                ).show()
                                true
                            }
                        } catch (e: Exception) {
                            loading.text = NaoLang.t("Preview tidak tersedia")
                        }
                    }
                }.start()
            } catch (_: Exception) {
                loading.text = NaoLang.t("Preview tidak tersedia")
            }
        }

        fun downloadRemote() {
            Thread {
                try {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0 NaoMD")
                    }
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw Exception("HTTP ${connection.responseCode}")
                    }

                    val length = connection.contentLengthLong
                    if (length > 80L * 1024L * 1024L) {
                        throw Exception("Video terlalu besar untuk preview")
                    }

                    val ext = when {
                        url.contains(".webm", true) -> ".webm"
                        url.contains(".mov", true) -> ".mov"
                        url.contains(".mkv", true) -> ".mkv"
                        else -> ".mp4"
                    }
                    val file = File(cacheDir, "nao_preview_${System.currentTimeMillis()}$ext")
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    connection.disconnect()
                    post { attachLocalFile(file) }
                } catch (e: Exception) {
                    post {
                        loading.text = NaoLang.t("Preview gagal")
                        play.visibility = View.GONE
                        Toast.makeText(this@MainActivity, NaoLang.t("Gagal menyiapkan preview: ${e.message}"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
        }

        play.setOnClickListener {
            if (video.isPlaying) {
                video.pause()
                play.text = NaoLang.t("▶")
            } else {
                thumbnail.visibility = View.VISIBLE
                play.text = NaoLang.t("Ⅱ")
                video.start()
                // Keep the real frame visible until Android reports that
                // rendering has started. This prevents a black flash.
                postDelayed({
                    if (video.isPlaying) thumbnail.visibility = View.GONE
                }, 500)
            }
        }

        if (url.startsWith("file://")) {
            val file = try { File(Uri.parse(url).path ?: "") } catch (_: Exception) { null }
            if (file != null && file.exists()) attachLocalFile(file)
            else {
                loading.text = NaoLang.t("File video tidak ditemukan")
                play.visibility = View.GONE
            }
        } else {
            downloadRemote()
        }
    }

    private fun formatPreviewTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    /**
     * Preview audio ringan untuk kartu Downloader: streaming langsung dari
     * URL media (tanpa diunduh dulu ke berkas), tombol play/pause + seek bar
     * + waktu berjalan. Tombol "Download audio" tetap terpisah dan tetap
     * lewat NaoDownloadCenter seperti biasa.
     */
    private fun previewAudio(url: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)), 16, true)
        setPadding(dp(8), dp(6), dp(14), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(0, dp(4), 0, dp(2)) }

        val play = TextView(this@MainActivity).apply {
            text = NaoLang.t("▶")
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(NaoThemeManager.tintBoxArgb(this@MainActivity, 220, 10, 12, 18), 22, false)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val seek = SeekBar(this@MainActivity).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, dp(8), 0) }
        }
        val time = tv("0:00", 10f).apply { setTextColor(muted) }
        addView(play)
        addView(seek)
        addView(time)

        var player: MediaPlayer? = null
        var userSeeking = false
        val handler = android.os.Handler(mainLooper)
        val ticker = object : Runnable {
            override fun run() {
                player?.let { mp ->
                    try {
                        if (!userSeeking && mp.isPlaying) {
                            val dur = mp.duration
                            if (dur > 0) seek.progress = (mp.currentPosition.toLong() * 1000L / dur).toInt()
                            time.text = formatPreviewTime(mp.currentPosition.toLong())
                        }
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 400)
            }
        }

        fun releasePlayer() {
            handler.removeCallbacks(ticker)
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
        }

        fun preparePlayer() {
            play.text = NaoLang.t("…")
            Thread {
                try {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    mp.setDataSource(url)
                    mp.setOnPreparedListener {
                        runOnUiThread {
                            player = mp
                            seek.progress = 0
                            time.text = "0:00"
                            mp.start()
                            play.text = NaoLang.t("Ⅱ")
                            handler.post(ticker)
                        }
                    }
                    mp.setOnCompletionListener {
                        runOnUiThread {
                            play.text = NaoLang.t("▶")
                            seek.progress = 0
                            time.text = "0:00"
                        }
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        runOnUiThread {
                            play.text = NaoLang.t("▶")
                            Toast.makeText(this@MainActivity, NaoLang.t("Audio tidak dapat diputar ($what/$extra)"), Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    runOnUiThread {
                        play.text = NaoLang.t("▶")
                        Toast.makeText(this@MainActivity, NaoLang.t("Gagal memuat audio: ${e.message}"), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        play.setOnClickListener {
            val current = player
            when {
                current == null -> preparePlayer()
                current.isPlaying -> {
                    current.pause()
                    play.text = NaoLang.t("▶")
                }
                else -> {
                    current.start()
                    play.text = NaoLang.t("Ⅱ")
                    handler.post(ticker)
                }
            }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.let { mp ->
                    if (mp.duration > 0) time.text = formatPreviewTime(mp.duration.toLong() * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                player?.let { mp -> if (mp.duration > 0) mp.seekTo((mp.duration.toLong() * seek.progress / 1000L).toInt()) }
            }
        })

        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { releasePlayer() }
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 21, true)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
    }
    private fun assetImage(name: String, size: Int = 46) = ImageView(this).apply {
        setImageResource(resources.getIdentifier("nao_$name", "drawable", packageName))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private fun field(hint: String, multi: Boolean = false) = EditText(this).apply {
        setHint(hint); setHintTextColor(muted); setTextColor(this@MainActivity.text); textSize = 15f
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)), 15, true)
        if (multi) { minLines = 5; gravity = Gravity.TOP } else {
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        }
    }

    // Tutup keyboard otomatis setelah Enter / Done / Search ditekan.
    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        musicUiResumed = true
        // Do not reset the current page here. File pickers, WebViews and
        // returning from Recents all pass through onResume; resetting here
        // was the reason Video & Audio / AI Upscaler jumped back to Home.
    }

    override fun onPause() {
        super.onPause()
        // Hemat baterai: hentikan pekerjaan UI saat aplikasi di latar.
        musicUiResumed = false
        naoVideoPages?.pausePlayback()
    }

    private fun connectMusicController(onReady: (MediaController) -> Unit = {}) {
        musicPlayer?.let { p ->
            if (p is MediaController) { onReady(p); return }
        }
        if (musicControllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, NaoMusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        musicControllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                musicPlayer = controller
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateMusicMiniPlay(); updateMusicProgressNow()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        updateMusicProgressNow()
                        // A newly connected, empty session also reports ENDED.
                        // Only advance the queue once this app instance has
                        // actually started a track.
                        if (state == Player.STATE_ENDED && musicPlaybackStarted) {
                            root.postDelayed({ handleMusicEnded() }, 180L)
                        }
                    }
                    // Previously unhandled entirely: a failed stream (wrong
                    // codec, HTTP 403 from the CDN, expired URL, etc.) left
                    // the player silently sitting paused at 0:00 forever —
                    // no toast, no retry, nothing to tell the user what
                    // happened. This surfaces the failure and automatically
                    // tries the next resolved stream candidate, if any,
                    // before giving up.
                    override fun onPlayerError(error: PlaybackException) {
                        handleMusicPlaybackError(error)
                    }
                })
                onReady(controller)
                pendingMusicTransportAction?.let { action ->
                    pendingMusicTransportAction = null
                    root.post { handleMusicTransportIntent(Intent(action)) }
                }
            } catch (e: Exception) {
                Toast.makeText(this, NaoLang.t("Nao Music playback service tidak tersedia: ${e.message ?: "unknown error"}"), Toast.LENGTH_LONG).show()
                musicControllerFuture = null
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun buildMusicMediaItem(
        track: InnerTubeClient.Track,
        audioUrl: String,
        artworkData: ByteArray? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { "Nao Music" })
            .setAlbumTitle(track.album)
            .setArtworkUri(
                Uri.parse(
                    track.thumbnail.ifBlank {
                        "https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
                    }
                )
            )
        if (artworkData != null) {
            metadata.setArtworkData(
                artworkData,
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
        }
        return MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaId(track.videoId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun startResolvedMusic(
        track: InnerTubeClient.Track,
        candidates: List<String>,
        generation: Long
    ) {
        val audioUrl = candidates.first()
        runOnUiThread {
            if (generation != musicPlaybackGeneration) {
                endProcessing()
                return@runOnUiThread
            }
            connectMusicController { player ->
                if (generation != musicPlaybackGeneration) {
                    endProcessing()
                    return@connectMusicController
                }

                musicNow = track
                musicPlaybackStarted = true
                musicSessionRestored = true
                musicStreamCandidates = candidates
                musicStreamCandidateIndex = 0
                try { musicStore.recordPlay(track) } catch (_: Exception) {}
                // Lirik lama harus dibuang total sebelum lagu baru mulai,
                // supaya tidak pernah ada lirik lagu lain yang tertinggal.
                musicLyricsVideoId = ""
                musicLyricsLines = emptyList()
                musicLyricsActiveIndex = -1
                musicLyricsViews.clear()
                musicLyricsStatus("Memuat lirik\u2026")
                // Selalu dimuat, bukan cuma saat tab Informasi dibuka —
                // supaya panel lirik di bawah progress bar (Preview)
                // langsung punya isi tanpa harus pindah tab dulu.
                loadMusicLyrics()
                showMusicMini(track)
                musicSheetTitle?.text = track.title
                musicSheetArtist?.text = track.artist.ifBlank { "YouTube Music" }
                saveMusicSessionState()

                val cachedArtwork = try {
                    File(filesDir, "nao_music_artwork_${track.videoId}.png")
                        .takeIf { it.exists() }?.readBytes()
                } catch (_: Exception) {
                    null
                }

                val item = buildMusicMediaItem(track, audioUrl, cachedArtwork)
                player.setMediaItem(item)
                player.prepare()
                if (musicRestorePosition > 0L && musicNow?.videoId == track.videoId) {
                    player.seekTo(musicRestorePosition)
                    musicRestorePosition = 0L
                }
                player.play()
                musicSwitchLock.set(false)
                musicRestoreWasPlaying = true
                startMusicKeepAlive()
                // Prefetch the next queued track's stream now, in the
                // background, so tapping Next / auto-advance later can
                // usually skip the network round-trip entirely.
                musicQueue.firstOrNull()?.let { upcoming ->
                    musicResolveExec.execute { runCatching { NaoMusicBackend.prefetch(upcoming.videoId) } }
                }
                NaoDiscordManager.updateForTrack(
                    this,
                    track.title,
                    track.artist.ifBlank { "Nao Music" },
                    "https://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg",
                    false,
                    player.duration.coerceAtLeast(0L),
                    player.currentPosition.coerceAtLeast(0L),
                    track.album
                )
                endProcessing()
                NaoMediaLoadingGuard.finish()
                updateMusicMiniPlay()
                startMusicProgressLoop()

                // MediaSession/notification needs compressed artwork bytes for
                // reliable lock-screen rendering. Load it off the main thread
                // and replace the current MediaItem metadata when available.
                if (cachedArtwork == null) {
                    exec.execute {
                        val artwork = loadMusicArtworkBytesBlocking(track)
                        if (artwork != null) {
                            runOnUiThread {
                                val active = musicPlayer
                                if (generation != musicPlaybackGeneration ||
                                    active !== player ||
                                    musicNow?.videoId != track.videoId
                                ) return@runOnUiThread

                                val updated = buildMusicMediaItem(track, audioUrl, artwork)
                                active.replaceMediaItem(0, updated)
                                active.prepare()
                                active.play()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fires when ExoPlayer actually fails to play the resolved stream (bad
     * codec, expired/rejected URL, network drop mid-stream, etc). Before
     * this existed the player just sat paused at 0:00 forever with no
     * feedback at all. Silently retries the next resolved candidate URL for
     * this track (player() already returns several, sorted best-first) and
     * only bothers the user once every candidate has been exhausted.
     */
    private fun handleMusicPlaybackError(error: PlaybackException) {
        val player = musicPlayer ?: return
        val track = musicNow ?: return
        musicStreamCandidateIndex++
        if (musicStreamCandidateIndex < musicStreamCandidates.size) {
            val nextUrl = musicStreamCandidates[musicStreamCandidateIndex]
            val item = buildMusicMediaItem(track, nextUrl)
            player.setMediaItem(item)
            player.prepare()
            player.play()
            return
        }
        musicSwitchLock.set(false)
        endProcessing()
        NaoMediaLoadingGuard.finish()
        updateMusicMiniPlay()
        Toast.makeText(
            this,
            NaoLang.t("Nao Music: \"${track.title}\" gagal diputar (${error.errorCodeName}). Coba lagu lain."),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleMusicTransportIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NaoMusicPlaybackService.ACTION_PREVIOUS &&
            action != NaoMusicPlaybackService.ACTION_NEXT
        ) return

        if (musicPlayer == null) {
            pendingMusicTransportAction = action
            return
        }

        when (action) {
            NaoMusicPlaybackService.ACTION_PREVIOUS -> playPreviousMusic()
            NaoMusicPlaybackService.ACTION_NEXT -> playNextQueuedTrack()
        }
        saveMusicSessionState()
        intent.action = null
    }

    override fun onCreate(b: Bundle?) {
        applySavedLanguage()
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // setNavigationBarDividerColor hanya ada sejak Android 9 (API 28).
        // Tanpa penjagaan ini aplikasi langsung force close di Android 8.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT)
        }

        build()
        activeInstance = this
        // Panaskan koneksi ke layanan ytmusicapi lebih awal supaya handshake
        // TCP+TLS sudah selesai sebelum lagu pertama ditekan.
        NaoMusicBackend.init(this)
        NaoMusicBackend.warmUp()
        // Restore first: connecting the media controller can deliver player
        // callbacks that would otherwise overwrite the saved session.
        restoreMusicSessionState()
        connectMusicController()
        applyNaoTaskIdentity()
        handleMusicTransportIntent(intent)
        ensureNaoMdPermissions {
            restoreLastPage()
            // Verifikasi dulu ke service sebelum menampilkan mini player —
            // dipanggil selalu (bukan cuma saat musicRestoreWasPlaying),
            // supaya kalau service sudah benar-benar berhenti (ditutup lewat
            // tombol X / force-stop / dibunuh sistem) mini player yang
            // "nyangkut" langsung dibersihkan, bukan tampil tapi tidak bisa
            // dipencet.
            if (musicNow != null) {
                val restoreTrack = musicNow!!
                root.postDelayed({ resumeMusicOnReopen(restoreTrack) }, 220L)
            }
            root.postDelayed({ checkForAppUpdate(manual = false) }, 1200L)
            // Pop-up izin latar belakang selalu menyusul pop-up izin notifikasi.
            root.postDelayed({ showBackgroundRunPromptIfNeeded() }, 500L)
            root.postDelayed({ showDailyDonationPromptIfNeeded() }, 2200L)
        }
    }

    /** Nao MD owns its first-run permission flow. */
    private fun ensureNaoMdPermissions(after: () -> Unit) {
        if (prefs.getBoolean("bootstrap_permissions_done", false)) {
            after()
            return
        }

        val requested = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            // Android 8/9 masih memakai penyimpanan bersama untuk unduhan dan
            // pemindaian musik lokal, jadi izinnya harus diminta di sini.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (requested.isEmpty()) {
            prefs.edit().putBoolean("bootstrap_permissions_done", true).apply()
            after()
            return
        }

        pendingAfterPermission = after
        requestPermissions(requested.toTypedArray(), bootstrapPermissionRequestCode)
    }

    /**
     * Setelah dialog izin notifikasi ditutup, alur lanjut berjalan sehingga
     * pop-up izin latar belakang muncul tepat sesudahnya.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != bootstrapPermissionRequestCode) return
        prefs.edit().putBoolean("bootstrap_permissions_done", true).apply()
        val next = pendingAfterPermission
        pendingAfterPermission = null
        next?.invoke()
    }

    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pageBackground() }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val brandBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brandBox.addView(assetImage("nao_mark", 34))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brandText.addView(tv("CORE UTILITY SYSTEM", 9f).apply { setTextColor(muted) })
        brandText.addView(tv("Nao MD", 14f, true))
        brandBox.addView(brandText)
        topBar.addView(brandBox, LinearLayout.LayoutParams(0, -2, 1f))

        topBar.addView(tv("● Operational  99.9%", 10f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(137, 240, 180))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(13,17,25)), 999)
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 1
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(118), dp(34)).apply {
            setMargins(0, 0, dp(8), 0)
        })

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.settings)
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            contentDescription = "Pengaturan Nao MD"
            scaleType = ImageView.ScaleType.CENTER
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)), 16, true)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { showNaoMdSettings() }
        }
        topBar.addView(settingsButton, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false; scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(110))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(10, 13, 20)), 28, true)
        }
        navItems.clear(); navIcons.clear()
        listOf(
            Triple("Home", R.drawable.home_filled) { home() },
            Triple("Tools", R.drawable.tune) { tools() },
            Triple("Music", R.drawable.nao_music) { music() },
            Triple("Video", R.drawable.nao_video) { video() },
            Triple("Berkas", R.drawable.download) { berkas() },
            Triple("Core", R.drawable.info) { core() }
        ).forEach { (label, iconRes, action) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(Color.TRANSPARENT, 16, false)
                setOnClickListener { action() }
            }
            val iv = ImageView(this).apply {
                setImageResource(iconRes)
                alpha = 1f
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            item.addView(iv, LinearLayout.LayoutParams(-1, dp(24)))
            val navLabel = tv(label, 10f, true).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 0)
            }
            item.addView(navLabel, LinearLayout.LayoutParams(-1, dp(18)))
            navItems[label] = item; navIcons[label] = iv
            bottomBar.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        musicMiniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(17, 21, 31)), 28, true)
            elevation = dp(4).toFloat()
        }
        val miniRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }

        musicMiniMedia = FrameLayout(this).apply {
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(28, 32, 45)), 16, false)
            clipChildren = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
        }
        musicCurrentArtwork = ImageView(this).apply {
            setImageResource(R.drawable.nao_music_artwork)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Music artwork"
        }
        musicMiniMedia!!.addView(musicCurrentArtwork, FrameLayout.LayoutParams(-1, -1))

        musicMiniVideo = VideoView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            setMediaController(null)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                musicMiniVideo?.visibility = View.VISIBLE
                musicCurrentArtwork?.visibility = View.GONE
            }
            setOnErrorListener { _, _, _ ->
                musicMiniVideo?.visibility = View.GONE
                musicCurrentArtwork?.visibility = View.VISIBLE
                true
            }
        }
        musicMiniMedia!!.addView(
            musicMiniVideo,
            FrameLayout.LayoutParams(-1, -1)
        )
        miniRow.addView(musicMiniMedia, LinearLayout.LayoutParams(dp(58), dp(58)))

        val miniMeta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        musicMiniTitle = tv("Nothing playing", 13f, true)
        musicMiniArtist = tv("Nao Music", 10f).apply { setTextColor(muted) }
        miniMeta.addView(musicMiniTitle); miniMeta.addView(musicMiniArtist)
        miniRow.addView(miniMeta, LinearLayout.LayoutParams(0, -2, 1f))

        musicMiniPrevious = modernMusicIcon(R.drawable.nao_ic_prev, 20) {
            playPreviousMusic()
            saveMusicSessionState()
        }
        miniRow.addView(musicMiniPrevious, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })

        musicMiniPlay = modernMusicIcon(R.drawable.nao_ic_play, 20, primary = true) {
            musicPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            updateMusicMiniPlay()
            saveMusicSessionState()
        }
        miniRow.addView(musicMiniPlay, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })

        musicMiniNext = modernMusicIcon(R.drawable.nao_ic_next, 20) {
            playNextQueuedTrack()
            saveMusicSessionState()
        }
        miniRow.addView(musicMiniNext, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })

        // Usap ke atas (mini player atau bilah navigasi) membuka player penuh.
        fun swipeUpDetector(onTap: (() -> Unit)?): GestureDetector =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = onTap != null
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (onTap == null) return false
                    onTap.invoke()
                    return true
                }
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val dy = start.y - e2.y
                    val dx = kotlin.math.abs(e2.x - start.x)
                    if (dy > dp(24) && dy > dx && velocityY < -300f && musicNow != null) {
                        showMusicPlayerSheet()
                        return true
                    }
                    return false
                }
            })

        val miniSwipe = swipeUpDetector { showMusicPlayerSheet() }
        val miniSwipeListener = View.OnTouchListener { v, ev ->
            val handled = miniSwipe.onTouchEvent(ev)
            if (handled) v.parent?.requestDisallowInterceptTouchEvent(true)
            handled
        }
        musicMiniPlayer!!.setOnTouchListener(miniSwipeListener)
        miniRow.setOnTouchListener(miniSwipeListener)
        miniMeta.setOnTouchListener(miniSwipeListener)
        musicMiniMedia!!.setOnTouchListener(miniSwipeListener)

        // Bilah navigasi: usap ke atas membuka player musik (sudah ada),
        // dan usap ke kiri/kanan pindah tab (Home ⇄ Tools ⇄ Music ⇄ Video ⇄
        // Berkas ⇄ Core). Gestur ini SENGAJA hanya dipasang di bottomBar & navItems,
        // jadi usapan di luar bilah navigasi (mis. di area konten/scroll)
        // tidak akan memicu pindah tab.
        val navOrder = listOf("Home", "Tools", "Music", "Video", "Berkas", "Core")
        fun navigateToTab(label: String) {
            when (label) {
                "Home" -> home()
                "Tools" -> tools()
                "Music" -> music()
                "Video" -> video()
                "Berkas" -> berkas()
                "Core" -> core()
            }
        }
        val navSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dy = start.y - e2.y
                val dx = e2.x - start.x
                // Usap ke atas -> buka player penuh (perilaku lama, tetap dipertahankan).
                if (dy > dp(24) && dy > kotlin.math.abs(dx) && velocityY < -300f && musicNow != null) {
                    showMusicPlayerSheet()
                    return true
                }
                // Usap ke kiri/kanan -> pindah tab navigasi.
                if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(velocityX) > 300f) {
                    val currentIdx = navOrder.indexOf(selectedNav).let { if (it < 0) 0 else it }
                    val nextIdx = if (dx < 0) currentIdx + 1 else currentIdx - 1
                    if (nextIdx in navOrder.indices && nextIdx != currentIdx) {
                        navigateToTab(navOrder[nextIdx])
                        return true
                    }
                }
                return false
            }
        })
        val navSwipeListener = View.OnTouchListener { _, ev ->
            val handled = navSwipe.onTouchEvent(ev)
            // Jangan konsumsi ACTION_DOWN, supaya ketukan (tap) biasa pada
            // item navigasi tetap berjalan normal (memicu setOnClickListener).
            // Hanya konsumsi event saat usapan benar-benar terdeteksi sebagai
            // fling (lihat onFling di atas), sehingga usap kanan/kiri TETAP
            // bisa dipakai untuk pindah tab, dan tap TETAP bisa dipakai juga.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) false else handled
        }
        bottomBar.setOnTouchListener(navSwipeListener)
        navItems.values.forEach { it.setOnTouchListener(navSwipeListener) }

        musicMiniPlayer!!.addView(miniRow)
        musicMiniProgress = SeekBar(this).apply {
            max = 1000
            progress = 0
            isIndeterminate = false
            setPadding(dp(2), 0, dp(2), 0)
            progressDrawable = modernMusicSeekDrawable()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) musicPlayer?.let { player ->
                        if (player.duration > 0) player.seekTo((player.duration * progress / 1000f).toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val miniProgressHolder = FrameLayout(this).apply {
            setPadding(0, dp(3), 0, dp(3))
        }
        miniProgressHolder.addView(
            musicMiniProgress,
            FrameLayout.LayoutParams(-1, dp(1)).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(30)
                rightMargin = dp(30)
            }
        )
        musicMiniPlayer!!.addView(miniProgressHolder, LinearLayout.LayoutParams(-1, dp(7)).apply {
            topMargin = dp(6)
        })
        downloadIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(15, 19, 29)), 24, true)
            elevation = dp(4).toFloat()
        }
        root.addView(downloadIndicator, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(3), dp(8), 0) })
        root.addView(musicMiniPlayer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(3), dp(8), dp(3)) })
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateMusicModeControls()
        installMusicPlayerSheet()
        installProcessingOverlay()
        installDownloadIndicator()
        applyInsets()
    }

    // ============ INDIKATOR UNDUHAN GLOBAL (semua fitur) ============

    private var downloadIndicator: LinearLayout? = null
    private var downloadIndicatorTicker: Runnable? = null

    /**
     * Indikator progres ala browser yang tampil di semua halaman: berlaku untuk
     * Downloader, Nao Music (download lagu), AI Upscaler dan Video ke Audio.
     * Begitu sebuah unduhan mencapai 100%, barisnya hilang sendiri.
     */
    private fun installDownloadIndicator() {
        NaoDownloadCenter.restore(this)
        NaoDownloadCenter.listener = {
            runOnUiThread {
                renderDownloadIndicator()
                if (selectedNav == "Berkas") renderBerkas()
            }
        }
        downloadIndicatorTicker?.let { root.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                renderDownloadIndicator()
                root.postDelayed(this, if (NaoDownloadCenter.activeCount() > 0) 500L else 1500L)
            }
        }
        downloadIndicatorTicker = ticker
        root.post(ticker)
    }

    private fun renderDownloadIndicator() {
        val box = downloadIndicator ?: return
        val jobs = NaoDownloadCenter.jobs().filter { it.state != NaoDownloadCenter.State.DONE }
        if (jobs.isEmpty()) {
            if (box.visibility != View.GONE) { box.removeAllViews(); box.visibility = View.GONE }
            return
        }
        box.visibility = View.VISIBLE
        box.removeAllViews()
        val extra = jobs.size - 3
        jobs.take(3).forEach { job ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { berkas() }
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(tv(job.filename, 11f, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv(
                if (job.state == NaoDownloadCenter.State.FAILED) "gagal"
                else if (job.total > 0) "${job.percent}%" else "\u2026",
                11f, true
            ).apply {
                gravity = Gravity.END
                setTextColor(if (job.state == NaoDownloadCenter.State.FAILED) Color.rgb(240, 120, 120) else purple)
            }, LinearLayout.LayoutParams(dp(52), -2))
            row.addView(head)
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = job.state == NaoDownloadCenter.State.RUNNING && job.total <= 0
                progress = job.percent
                progressTintList = android.content.res.ColorStateList.valueOf(
                    when (job.state) {
                        NaoDownloadCenter.State.FAILED -> Color.rgb(240, 120, 120)
                        NaoDownloadCenter.State.PAUSED -> Color.rgb(230, 190, 100)
                        else -> purple
                    }
                )
            }, LinearLayout.LayoutParams(-1, dp(5)).apply { setMargins(0, dp(5), 0, 0) })
            box.addView(row)
        }
        if (extra > 0) {
            box.addView(tv("+$extra unduhan lain \u00b7 ketuk untuk lihat semua", 10f).apply {
                setTextColor(muted); setPadding(0, dp(4), 0, 0)
                setOnClickListener { berkas() }
            })
        }
    }

    private fun installProcessingOverlay() {
        val decor = window.decorView as? ViewGroup ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(NaoThemeManager.tintBoxArgb(this@MainActivity, 165, 5, 7, 12))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = 10000f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(16, 20, 30)), 22, true)
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val label = tv("Processing…", 13f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(spinner, LinearLayout.LayoutParams(dp(42), dp(42)))
        box.addView(label)
        overlay.addView(box, FrameLayout.LayoutParams(dp(190), dp(130), Gravity.CENTER))
        decor.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        processingOverlay = overlay
    }

    private fun setProcessing(active: Boolean, message: String = "Processing…") {
        runOnUiThread {
            processingOverlay?.let { overlay ->
                overlay.visibility = if (active) View.VISIBLE else View.GONE
                (overlay.getChildAt(0) as? ViewGroup)?.let { box ->
                    (box.getChildAt(1) as? TextView)?.text = message
                }
            }
        }
    }

    private fun beginProcessing(message: String): Boolean {
        if (!processing.compareAndSet(false, true)) {
            Toast.makeText(this, NaoLang.t("Masih memproses. Tunggu sampai selesai."), Toast.LENGTH_SHORT).show()
            return false
        }
        setProcessing(true, message)
        return true
    }

    private fun endProcessing() {
        processing.set(false)
        setProcessing(false)
    }


    private fun setNavSelected(label: String) {
        selectedNav = label
        prefs.edit().putString("last_nav", label).apply()
        navItems.forEach { (name, view) ->
            val active = name == label
            view.background = rounded(if (active) NaoThemeManager.current(this).dark else Color.TRANSPARENT, 16, active)
            navIcons[name]?.apply {
                alpha = 1f
                setColorFilter(if (active) purple else Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemTopInset = bars.top
            systemBottomInset = bars.bottom
            topBar.setPadding(topBar.paddingLeft, dp(10) + bars.top, topBar.paddingRight, dp(10))
            bottomBar.setPadding(bottomBar.paddingLeft, dp(8), bottomBar.paddingRight, dp(10) + bars.bottom)
            content.setPadding(content.paddingLeft, dp(20), content.paddingRight, dp(110) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun clear() {
        // Saat pindah ke tab lain, hentikan & lepas pemutar video Invidious
        // supaya tidak menimbun resource (ExoPlayer + TextureView). Tab Video
        // sendiri tidak di-release di sini karena sedang dirender ulang.
        if (selectedNav != "Video") naoVideoPages?.release()
        content.removeAllViews()
    }

    private fun title(k: String, h: String, p: String) {
        content.addView(tv(k.uppercase(), 11f, true))
        content.addView(tv(h, 29f, true))
        content.addView(tv(p, 14f).apply { setTextColor(muted); setPadding(0, dp(6), 0, dp(18)) })
    }

    private fun home() {
        setNavSelected("Home")
        clear()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                colors = intArrayOf(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(17, 20, 30)), NaoThemeManager.tintBox(this@MainActivity, Color.rgb(10, 13, 20)))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.argb(24, 255, 255, 255))
            }
            setPadding(dp(22), dp(24), dp(22), dp(12))
            elevation = dp(2).toFloat()
        }
        val kicker = tv("●  NEXT-GENERATION UTILITY LAYER", 9f, true).apply {
            setTextColor(Color.rgb(137, 147, 165)); letterSpacing = .12f
        }
        hero.addView(kicker)
        hero.addView(tv("One core.", 40f, true).apply { setPadding(0, dp(18), 0, 0); letterSpacing = -.04f })
        hero.addView(tv("Many capabilities.", 40f, true).apply { setTextColor(accentOnCard); letterSpacing = -.04f })
        hero.addView(tv("Ruang kerja terpadu untuk media dan utilitas digital. Dibuat untuk bergerak cepat tanpa kehilangan fokus.", 13f).apply {
            setTextColor(muted); setPadding(0, dp(14), 0, dp(14)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("03" to "modules", "24/7" to "ready", "v${appVersionName()}" to "core").forEachIndexed { i, pair ->
            val m = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            m.addView(tv(pair.first, 10f, true))
            m.addView(tv(" ${pair.second}", 10f).apply { setTextColor(muted) })
            meta.addView(m, LinearLayout.LayoutParams(0, -2, 1f))
        }
        hero.addView(meta)
        val visual = OrbitVisual(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(305)).apply { setMargins(0, dp(10), 0, 0) } }
        hero.addView(visual)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(18)) })

        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(tv("SYSTEM OVERVIEW", 9f, true).apply { setTextColor(muted); letterSpacing = .14f })
        head.addView(tv("Everything in one place.", 27f, true).apply { setPadding(0, dp(7), 0, dp(16)); letterSpacing = -.025f })
        content.addView(head)

        val stats = arrayOf(
            Triple("CORE STATUS", "Online", "All services operational"),
            Triple("MODULES", "03", "Focused capabilities"),
            Triple("ARCHITECTURE", "v${appVersionName()}", "Modular runtime"),
            Triple("EXPERIENCE", "Fast", "Responsive by default")
        )
        stats.forEach { item ->
            val c = card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(13,17,25)), 18) }
            c.addView(tv(item.first, 9f, true).apply { setTextColor(muted); letterSpacing = .12f })
            c.addView(tv(item.second, 25f, true).apply {
                setTextColor(if (item.first == "CORE STATUS") Color.rgb(121,227,167) else this@MainActivity.text)
                setPadding(0, dp(10), 0, dp(3))
            })
            c.addView(tv(item.third, 10f).apply { setTextColor(muted) })
            content.addView(c)
        }
    }

    private inner class OrbitVisual(context: android.content.Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val mode get() = 2 // Home uses the fixed Pulse Grid animation.
        private var value = 0f
        private val animationStartNanos = System.nanoTime()
        private val degreesPerSecond = 8.0f

        init {
            // Keep the home visual lightweight on mobile hardware.
            isFocusable = false
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            // Monotonic clock: no finite animator, no repeat/reset pause.
            val elapsedSeconds = (System.nanoTime() - animationStartNanos) / 1_000_000_000.0f
            value = elapsedSeconds * degreesPerSecond
            postDelayed({ if (isAttachedToWindow) invalidate() }, 42L)
            val cx = width / 2f; val cy = height / 2f + dp(8); val r1 = minOf(width, height) * .38f; val r2 = r1 * .70f
            when (mode) {
                0 -> drawAmbient(c, cx, cy, r1, r2)
                1 -> drawOrbit(c, cx, cy, r1, r2)
                else -> drawPulse(c, cx, cy, r1, r2)
            }
        }
        private fun drawAmbient(c: Canvas, cx: Float, cy: Float, r1: Float, r2: Float) {
            ring.color = Color.argb(30,157,140,255); c.drawCircle(cx, cy, r1, ring)
            ring.color = Color.argb(22,101,225,209); c.drawCircle(cx, cy, r2, ring)
            val pulse = 0.82f + 0.12f * kotlin.math.sin(Math.toRadians(value.toDouble())).toFloat()
            fill.color = Color.argb(240,10,13,20); fill.setShadowLayer(dp((35*pulse).toInt()).toFloat(),0f,0f,Color.argb(65,140,110,255)); c.drawRoundRect(RectF(cx-dp(44),cy-dp(44),cx+dp(44),cy+dp(44)),dp(22).toFloat(),dp(22).toFloat(),fill); fill.clearShadowLayer()
            drawMark(c,cx,cy)
            drawGlowDot(c,cx+r1*.62f,cy-r1*.48f,Color.rgb(183,170,255),6f+(2f*pulse))
            drawGlowDot(c,cx-r1*.54f,cy+r1*.48f,Color.rgb(114,231,215),4f+(1.5f*pulse))
            drawGlowDot(c,cx+r1*.22f,cy+r1*.70f,Color.rgb(255,199,118),3f)
        }
        private fun drawOrbit(c: Canvas, cx: Float, cy: Float, r1: Float, r2: Float) {
            ring.color = Color.argb(42,157,140,255); c.drawCircle(cx, cy, r1, ring)
            ring.color = Color.argb(38,101,225,209); c.drawCircle(cx, cy, r2, ring)
            ring.color = Color.argb(120,157,140,255); c.save(); c.rotate(value, cx, cy); c.drawArc(RectF(cx-r1,cy-r1,cx+r1,cy+r1), 0f, 72f, false, ring); c.restore()
            ring.color = Color.argb(100,101,225,209); c.save(); c.rotate(-value*1.45f, cx, cy); c.drawArc(RectF(cx-r2,cy-r2,cx+r2,cy+r2), 180f, 80f, false, ring); c.restore()
            drawCore(c,cx,cy)
            drawChip(c, cx+r1*.62f, cy-r1*.48f, "MEDIA", Color.rgb(114,231,215)); drawChip(c, cx-r1*.66f, cy+r1*.58f, "TOOLS", Color.rgb(183,170,255))
        }
        private fun drawPulse(c: Canvas, cx: Float, cy: Float, r1: Float, r2: Float) {
            // Stable home animation:
            // the new Nao icon remains centered while the eight technologies
            // orbit it smoothly and continuously.
            val phase = (System.nanoTime() / 1_000_000_000.0) * 0.14
            val breathe = (0.5f + 0.5f * kotlin.math.sin(phase)).toFloat()

            // Main orbital rings.
            val themeBright = NaoThemeManager.current(this@MainActivity).bright
            val themeRing = NaoThemeManager.current(this@MainActivity).ring
            ring.color = Color.argb((42 + breathe * 22).toInt(), Color.red(themeBright), Color.green(themeBright), Color.blue(themeBright))
            c.drawCircle(cx, cy, r1 * 0.82f, ring)
            ring.color = Color.argb((28 + breathe * 18).toInt(), 34, 211, 238)
            c.drawCircle(cx, cy, r1 * 0.69f, ring)

            // A moving arc gives the orbit a clear direction without rotating
            // the actual icon.
            c.save()
            c.rotate(value * 0.42f, cx, cy)
            ring.color = Color.argb(155, Color.red(themeRing), Color.green(themeRing), Color.blue(themeRing))
            c.drawArc(
                RectF(cx - r1 * 0.82f, cy - r1 * 0.82f,
                      cx + r1 * 0.82f, cy + r1 * 0.82f),
                -35f, 82f, false, ring
            )
            c.restore()

            val labels = arrayOf(
                "JavaScript", "TypeScript", "Kotlin", "HTML5",
                "CSS3", "Python", "Git", "GitHub"
            )
            val colors = intArrayOf(
                Color.rgb(247, 223, 30), Color.rgb(49, 120, 198),
                Color.rgb(127, 255, 0), Color.rgb(227, 79, 38),
                Color.rgb(38, 77, 228), Color.rgb(255, 212, 59),
                Color.rgb(240, 81, 51), Color.rgb(245, 245, 245)
            )

            val orbitRadius = r1 * 0.82f
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(8).toFloat()
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
            }

            labels.forEachIndexed { i, label ->
                val angle = phase + Math.toRadians((-90.0 + i * 45.0))
                val px = cx + kotlin.math.cos(angle).toFloat() * orbitRadius
                val py = cy + kotlin.math.sin(angle).toFloat() * orbitRadius

                val accent = colors[i]
                val alpha = (175 + 55 * (0.5f + 0.5f *
                    kotlin.math.sin(angle * 1.7)).toFloat()).toInt().coerceIn(0, 255)

                labelPaint.color = accent
                val textWidth = labelPaint.measureText(label)
                val boxW = textWidth + dp(14)
                val boxH = dp(20)

                fill.color = Color.argb(alpha, 8, 10, 16)
                c.drawRoundRect(
                    RectF(
                        px - boxW / 2f, py - boxH / 2f,
                        px + boxW / 2f, py + boxH / 2f
                    ),
                    dp(10).toFloat(), dp(10).toFloat(), fill
                )

                ring.color = Color.argb(105, accent shr 16 and 255, accent shr 8 and 255, accent and 255)
                c.drawRoundRect(
                    RectF(
                        px - boxW / 2f, py - boxH / 2f,
                        px + boxW / 2f, py + boxH / 2f
                    ),
                    dp(10).toFloat(), dp(10).toFloat(), ring
                )

                labelPaint.color = Color.argb(alpha, accent shr 16 and 255, accent shr 8 and 255, accent and 255)
                c.drawText(label, px - textWidth / 2f, py + dp(3).toFloat(), labelPaint)
            }

            // Moving particles on the orbit.
            for (i in 0 until 4) {
                val a = phase * 1.35 + Math.toRadians(i * 90.0)
                val px = cx + kotlin.math.cos(a).toFloat() * r1 * 0.91f
                val py = cy + kotlin.math.sin(a).toFloat() * r1 * 0.91f
                drawGlowDot(c, px, py, colors[(i * 2) % colors.size], 2.5f)
            }

            drawCore(c, cx, cy)
        }

        private fun drawCore(c: Canvas,cx:Float,cy:Float){
            fill.color = Color.argb(235, 8, 10, 16)
            fill.setShadowLayer(
                dp(28).toFloat(), 0f, 0f,
                Color.argb(90, Color.red(purple), Color.green(purple), Color.blue(purple))
            )
            c.drawRoundRect(
                RectF(cx-dp(49), cy-dp(49), cx+dp(49), cy+dp(49)),
                dp(25).toFloat(), dp(25).toFloat(), fill
            )
            fill.clearShadowLayer()
            drawMark(c, cx, cy)
        }

        private fun drawMark(c:Canvas,cx:Float,cy:Float){
            // The supplied new icon already has rounded transparent corners,
            // so no artificial white border is drawn around it.
            val mark = resources.getIdentifier("nao_nao_mark","drawable",packageName)
            val d = resources.getDrawable(mark, null)
            d.setBounds(
                cx.toInt()-dp(43), cy.toInt()-dp(43),
                cx.toInt()+dp(43), cy.toInt()+dp(43)
            )
            d.draw(c)
        }
        private fun drawGlowDot(c:Canvas,x:Float,y:Float,color:Int,r:Float){ fill.color=color; fill.setShadowLayer(dp(12).toFloat(),0f,0f,Color.argb(110, color shr 16 and 255, color shr 8 and 255, color and 255)); c.drawCircle(x,y,dp(r.toInt()).toFloat(),fill); fill.clearShadowLayer() }
        private fun drawChip(c: Canvas,x:Float,y:Float,label:String,color:Int){ fill.color=Color.argb(220,10,13,20); c.drawRoundRect(RectF(x-dp(28),y-dp(14),x+dp(28),y+dp(14)),dp(10).toFloat(),dp(10).toFloat(),fill); ring.color=Color.argb(30,255,255,255); c.drawRoundRect(RectF(x-dp(28),y-dp(14),x+dp(28),y+dp(14)),dp(10).toFloat(),dp(10).toFloat(),ring); val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=dp(8).toFloat();typeface=android.graphics.Typeface.MONOSPACE;isFakeBoldText=true}; c.drawText(label,x-p.measureText(label)/2,y+dp(3),p) }
        override fun onDetachedFromWindow(){ super.onDetachedFromWindow() }
    }

    private val toolsList = listOf(
        "downloader" to ("Downloader" to "Unduh media dari berbagai sumber."),
        "ai-upscaler" to ("Photo Upscale AI" to "Perbesar & pertajam resolusi foto pakai AI. Khusus foto."),
        "video-audio" to ("Video To Audio" to "Ubah video jadi audio, pilih formatnya.")
    )
    private fun tools() {
        setNavSelected("Tools")
        clear(); title("TOOL LIBRARY", "Pick a workspace.", "Four focused modules, one consistent experience.")
        val icons = mapOf("downloader" to "download", "ai-upscaler" to "spark", "video-audio" to "video")
        toolsList.forEach { (id, p) ->
            val c = card(); val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(assetImage(icons[id] ?: "spark", 46))
            val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            textBox.addView(tv(p.first, 17f, true)); textBox.addView(tv(p.second, 11f).apply { setTextColor(muted); setPadding(0, dp(5), 0, 0) })
            row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(tv("↗", 20f, true).apply { setTextColor(muted) })
            row.setOnClickListener { openTool(id) }; c.addView(row)
            content.addView(c)
            content.addView(tv("Ready   ●", 9f).apply {
                setTextColor(Color.rgb(121, 227, 167))
                setPadding(dp(4), 0, 0, dp(14))
            })
        }
    }

    private fun header(name: String, desc: String) {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = btn("‹  Tools")
        back.setOnClickListener { currentTool = null; prefs.edit().remove("last_tool").apply(); tools() }
        val clearButton = btn("Clear")
        clearButton.setOnClickListener {
            currentTool?.let { tool -> openTool(tool) }
        }
        actions.addView(back, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(clearButton, LinearLayout.LayoutParams(dp(88), dp(46)).apply { setMargins(dp(8), 0, 0, 0) })
        content.addView(actions)
        content.addView(tv(name, 28f, true))
        content.addView(tv(desc, 14f).apply { setTextColor(muted); setPadding(0, dp(4), 0, dp(16)) })
    }
    private fun openTool(id: String) {
        currentTool = id
        prefs.edit().putString("last_tool", id).putString("last_nav", "Tools").apply()
        clear()
        when (id) {
            "downloader" -> downloaderUi()
            "ai-upscaler" -> photoUpscaleUi()
            "video-audio" -> videoAudioUi()
        }
    }

    private fun workButton(label: String, action: () -> Unit) = btn(label, true).also { b ->
        b.setOnClickListener {
            if (!beginProcessing("Processing…")) return@setOnClickListener
            b.isEnabled = false
            b.text = NaoLang.t("Processing…")
            try {
                action()
            } catch (e: Exception) {
                done(b, label)
                endProcessing()
                Toast.makeText(this, NaoLang.t("Gagal: ${e.message}"), Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun done(b: Button, label: String) {
        runOnUiThread {
            b.isEnabled = true
            b.text = label
            endProcessing()
        }
    }

    private fun pick(mime: String, callback: (Uri) -> Unit) {
        pendingPick = callback
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 10)
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == REQ_DISCORD_TOKEN) {
            if (NaoDiscordManager.isConnected(this)) {
                NaoDiscordManager.setRichPresenceEnabled(this, true)
                activateRichPresenceNow()
                Toast.makeText(this, NaoLang.t("Discord terhubung ke aplikasi · Rich Presence siap."), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (c == Activity.RESULT_OK && d?.data != null) pendingPick?.invoke(d.data!!)
        pendingPick = null
    }
    private fun uriFile(uri: Uri): File {
        var displayName = "video_${System.currentTimeMillis()}.mp4"
        var mimeType = contentResolver.getType(uri)
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                if (name.isNotBlank()) displayName = name
            }
        }
        if (!displayName.contains('.')) {
            val ext = when (mimeType?.lowercase()) {
                "video/mp4" -> ".mp4"
                "video/webm" -> ".webm"
                "video/quicktime" -> ".mov"
                "video/x-matroska" -> ".mkv"
                "video/x-msvideo" -> ".avi"
                "video/mpeg" -> ".mpeg"
                else -> ".mp4"
            }
            displayName += ext
        }
        val safe = safeName(displayName).ifEmpty { "video_${System.currentTimeMillis()}.mp4" }
        val f = File(cacheDir, "nao_$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: throw Exception("Tidak dapat membaca file video.")
        return f
    }

    private fun downloaderUi() {
        header("Downloader", "Preview media, choose quality when available, then download one or all.")
        val box = card()
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val url = field("Paste URL…")
        val paste = btn("Tempel")
        paste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                url.setText(clip.getItemAt(0).coerceToText(this).toString())
                url.setSelection(url.text.length)
            }
        }
        urlRow.addView(url, LinearLayout.LayoutParams(0, -2, 1f))
        urlRow.addView(paste, LinearLayout.LayoutParams(dp(88), dp(52)).apply { setMargins(dp(8), 0, 0, 0) })
        box.addView(urlRow)

        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(result)
        lateinit var resolve: Button
        resolve = workButton("Resolve URL") {
            val u = url.text.toString().trim()
            if (u.isEmpty()) { done(resolve, "Resolve URL"); return@workButton }
            exec.execute {
                try {
                    val d = downloader.resolve(u)
                    val ds = d.optJSONArray("downloads") ?: JSONArray()
                    runOnUiThread {
                        result.removeAllViews()
                        val titleText = d.optString("title", "Media")
                        val platform = d.optString("platform", "Unknown")
                        val isYouTube = platform.contains("youtube", true) || u.contains("youtube.com", true) || u.contains("youtu.be", true)
                        result.addView(tv(titleText, 20f, true))
                        result.addView(tv("$platform  •  ${d.optString("provider", "API")}", 11f).apply {
                            setTextColor(muted); setPadding(0, dp(4), 0, dp(8))
                        })
                        val thumb = d.optString("thumbnail")
                        if (thumb.isNotBlank()) result.addView(remoteImage(thumb, 190))

                        val rawMedia = ArrayList<JSONObject>()
                        for (i in 0 until ds.length()) rawMedia.add(ds.getJSONObject(i))
                        val media = dedupeMedia(expandMediaOptions(rawMedia))
                        renderDownloaderResult(result, media, titleText, isYouTube)
                        done(resolve, "Resolve URL")
                    }
                } catch (e: Exception) {
                    val fallback = downloader.directMediaFallback(u)
                    if (fallback != null) {
                        runOnUiThread {
                            result.removeAllViews()
                            result.addView(tv("Direct media", 20f, true))
                            result.addView(tv("Provider mandiri · tidak melalui API downloader", 11f).apply { setTextColor(muted); setPadding(0, dp(4), 0, dp(8)) })
                            val rawMedia = fallback.optJSONArray("downloads")?.let { arr -> ArrayList<JSONObject>().apply { for (i in 0 until arr.length()) add(arr.getJSONObject(i)) } } ?: arrayListOf()
                            val media = dedupeMedia(expandMediaOptions(rawMedia))
                            val titleText = fallback.optString("title", "Media")
                            val platform = fallback.optString("platform", "Unknown")
                            val isYouTube = platform.contains("youtube", true) || u.contains("youtube.com", true) || u.contains("youtu.be", true)
                            // Jalur cadangan ini dulu langsung memanggil renderDownloaderMedia
                            // tanpa mode ("all" default), sehingga video & audio tampil
                            // tercampur dalam satu layar. Sekarang dipaksa lewat toggle
                            // Video/Audio yang sama seperti jalur resolve normal, supaya
                            // tampilannya konsisten terlepas dari sumbernya berhasil di-resolve
                            // API utama atau jatuh ke fallback direct-media.
                            renderDownloaderResult(result, media, titleText, isYouTube)
                            done(resolve, "Resolve URL")
                        }
                    } else {
                        runOnUiThread {
                            result.removeAllViews()
                            result.addView(tv("✕ Resolve gagal: ${e.message}", 14f).apply { setTextColor(Color.rgb(255,120,120)) })
                            done(resolve, "Resolve URL")
                        }
                    }
                }
            }
        }
        box.addView(resolve)
        content.addView(box)
    }

    private fun renderDownloaderMedia(result: ViewGroup, media: List<JSONObject>, title: String, mode: String = "all") {
        renderDownloaderCards(result, media, title, mode = mode)
    }

    /**
     * Titik render tunggal untuk hasil Downloader, dipakai baik oleh jalur
     * resolve normal maupun jalur cadangan (direct-media fallback), supaya
     * toggle Video/Audio selalu tampil dan video & audio tidak pernah
     * tercampur dalam satu layar, apa pun sumbernya (TikTok, Instagram,
     * Facebook, YouTube, dll) atau jalur mana yang berhasil me-resolve-nya.
     * Default layar pertama kali dibuka adalah mode Video.
     */
    private fun renderDownloaderResult(result: ViewGroup, media: List<JSONObject>, titleText: String, isYouTube: Boolean) {
        if (media.isEmpty()) {
            result.addView(tv("API tidak mengembalikan media.", 14f).apply { setTextColor(Color.rgb(255,120,120)) })
            return
        }
        result.addView(tv(if (isYouTube) "YOUTUBE FORMAT" else "MODE UNDUHAN", 10f, true).apply {
            setTextColor(muted); setPadding(0, dp(6), 0, dp(6))
        })
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val videoMode = btn("Video", true)
        val audioMode = btn("Audio")
        // Tinggi mengikuti minHeight tombol (46dp) supaya teks
        // tidak pernah terpotong di bagian atas/bawah.
        modes.addView(videoMode, LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(audioMode, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        result.addView(modes)
        val mediaArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        result.addView(mediaArea)
        fun refreshMode(audio: Boolean) {
            val themeVeryLight = NaoThemeManager.current(this@MainActivity).veryLight
            videoMode.background = rounded(if (!audio) themeVeryLight else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(23,27,38)), 15, true)
            audioMode.background = rounded(if (audio) themeVeryLight else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(23,27,38)), 15, true)
            videoMode.setTextColor(if (!audio) bg else this@MainActivity.text)
            audioMode.setTextColor(if (audio) bg else this@MainActivity.text)
        }
        refreshMode(false)
        videoMode.setOnClickListener {
            refreshMode(false)
            renderDownloaderMedia(mediaArea, media, titleText, "video")
        }
        audioMode.setOnClickListener {
            refreshMode(true)
            renderDownloaderMedia(mediaArea, media, titleText, "audio")
        }
        renderDownloaderMedia(mediaArea, media, titleText, "video")
    }

    private fun expandMediaOptions(source: List<JSONObject>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        fun qualityOf(o: JSONObject): String = mediaQuality(o)
        fun add(o: JSONObject) {
            val type = o.optString("type").lowercase()
            val key = listOf(o.optString("id"), o.optString("url"), type, qualityOf(o)).joinToString("|")
            if (key != "|||" && seen.add(key)) out.add(o)
        }
        fun inspect(parent: JSONObject, depth: Int = 0) {
            if (depth > 3) return
            add(parent)
            val keys = arrayOf("formats","options","qualities","streams","videos","audios","media")
            for (key in keys) {
                val arr = parent.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    if (v is JSONObject) {
                        val child = JSONObject(parent.toString())
                        keys.forEach { child.remove(it) }
                        val it = v.keys()
                        while (it.hasNext()) { val k=it.next(); child.put(k,v.opt(k)) }
                        inspect(child, depth + 1)
                    }
                }
            }
        }
        source.forEach { inspect(it) }
        return out
    }

    private fun canonicalMediaUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val u = java.net.URI(raw.trim())
            val query = u.rawQuery
                ?.split("&")
                ?.filter { part ->
                    val k = part.substringBefore("=").lowercase()
                    k !in setOf("token", "sig", "signature", "expires", "expire", "timestamp", "ts", "utm_source", "utm_medium", "utm_campaign", "download")
                }
                ?.sorted()
                ?.joinToString("&")
            java.net.URI(u.scheme, u.userInfo, u.host, u.port, u.path, query, null).toString().lowercase()
        } catch (_: Exception) {
            raw.substringBefore("#").trim().lowercase()
        }
    }

    private fun dedupeMedia(source: List<JSONObject>): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (item in source) {
            val type = mediaType(item)
            val id = item.optString("id").trim()
            val url = canonicalMediaUrl(item.optString("url"))
            val thumb = canonicalMediaUrl(item.optString("thumbnail"))
            val w = item.optInt("width", 0)
            val h = item.optInt("height", 0)
            val duration = item.optLong("duration", 0L)
            val quality = mediaQuality(item).lowercase().trim()
            val key = when {
                url.isNotBlank() -> "$type|url|$url"
                id.isNotBlank() -> "$type|id|$id"
                thumb.isNotBlank() -> "$type|thumb|$thumb|$w|$h|$duration"
                else -> "$type|$quality|$w|$h|$duration|${item.optString("title").trim().lowercase()}"
            }
            if (seen.add(key)) out.add(item)
        }
        return out
    }

    private fun mediaQuality(o: JSONObject): String {
        val direct = arrayOf("quality", "resolution", "label", "qualityLabel", "videoQuality", "formatLabel")
            .firstNotNullOfOrNull { key ->
                val value = o.opt(key)
                when (value) {
                    is JSONObject -> {
                        val h = value.optInt("height", 0)
                        val w = value.optInt("width", 0)
                        when {
                            h > 0 -> "${h}p"
                            w > 0 -> "${w}w"
                            else -> value.optString("label").takeIf { it.isNotBlank() }
                        }
                    }
                    null -> null
                    else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
                }
            }
        if (direct != null) return direct
        val height = o.optInt("height", 0)
        val width = o.optInt("width", 0)
        if (height > 0) return "${height}p"
        if (width > 0 && height == 0) return "${width}w"
        val video = o.optJSONObject("video")
        if (video != null) return mediaQuality(video)
        return ""
    }

    private fun mediaType(o: JSONObject): String {
        val t = o.optString("type").lowercase()
        if (t.isNotBlank()) return t
        val mime = o.optString("mime").lowercase()
        if (mime.startsWith("image/")) return "image"
        if (mime.startsWith("video/")) return "video"
        if (mime.startsWith("audio/")) return "audio"
        val url = o.optString("url").lowercase()
        return when {
            url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || url.contains(".webp") -> "image"
            url.contains(".mp3") || url.contains(".m4a") || url.contains(".aac") -> "audio"
            url.contains(".mp4") || url.contains(".webm") || url.contains(".mov") -> "video"
            else -> "media"
        }
    }

    /** Format audio item hasil pencarian (mp3/m4a/ogg/wav), dipakai untuk chip
     * pilihan format saat opsi audio muncul di Downloader. */
    private fun audioFormat(o: JSONObject): String {
        val direct = arrayOf("format", "ext", "extension", "audioFormat")
            .firstNotNullOfOrNull { key -> o.optString(key).takeIf { it.isNotBlank() && it != "null" } }
        if (!direct.isNullOrBlank()) return direct.lowercase().removePrefix(".")
        val mime = o.optString("mime").lowercase()
        return when {
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("mp4a") || mime.contains("aac") -> "m4a"
            mime.contains("ogg") || mime.contains("opus") || mime.contains("vorbis") -> "ogg"
            mime.contains("wav") -> "wav"
            else -> {
                val url = o.optString("url").lowercase()
                when {
                    url.contains(".mp3") -> "mp3"
                    url.contains(".m4a") || url.contains(".aac") -> "m4a"
                    url.contains(".ogg") || url.contains(".opus") -> "ogg"
                    url.contains(".wav") -> "wav"
                    else -> ""
                }
            }
        }
    }

    private fun renderDownloaderCards(result: ViewGroup, media: List<JSONObject>, title: String, selectedQuality: String? = null, selectedAudioFormat: String? = null, selectedTargetFormat: String? = null, mode: String = "all") {
        result.removeAllViews()
        if (media.isEmpty()) {
            result.addView(tv("Format yang dipilih tidak tersedia.", 14f).apply { setTextColor(Color.rgb(255,180,100)); setPadding(0, dp(12), 0, 0) })
            return
        }
        // mode "video" -> hanya tampilkan kartu gambar/video/lainnya.
        // mode "audio" -> hanya tampilkan kartu audio (chip sumber, chip format,
        // kartu preview+download). "all" (default lama) tampilkan semuanya
        // tercampur seperti sebelumnya, dipakai jalur fallback direct-media.
        val showVideoSection = mode != "audio"
        val showAudioSection = mode != "video"

        // Chip "RESOLUSI/QUALITY" cuma perlu mewakili section yang lagi
        // ditampilkan (video/gambar), bukan seluruh media mentah — kalau
        // dihitung dari semua item, label kualitas milik item audio (mis.
        // literally "audio") ikut nongol sebagai pilihan resolusi video,
        // padahal unduh audio sudah punya tombol sendiri di "MODE UNDUHAN".
        val qualityPool = if (showVideoSection && !showAudioSection) media.filter { mediaType(it) != "audio" } else media
        val qualityValues = qualityPool.map { mediaQuality(it) }.filter { it.isNotBlank() }.distinct()
        val effectiveQuality = selectedQuality ?: qualityValues.firstOrNull()
        val shown = if (!effectiveQuality.isNullOrBlank() && qualityValues.size > 1) {
            qualityPool.filter { mediaQuality(it) == effectiveQuality }
        } else qualityPool

        if (qualityValues.size > 1 && showVideoSection) {
            result.addView(tv("RESOLUSI / QUALITY", 10f, true).apply {
                setTextColor(muted); setPadding(0, dp(12), 0, dp(7))
            })
            // Chip pilihan (mis. "HD", "SD", "No Watermark") memakai flow
            // layout: lebar mengikuti panjang label dan otomatis turun ke
            // baris berikutnya, jadi tidak ada teks yang terpotong.
            val row = NaoFlowLayout(this, dp(8))
            qualityValues.forEachIndexed { index, quality ->
                val q = btn(quality, if (selectedQuality.isNullOrBlank()) index == 0 else selectedQuality == quality)
                q.setPadding(dp(14), dp(2), dp(14), dp(2))
                q.setSingleLine(true)
                q.minimumHeight = dp(46)
                q.setOnClickListener { renderDownloaderCards(result, media, title, quality, selectedAudioFormat, selectedTargetFormat, mode) }
                row.addView(q)
            }
            result.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })
        }

        if (shown.isEmpty()) {
            result.addView(tv("Tidak ada media pada resolusi tersebut.", 13f).apply { setTextColor(Color.rgb(255,180,100)); setPadding(0, dp(12), 0, 0) })
            return
        }

        val images = shown.filter { mediaType(it) == "image" }
        val videos = shown.filter { mediaType(it) == "video" }
        val audios = shown.filter { mediaType(it) == "audio" }
        val other = shown.filter { mediaType(it) !in setOf("image", "video", "audio") }

        // FOTO: slider hanya digunakan untuk foto. Video tidak pernah dipaksa masuk slider.
        if (images.isNotEmpty() && showVideoSection) {
            val imageBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(12, 16, 24)), 22, true)
                setPadding(dp(12), dp(12), dp(12), dp(14))
            }
            val frame = FrameLayout(this).apply {
                background = rounded(Color.BLACK, 18, false)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(-1, dp(340))
            }
            val previewHost = FrameLayout(this)
            frame.addView(previewHost, FrameLayout.LayoutParams(-1, -1))
            val left = navArrow("‹")
            val right = navArrow("›")
            frame.addView(left, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(10) })
            frame.addView(right, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(10) })
            imageBox.addView(frame)
            val counter = tv("", 11f, true).apply { gravity = Gravity.CENTER; setTextColor(this@MainActivity.text); setPadding(0, dp(10), 0, dp(3)) }
            imageBox.addView(counter)
            val download = btn("Download foto", true)
            imageBox.addView(download, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(8), dp(10), dp(8), 0) })
            result.addView(imageBox)

            var index = 0
            fun showImage(i: Int) {
                index = i.coerceIn(0, images.lastIndex)
                val item = images[index]
                previewHost.removeAllViews()
                val url = item.optString("url")
                if (url.isNotBlank()) previewHost.addView(remoteImage(url, 330) { w, h ->
                    applyAspectHeight(frame, w, h)
                }.apply {
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(Color.BLACK, 18, false)
                })
                counter.text = NaoLang.t("${index + 1} / ${images.size}")
                download.text = NaoLang.t("Download foto")
                download.setOnClickListener { downloadItem(item, title, download, result) }
                left.visibility = if (images.size > 1 && index > 0) View.VISIBLE else View.INVISIBLE
                right.visibility = if (images.size > 1 && index < images.lastIndex) View.VISIBLE else View.INVISIBLE
            }
            left.setOnClickListener { showImage(index - 1) }
            right.setOnClickListener { showImage(index + 1) }
            showImage(0)
        }

        // VIDEO: setiap video mendapat preview sendiri agar video selalu terlihat.
        if (showVideoSection) videos.forEachIndexed { index, item ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(12, 16, 24)), 22, true)
                setPadding(dp(12), dp(12), dp(12), dp(14))
            }
            box.addView(tv(if (videos.size > 1) "VIDEO ${index + 1}" else "VIDEO", 10f, true).apply {
                setTextColor(muted); letterSpacing = .12f; setPadding(0, 0, 0, dp(8))
            })
            val preview = previewVideo(item.optString("url"), 330)
            box.addView(preview)
            val download = btn("Download video", true)
            box.addView(download, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(8), dp(10), dp(8), 0) })
            download.setOnClickListener { downloadItem(item, title, download, result) }
            result.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) })
        }

        // AUDIO: kalau API kebetulan mengembalikan lebih dari satu sumber
        // audio mentah (mis. mp3 & m4a asli dari sumbernya), tampilkan chip
        // untuk memilih sumbernya dulu.
        val audioFormats = audios.map { audioFormat(it) }.filter { it.isNotBlank() }.distinct()
        val effectiveAudioFormat = selectedAudioFormat ?: audioFormats.firstOrNull()
        val shownAudios = if (!effectiveAudioFormat.isNullOrBlank() && audioFormats.size > 1) {
            audios.filter { audioFormat(it) == effectiveAudioFormat }
        } else audios

        if (audioFormats.size > 1 && showAudioSection) {
            result.addView(tv("SUMBER AUDIO", 10f, true).apply {
                setTextColor(muted); setPadding(0, dp(4), 0, dp(7))
            })
            val formatRow = NaoFlowLayout(this, dp(8))
            audioFormats.forEachIndexed { index, format ->
                val fb = btn(format.uppercase(), if (selectedAudioFormat.isNullOrBlank()) index == 0 else selectedAudioFormat == format)
                fb.setPadding(dp(14), dp(2), dp(14), dp(2))
                fb.setSingleLine(true)
                fb.minimumHeight = dp(46)
                fb.setOnClickListener { renderDownloaderCards(result, media, title, selectedQuality, format, selectedTargetFormat, mode) }
                formatRow.addView(fb)
            }
            result.addView(formatRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })
        }

        // FORMAT AUDIO: sama seperti Video To Audio — pengguna selalu bisa
        // memilih MP3/M4A/WAV/OGG sebagai format keluaran, tidak bergantung
        // pada apa yang dikembalikan API. Kalau formatnya beda dari sumber
        // aslinya, hasil dikonversi lewat server saat diunduh.
        //
        // Banyak provider (TikTok/Instagram/Facebook) sama sekali tidak
        // mengembalikan item bertipe "audio" — cuma "video". Supaya opsi
        // format audio tetap selalu ada, kalau tidak ada item audio sungguhan
        // audionya diekstrak dari video pertama yang tersedia.
        val extractedFromVideo = audios.isEmpty() && videos.isNotEmpty()
        val audioCardItems: List<JSONObject> = if (shownAudios.isNotEmpty()) shownAudios else videos.take(1)
        val effectiveTargetFormat = naoAudioFormats.firstOrNull { it.id == selectedTargetFormat } ?: naoAudioFormats[0]
        if (audioCardItems.isNotEmpty() && showAudioSection) {
            result.addView(tv("FORMAT AUDIO", 10f, true).apply {
                setTextColor(muted); setPadding(0, dp(10), 0, dp(7))
            })
            val targetRow = NaoFlowLayout(this, dp(8))
            naoAudioFormats.forEachIndexed { _, format ->
                val fb = btn(format.label, format.id == effectiveTargetFormat.id)
                fb.setPadding(dp(14), dp(2), dp(14), dp(2))
                fb.setSingleLine(true)
                fb.minimumHeight = dp(46)
                fb.setOnClickListener { renderDownloaderCards(result, media, title, selectedQuality, selectedAudioFormat, format.id, mode) }
                targetRow.addView(fb)
            }
            result.addView(targetRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })
            result.addView(tv(
                if (extractedFromVideo)
                    "Sumber ini tidak menyediakan trek audio terpisah — audio akan diekstrak dari video lalu dikonversi ke format di atas."
                else
                    "Diproses cepat di perangkat bila format sumbernya sudah cocok; selain itu dikonversi lewat server.",
                11f
            ).apply {
                setTextColor(muted); setPadding(0, dp(2), 0, dp(2))
            })
        }

        // Setiap audio dapat preview player (play/pause + seek) dan tombol
        // unduh sendiri, mengikuti pola video di atas.
        if (showAudioSection) audioCardItems.forEachIndexed { index, item ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(12, 16, 24)), 22, true)
                setPadding(dp(12), dp(12), dp(12), dp(14))
            }
            val label = if (extractedFromVideo) "AUDIO (dari video)" else if (audioCardItems.size > 1) "AUDIO ${index + 1}" else "AUDIO"
            box.addView(tv(label, 10f, true).apply {
                setTextColor(muted); letterSpacing = .12f; setPadding(0, 0, 0, dp(8))
            })
            box.addView(previewAudio(item.optString("url")))
            val download = btn("Download audio (${effectiveTargetFormat.label})", true)
            box.addView(download, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(8), dp(10), dp(8), 0) })
            download.setOnClickListener { downloadAudioWithFormat(item, title, effectiveTargetFormat, download) }
            result.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) })
        }

        if (showVideoSection) other.forEach { item ->
            val b = btn("Download ${mediaType(item)}", true)
            b.setOnClickListener { downloadItem(item, title, b, result) }
            result.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(12), 0, 0) })
        }

        // Tombol "download semua" cuma relevan kalau video & audio masih
        // tercampur dalam satu layar (mode "all" / fallback direct-media).
        // Di tampilan video-saja atau audio-saja, satu kartu sudah cukup.
        if (shown.size > 1 && mode == "all") {
            result.addView(btn("Download semua media", false).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(10), 0, 0) }
                setOnClickListener {
                    val seen = HashSet<String>()
                    var queued = 0
                    shown.forEachIndexed { i, item ->
                        val key = item.optString("id").ifBlank { item.optString("url") }
                        if (key.isBlank() || !seen.add(key)) return@forEachIndexed
                        NaoDownloadCenter.enqueue(
                            this@MainActivity,
                            safeName(title) + "_${i + 1}",
                            mediaType(item),
                            item.optString("url"),
                            item.optString("id")
                        )
                        queued++
                    }
                    Toast.makeText(this@MainActivity, NaoLang.t("$queued unduhan dimulai · lihat tab Berkas"), Toast.LENGTH_SHORT).show()
                    berkas()
                }
            })
        }
    }

    private fun navArrow(label:String)=TextView(this).apply{ text=label;gravity=Gravity.CENTER;textSize=24f;setTextColor(this@MainActivity.text);background=rounded(NaoThemeManager.tintBoxArgb(this@MainActivity, 205,18,22,32),999,true);elevation=dp(4).toFloat();setPadding(0,0,0,dp(2)) }

    /**
     * Unduhan tidak lagi memblokir UI: pekerjaan dikirim ke pusat unduhan
     * (halaman Berkas) supaya bisa dipantau, dijeda, dilanjutkan atau dihapus.
     */
    private fun downloadItem(item: JSONObject, title: String, button: Button, result: ViewGroup) {
        val type = item.optString("type")
        NaoDownloadCenter.enqueue(
            this,
            safeName(title),
            type,
            item.optString("url"),
            item.optString("id")
        )
        button.text = NaoLang.t("Download ${type.ifBlank { "media" }}")
        Toast.makeText(this, NaoLang.t("Unduhan dimulai · pantau di tab Berkas"), Toast.LENGTH_SHORT).show()
        berkas()
    }

    /** Tebak ekstensi berkas sumber dari data API atau dari URL-nya, supaya
     * nama berkas yang diunggah ke server punya ekstensi yang benar (server
     * menentukan cara memprosesnya dari ekstensi, bukan dari isi berkas). */
    private fun guessSourceExtension(item: JSONObject, url: String): String {
        val direct = arrayOf("format", "ext", "extension", "audioFormat")
            .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() && it != "null" } }
        if (!direct.isNullOrBlank()) return "." + direct.removePrefix(".").lowercase()
        val lower = url.lowercase()
        listOf(".mp4", ".webm", ".mkv", ".mov", ".m4a", ".aac", ".mp3", ".ogg", ".opus", ".wav")
            .firstOrNull { lower.contains(it) }?.let { return it }
        // Kebanyakan sumber "audio" dari downloader sebenarnya berkas video
        // (mp4) yang cuma ditandai audio; ini default paling aman untuk
        // dikenali server.
        return ".mp4"
    }

    /**
     * Unduh media dari Downloader (video ATAU audio, apa pun yang
     * dikembalikan API) lalu ubah jadi audio format [target] — persis
     * konsep "Video To Audio": unduh dulu, deteksi trek audio dari ISI
     * berkas yang sungguh terunduh (pakai MediaExtractor, bukan tebakan dari
     * metadata API yang sering kosong), baru diproses cepat di perangkat
     * kalau formatnya sudah cocok atau dikonversi lewat server kalau beda.
     * User hanya menerima hasil audionya — berkas video sumbernya dihapus.
     */
    private fun downloadAudioWithFormat(item: JSONObject, title: String, target: NaoAudioFormat, button: Button) {
        val sourceUrl = item.optString("url")
        if (sourceUrl.isBlank()) {
            Toast.makeText(this, NaoLang.t("URL media tidak tersedia."), Toast.LENGTH_SHORT).show()
            return
        }
        val base = safeName(title)
        NaoDownloadCenter.enqueueLocal(this, base, "audio", base + target.ext) { job, out ->
            val srcExt = guessSourceExtension(item, sourceUrl)
            // Nama berkas HARUS punya ekstensi: kalau nanti perlu dikonversi
            // lewat server, server menebak jenis berkas dari ekstensi nama
            // file yang diunggah, bukan dari isinya.
            val temp = File(cacheDir, "nao_audio_src_${job.id}$srcExt")
            try {
                // Progres seluruh proses dipetakan ke skala 0-100 lewat
                // beberapa tahap (unduh sumber, lalu proses jadi audio),
                // supaya indikatornya bergerak terus dan TIDAK macet di satu
                // angka tetap selama tahap yang lama (mis. unggah+konversi
                // di server) berjalan — inilah penyebab dulu terlihat "macet
                // di 49/50%": progres sengaja dipatok ke bytes.size/2 lalu
                // tidak diperbarui lagi sampai semuanya selesai.
                job.report(0L, 100L)

                // 1) Unduh media aslinya — jalur yang sama seperti unduhan
                //    video biasa di Downloader (yang sudah terbukti jalan).
                //    Tahap ini memakai 0-30% dari total, dilaporkan real-time
                //    lewat callback onProgress (bukan angka tebakan).
                val bytes = downloader.getUrlBytes(sourceUrl) { done, total ->
                    if (total > 0) job.report((done * 30L / total), 100L)
                }
                job.report(30L, 100L)
                if (bytes.isEmpty()) throw Exception("Berkas sumber kosong.")
                // Deteksi dini kalau yang kembali ternyata halaman HTML/JSON
                // (mis. link sudah kedaluwarsa) supaya errornya jelas.
                val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1).trimStart()
                if (head.startsWith("<") || head.startsWith("{") || head.startsWith("[")) {
                    throw Exception("Server sumber mengembalikan respons tidak valid (link mungkin sudah kedaluwarsa).")
                }
                temp.writeBytes(bytes)

                // 2) Sama seperti "Video To Audio": cek trek audio yang
                //    SUNGGUH ada di berkas yang baru diunduh, bukan cuma
                //    tebakan dari metadata API.
                val src = Uri.fromFile(temp)
                val nativeExt = try { NaoAudioExtractor.outputExtension(this, src) } catch (_: Exception) { null }
                var handledLocally = false
                if (nativeExt != null && nativeExt.equals(target.ext, ignoreCase = true)) {
                    try {
                        // Jalur cepat: remux langsung di perangkat tanpa
                        // unggah, sama seperti Video To Audio. Sisa 30-100%
                        // dipetakan dari progres remux yang sesungguhnya.
                        NaoAudioExtractor.extract(this, src, out) { d, t ->
                            if (t > 0) job.report(30L + (d * 70L / t), 100L)
                        }
                        job.report(100L, 100L)
                        handledLocally = true
                    } catch (_: Exception) {}
                }
                if (!handledLocally) {
                    // Format target beda dari trek asli (atau jalur cepat
                    // gagal): konversi lewat server, sama seperti Video To
                    // Audio saat codec-nya tidak cocok. Sisa 30-100% dipakai
                    // untuk tahap unggah + unduh hasil konversi.
                    transcodeAudioLocally(temp, out, job, target.id, progressBase = 30, progressSpan = 70)
                }
            } finally {
                temp.delete()
            }
        }
        button.text = NaoLang.t("Mengunduh audio (${target.label})\u2026")
        Toast.makeText(this, NaoLang.t("Unduhan audio dimulai (${target.label}) \u00b7 pantau di tab Berkas"), Toast.LENGTH_SHORT).show()
        berkas()
    }

    private fun safeName(s: String) = s.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().ifEmpty { "media" }.take(100)
    private fun saveBytes(name: String, data: ByteArray, type: String) {
        val ext = when (type) { "audio" -> ".mp3"; "image" -> ".jpg"; else -> ".mp4" }
        val filename = if (com.nao.md.project.core.NaoDownloadCenter.hasMediaExtension(name)) name else name + ext
        val mime = when (ext.lowercase()) {
            ".mp3" -> "audio/mpeg"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".png" -> "image/png"
            ".webp" -> "image/webp"
            ".m4a" -> "audio/mp4"
            ".mp4" -> "video/mp4"
            ".webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        com.nao.md.project.core.NaoMediaStoreCompat.saveToDownloads(this, filename, mime) { it.write(data) }
    }

    /** Format audio yang bisa dipilih di Video To Audio & Downloader. */
    private data class NaoAudioFormat(val label: String, val id: String, val ext: String)
    private val naoAudioFormats = listOf(
        NaoAudioFormat("MP3", "mp3", ".mp3"),
        NaoAudioFormat("M4A", "m4a", ".m4a"),
        NaoAudioFormat("WAV", "wav", ".wav"),
        NaoAudioFormat("OGG", "ogg", ".ogg")
    )

    /** Empat mesin upscale foto yang didukung, lewat api-faa.my.id. */
    private data class NaoUpscaleEngine(val label: String, val id: String)
    private val naoUpscaleEngines = listOf(
        NaoUpscaleEngine("SuperHD", "superhd"),
        NaoUpscaleEngine("HD v2", "hdv2"),
        NaoUpscaleEngine("HD v3", "hdv3"),
        NaoUpscaleEngine("HD v4", "hdv4")
    )

    /** Halaman "Photo Upscale AI": khusus foto (bukan video). Pilih foto dari
     * galeri, pilih mesin AI, lalu proses lewat api-faa.my.id. */
    private fun photoUpscaleUi() {
        header("Photo Upscale AI", "Perbesar & pertajam resolusi foto pakai AI. Fitur ini hanya untuk foto, bukan video.")

        var pickedImage: File? = null
        var selectedEngine = 0
        var resultBytes: ByteArray? = null

        val inputCard = card()
        inputCard.addView(tv("FOTO INPUT", 10f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        val fileText = tv("Belum ada foto dipilih", 15f, true)
        inputCard.addView(fileText)
        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(230)).apply { setMargins(0, dp(14), 0, dp(10)) }
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(5, 7, 11)), 18)
            visibility = View.GONE
        }
        val previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        preview.addView(previewImage)
        inputCard.addView(preview)
        val choose = btn("＋  Pilih foto", true)
        choose.setOnClickListener {
            pick("image/*") { u ->
                try {
                    val f = readPickedImage(u)
                    pickedImage = f
                    resultBytes = null
                    fileText.text = f.name
                    val bmp = decodeSampledBitmap(f, 900)
                    if (bmp != null) {
                        previewImage.setImageBitmap(bmp)
                        preview.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    fileText.text = NaoLang.t("Gagal membuka foto: ${e.message}")
                    preview.visibility = View.GONE
                }
            }
        }
        inputCard.addView(choose)
        content.addView(inputCard)

        val engineCard = card()
        engineCard.addView(tv("METODE AI", 10f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        val engineRow = NaoFlowLayout(this, dp(8))
        fun renderEngineRow() {
            engineRow.removeAllViews()
            naoUpscaleEngines.forEachIndexed { index, engine ->
                val b = btn(engine.label, index == selectedEngine)
                b.setPadding(dp(14), dp(2), dp(14), dp(2))
                b.setSingleLine(true)
                b.minimumHeight = dp(46)
                b.setOnClickListener { selectedEngine = index; renderEngineRow() }
                engineRow.addView(b)
            }
        }
        renderEngineRow()
        engineCard.addView(engineRow, LinearLayout.LayoutParams(-1, -2))
        engineCard.addView(tv("SuperHD & HD v2 cepat untuk pemakaian umum. HD v3 & HD v4 cocok untuk detail wajah/foto lama.", 11f).apply {
            setTextColor(muted); setPadding(0, dp(8), 0, dp(2))
        })
        content.addView(engineCard)

        val status = tv("", 13f).apply { setTextColor(muted) }

        val resultCard = card()
        resultCard.visibility = View.GONE
        resultCard.addView(tv("HASIL UPSCALE", 10f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        val resultPreview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(230)).apply { setMargins(0, 0, 0, dp(10)) }
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(5, 7, 11)), 18)
        }
        val resultImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        resultPreview.addView(resultImage)
        resultCard.addView(resultPreview)
        val saveResult = btn("⬇  Simpan ke Download/NAO", true)
        saveResult.setOnClickListener {
            val bytes = resultBytes
            if (bytes == null) {
                Toast.makeText(this, NaoLang.t("Belum ada hasil untuk disimpan."), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = safeName("nao_upscale_${System.currentTimeMillis()}.jpg")
            try {
                saveBytes(name, bytes, "image")
                NaoDownloadCenter.restore(this)
                Toast.makeText(this, NaoLang.t("Tersimpan di Download/NAO: $name"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, NaoLang.t("Gagal menyimpan hasil upscale: ${e.message}"), Toast.LENGTH_LONG).show()
            }
        }
        resultCard.addView(saveResult)
        content.addView(resultCard)

        val process = btn("Upscale Foto", true)
        process.setOnClickListener {
            val f = pickedImage
            if (f == null) {
                status.text = NaoLang.t("Pilih foto dulu.")
                return@setOnClickListener
            }
            if (!beginProcessing("Mengunggah & memproses foto…")) return@setOnClickListener
            process.isEnabled = false
            process.text = NaoLang.t("Memproses…")
            resultCard.visibility = View.GONE
            status.text = NaoLang.t("Mengunggah & memproses foto…")
            exec.execute {
                try {
                    val hostedUrl = uploadImageForUpscale(f)
                    val engine = naoUpscaleEngines[selectedEngine]
                    val bytes = requestPhotoUpscale(engine.id, hostedUrl)
                    if (bytes.isEmpty()) throw Exception("Server tidak mengembalikan gambar.")
                    resultBytes = bytes
                    runOnUiThread {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) resultImage.setImageBitmap(bmp)
                        resultCard.visibility = View.VISIBLE
                        status.text = NaoLang.t("Selesai · hasil siap disimpan (${bytes.size / 1024} KB).")
                        process.isEnabled = true
                        process.text = NaoLang.t("Upscale Foto")
                        endProcessing()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = NaoLang.t("Upscale gagal: ${e.message}")
                        process.isEnabled = true
                        process.text = NaoLang.t("Upscale Foto")
                        endProcessing()
                    }
                }
            }
        }
        content.addView(process)
        content.addView(status.apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
    }

    /** Menyalin foto yang dipilih pengguna (content:// URI) ke berkas cache lokal. */
    private fun readPickedImage(uri: Uri): File {
        var displayName = "photo_${System.currentTimeMillis()}.jpg"
        val mimeType = contentResolver.getType(uri)
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                if (name.isNotBlank()) displayName = name
            }
        }
        if (!displayName.contains('.')) {
            val ext = when (mimeType?.lowercase()) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }
            displayName += ext
        }
        val safe = safeName(displayName).ifEmpty { "photo_${System.currentTimeMillis()}.jpg" }
        val f = File(cacheDir, "nao_upscale_src_$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: throw Exception("Tidak dapat membaca file foto.")
        return f
    }

    /** Decode foto dengan inSampleSize supaya preview tidak membebani memori. */
    private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Unggah sementara ke catbox.moe: API upscale butuh URL sumber, bukan
     * berkas langsung, jadi foto lokal perlu dipublikasikan dulu. */
    private fun uploadImageForUpscale(file: File): String {
        val boundary = "----NAOUP${java.util.UUID.randomUUID()}"
        val c = (URL("https://catbox.moe/user/api.php").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 90000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "NAO-MD-Android/2.0")
        }
        c.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n".toByteArray())
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = input.bufferedReader().use { it.readText() }.trim()
        c.disconnect()
        if (code !in 200..299 || !text.startsWith("http")) throw Exception("Gagal mengunggah foto sementara ke host penyimpanan.")
        return text
    }

    private data class NaoHttpResult(val code: Int, val contentType: String?, val bytes: ByteArray)

    private fun naoHttpGet(urlStr: String): NaoHttpResult {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 90000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NAO-MD-Android/2.0")
        }
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val data = ByteArrayOutputStream()
        input.use { it.copyTo(data) }
        val type = c.contentType
        c.disconnect()
        return NaoHttpResult(code, type, data.toByteArray())
    }

    /** superhd & hdv2 mengembalikan gambar langsung; jika server malah
     * membalas JSON (biasanya error), pesan error itu yang dilempar. */
    private fun fetchUpscaleImageOrJson(urlStr: String): ByteArray {
        val res = naoHttpGet(urlStr)
        if (res.code !in 200..299) {
            val msg = try { JSONObject(res.bytes.toString(Charsets.UTF_8)).optString("message", "HTTP ${res.code}") } catch (_: Exception) { "HTTP ${res.code}" }
            throw Exception(msg)
        }
        if (res.contentType.orEmpty().contains("json")) {
            val obj = JSONObject(res.bytes.toString(Charsets.UTF_8))
            throw Exception(obj.optString("message", "Server menolak permintaan upscale."))
        }
        return res.bytes
    }

    /** Panggil salah satu dari 4 endpoint api-faa.my.id sesuai mesin yang dipilih. */
    private fun requestPhotoUpscale(engineId: String, sourceUrl: String): ByteArray {
        val encoded = java.net.URLEncoder.encode(sourceUrl, "UTF-8")
        return when (engineId) {
            "superhd" -> fetchUpscaleImageOrJson("https://api-faa.my.id/faa/superhd?url=$encoded")
            "hdv2" -> fetchUpscaleImageOrJson("https://api-faa.my.id/faa/hdv2?url=$encoded")
            "hdv3" -> {
                val res = naoHttpGet("https://api-faa.my.id/faa/hdv3?image=$encoded")
                val obj = JSONObject(res.bytes.toString(Charsets.UTF_8))
                if (!obj.optBoolean("status", false)) throw Exception(obj.optString("message", "Proses HD v3 gagal."))
                val resultUrl = obj.optString("result")
                if (resultUrl.isBlank()) throw Exception("HD v3 tidak mengembalikan hasil.")
                naoHttpGet(resultUrl).bytes
            }
            "hdv4" -> {
                val res = naoHttpGet("https://api-faa.my.id/faa/hdv4?image=$encoded")
                val obj = JSONObject(res.bytes.toString(Charsets.UTF_8))
                if (!obj.optBoolean("status", false)) throw Exception(obj.optString("message", "Proses HD v4 gagal."))
                val resultUrl = obj.optJSONObject("result")?.optString("image_upscaled").orEmpty()
                if (resultUrl.isBlank()) throw Exception("HD v4 tidak mengembalikan hasil.")
                naoHttpGet(resultUrl).bytes
            }
            else -> throw Exception("Metode upscale tidak dikenal.")
        }
    }

    /**
     * Konversi audio ke [format]. Semua format (WAV/M4A/OGG lewat
     * [NaoAudioTranscoder], MP3 lewat [com.nao.md.project.core.NaoMp3Encoder])
     * diproses 100% di perangkat lewat LAME (JNI) -- tidak lagi bergantung
     * API pihak ketiga (ApyHub) yang dulu dipakai NaoMp3RemoteEncoder.
     *
     * [progressBase]/[progressSpan] memetakan progres ke sebagian dari skala
     * 0-100 milik [job] (dipakai kalau ada tahap lain sebelumnya, mis. unduh
     * sumber di Downloader). Defaultnya 0..100 kalau fungsi ini satu-satunya
     * tahap (dipanggil dari Video To Audio).
     */
    private fun transcodeAudioLocally(
        source: File,
        target: File,
        job: NaoDownloadCenter.Job,
        format: String = "mp3",
        progressBase: Int = 0,
        progressSpan: Int = 100
    ) {
        job.report(progressBase.toLong(), 100L)
        if (format == "mp3") {
            com.nao.md.project.core.NaoMp3Encoder.encode(source, target) { done, total ->
                if (total > 0) {
                    job.report((progressBase + (done * progressSpan / total)).coerceIn(0L, 100L), 100L)
                }
            }
            job.report((progressBase + progressSpan).toLong(), 100L)
            return
        }
        NaoAudioTranscoder.transcode(this, source, target, format) { done, total ->
            if (total > 0) {
                job.report((progressBase + (done * progressSpan / total)).coerceIn(0L, 100L), 100L)
            }
        }
        job.report((progressBase + progressSpan).toLong(), 100L)
    }


    private fun videoAudioUi() {
        header("Video To Audio", "Preview video terlebih dahulu, pilih format, lalu ubah menjadi audio.")
        val box = card()
        box.addView(tv("VIDEO INPUT", 10f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        val fileText = tv("Belum ada video dipilih", 15f, true)
        box.addView(fileText)
        val preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(230)).apply { setMargins(0, dp(14), 0, dp(10)) }
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(5,7,11)), 18)
            clipChildren = false
            visibility = View.GONE
        }
        box.addView(preview)
        val choose = btn("＋  Pilih video", true)
        choose.setOnClickListener {
            pick("video/*") { u ->
                try {
                    pickedFile = uriFile(u)
                    fileText.text = pickedFile!!.name
                    preview.removeAllViews()
                    val player = previewVideo(Uri.fromFile(pickedFile).toString(), 230, onAspect = { w, h ->
                        applyAspectHeight(preview, w, h)
                    }).apply {
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                        setPadding(0, 0, 0, 0)
                    }
                    preview.addView(player)
                    preview.visibility = View.VISIBLE
                } catch (e: Exception) {
                    fileText.text = NaoLang.t("Gagal membuka video: ${e.message}")
                    preview.visibility = View.GONE
                }
            }
        }
        box.addView(choose)
        box.addView(tv("Preview digunakan untuk memastikan video yang dipilih sudah benar sebelum diproses.", 11f).apply {
            setTextColor(muted); setPadding(0, dp(8), 0, dp(4))
        })
        content.addView(box)

        val formatCard = card()
        formatCard.addView(tv("FORMAT AUDIO", 10f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        var selectedFormat = 0
        val formatRow = NaoFlowLayout(this, dp(8))
        fun renderFormatRow() {
            formatRow.removeAllViews()
            naoAudioFormats.forEachIndexed { index, format ->
                val b = btn(format.label, index == selectedFormat)
                b.setPadding(dp(14), dp(2), dp(14), dp(2))
                b.setSingleLine(true)
                b.minimumHeight = dp(46)
                b.setOnClickListener { selectedFormat = index; renderFormatRow() }
                formatRow.addView(b)
            }
        }
        renderFormatRow()
        formatCard.addView(formatRow, LinearLayout.LayoutParams(-1, -2))
        formatCard.addView(tv("MP3/M4A/OGG dari trek asli diproses cepat di perangkat bila codec-nya cocok; selain itu diproses lewat server.", 11f).apply {
            setTextColor(muted); setPadding(0, dp(8), 0, dp(2))
        })
        content.addView(formatCard)

        val actionCard = card()
        val out = tv("", 14f)
        actionCard.addView(out)
        lateinit var process: Button
        process = workButton("Extract audio") {
            val f = pickedFile
            if (f == null) {
                out.text = NaoLang.t("Pilih video dulu.")
                done(process, "Extract audio")
                return@workButton
            }
            val target = naoAudioFormats[selectedFormat]
            out.text = NaoLang.t("Mengekstrak audio (${target.label})\u2026 progres muncul di indikator unduhan.")
            done(process, "Extract audio")
            exec.execute {
                val src = Uri.fromFile(f)
                val nativeExt = NaoAudioExtractor.outputExtension(this, src)
                val base = safeName(f.name.substringBeforeLast('.', f.name))
                runOnUiThread {
                    NaoDownloadCenter.enqueueLocal(this, base, "audio", base + target.ext) { job, out2 ->
                        var handledLocally = false
                        if (nativeExt.equals(target.ext, ignoreCase = true)) {
                            try {
                                // Jalur cepat: ekstraksi langsung di perangkat
                                // (tanpa unggah) bila codec trek asli sudah cocok
                                // dengan format yang dipilih, selesai dalam hitungan detik.
                                NaoAudioExtractor.extract(this, src, out2) { d, t -> job.report(d, t) }
                                handledLocally = true
                            } catch (_: Exception) {}
                        }
                        if (!handledLocally) {
                            // Format yang diminta beda dari codec asli (atau
                            // jalur cepat gagal): decode+encode di perangkat
                            // lewat NaoAudioTranscoder, tanpa server.
                            transcodeAudioLocally(f, out2, job, target.id)
                        }
                    }
                    Toast.makeText(this, NaoLang.t("Ekstraksi audio dimulai"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        actionCard.addView(process)
        content.addView(actionCard)
    }

    private fun music() {
        musicSubPage = false
        setNavSelected("Music")
        clear()
        title("NAO MUSIC", "Music", "Listen, discover, repeat")
        attachNaoMusicShell()
    }

    private fun attachNaoMusicShell() {
        val pages = NaoMusicPages(
            activity = this,
            innerTube = innerTube,
            store = musicStore,
            exec = exec,
            callbacks = object : NaoMusicPages.Callbacks {
                override fun play(track: InnerTubeClient.Track, source: List<InnerTubeClient.Track>, index: Int, shuffleRest: Boolean) {
                    queueAndPlay(track, source, index, shuffleRest)
                }
                override fun enqueue(track: InnerTubeClient.Track) {
                    enqueueTrack(track)
                }
                override fun addToPlaylist(track: InnerTubeClient.Track) {
                    showAddToPlaylist(track)
                }
                override fun download(track: InnerTubeClient.Track) {
                    downloadCurrentTrack(track)
                }
                override fun loadArtwork(view: ImageView, track: InnerTubeClient.Track) {
                    loadMusicArtworkHd(view, track)
                }
                override fun applyThumb(view: ImageView, radiusDp: Int) {
                    applyMusicThumbnailShape(view, radiusDp)
                }
                override fun showSettings() {
                    showNaoMusicMenu()
                }
                override fun playLocalPlaylist(name: String) {
                    playPlaylist(name)
                }
                override fun queueTracks(): List<InnerTubeClient.Track> = musicQueue.toList()
            }
        )
        naoMusicPages = pages
        pages.attach(content)
    }

    private fun video() {
        musicSubPage = false
        setNavSelected("Video")
        clear()
        title("NAO VIDEO", "Video", "Watch, discover, binge")
        attachNaoVideoShell()
    }

    private fun attachNaoVideoShell() {
        val pages = NaoVideoPages(
            activity = this,
            api = newPipeVideoApi,
            exec = exec,
            callbacks = object : NaoVideoPages.Callbacks {
                override fun loadThumb(view: ImageView, url: String, videoId: String) {
                    NaoArtworkLoader.load(view, videoId, url, big = false)
                }
                override fun applyThumb(view: ImageView, radiusDp: Int) {
                    applyMusicThumbnailShape(view, radiusDp)
                }
                override fun showSettings() {
                    showVideoSettingsDialog()
                }
                override fun openInBrowser(videoId: String) {
                    openVideoInBrowser(videoId)
                }
            }
        )
        naoVideoPages?.release()
        naoVideoPages = pages
        pages.attach(content)
    }

    /** Buka video YouTube di browser (dipakai bila stream gagal diputar). */
    private fun openVideoInBrowser(videoId: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
        } catch (e: Exception) {
            Toast.makeText(this, NaoLang.t("Tidak ada browser tersedia."), Toast.LENGTH_SHORT).show()
        }
    }

/** Dialog pengaturan video: NewPipe langsung ke YouTube, tidak perlu instance. */
    private fun showVideoSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(NaoLang.t("Pengaturan Video"))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(6))
        }
        lateinit var dialog: AlertDialog

        panel.addView(tv("Backend: NewPipeExtractor (langsung ke YouTube)", 11f).apply {
            setTextColor(muted)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })

        panel.addView(tv("Tidak memerlukan instance Invidious. Ekstraksi stream\nmenggunakan NewPipeExtractor seperti aplikasi NewPipe resmi.", 10f).apply {
            setTextColor(muted)
            setPadding(0, dp(8), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(btn("Bersihkan Cache").apply {
            setOnClickListener {
                // Clear NewPipe cache if needed
                Toast.makeText(this@MainActivity,
                    NaoLang.t("Cache dibersihkan."), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(btn("Reset").apply {
            setOnClickListener {
                Toast.makeText(this@MainActivity,
                    NaoLang.t("Direset ke default."), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        panel.addView(row)

        dialog = builder.setView(panel)
            .setNegativeButton(NaoLang.t("Tutup"), null)
            .create()
        dialog.show()
    }
    private fun runMusicSearch(query: String, resultBox: LinearLayout, status: TextView) {
        val q = query.trim()
        if (q.isBlank()) return
        // Overlay loading yang sama dengan Downloader: mencegah tap beruntun
        // (spam search / tap lagu) selagi hasil belum kembali.
        if (!beginProcessing("Mencari lagu…")) return
        status.text = NaoLang.t("Finding music…")
        saveMusicSearch(q)
        exec.execute {
            try {
                val found = innerTube.search(q)
                runOnUiThread {
                    musicResults = found
                    resultBox.removeAllViews()
                    renderMusicSearchResults(resultBox, status, found)
                    status.text = NaoLang.t("${found.size} results")
                    endProcessing()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = NaoLang.t("Search error: ${e.message ?: "request failed"}")
                    endProcessing()
                }
            }
        }
    }

    private fun renderMusicSearchResults(resultBox: LinearLayout, status: TextView, items: List<InnerTubeClient.Track>) {
        if (items.isEmpty()) {
            resultBox.addView(tv("No results", 14f).apply {
                setTextColor(muted); setPadding(dp(4), dp(18), dp(4), dp(24))
            })
            return
        }
        resultBox.addView(tv("Songs", 19f, true).apply { setPadding(dp(3), dp(12), 0, dp(8)) })
        items.forEachIndexed { index, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(NaoThemeManager.current(this@MainActivity).mid, 22, true))
                    addState(intArrayOf(), rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14,18,27)), 22, true))
                }
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(.985f).scaleY(.985f).alpha(.82f).setDuration(70L).start()
                    } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110L).start()
                    }
                    false
                }
                setOnClickListener { queueAndPlay(track, items, index, shuffleRest = true) }
            }
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.nao_music_artwork)
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(31,34,43)), 18, false)
            }
            loadMusicArtworkHd(art, track)
            row.addView(art, LinearLayout.LayoutParams(dp(68), dp(68)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,dp(6),0) }
            meta.addView(tv(track.title, 14f, true).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
            meta.addView(tv(track.artist.ifBlank{"YouTube Music"} + if(track.album.isNotBlank()) " • ${track.album}" else "", 11f).apply {
                setTextColor(muted); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(4),0,0)
            })
            row.addView(meta, LinearLayout.LayoutParams(0,-2,1f))
            row.addView(tv("⋮",22f).apply {
                gravity=Gravity.CENTER; setTextColor(muted)
                setOnClickListener { enqueueTrack(track) }
            }, LinearLayout.LayoutParams(dp(40),dp(64)))
            resultBox.addView(row, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(8)) })
        }
    }

    private fun musicDialogCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 28, false)
        }
    }

    private fun showNaoMusicMenu() {
        val dialog = Dialog(this)
        val card = musicDialogCard("Nao Music")
        card.addView(tv("Nao Music", 22f, true).apply { setPadding(0, 0, 0, dp(12)) })

        fun menuItem(title: String, subtitle: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 20, false)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { action(); dialog.dismiss() }
            }
            item.addView(tv(title, 14f, true))
            item.addView(tv(subtitle, 11f).apply {
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }

        menuItem("Pengaturan", "Nao Music Lab dan playback", {
            showNaoMusicSettingsPanel()
        })
        menuItem("Antrian", "Lihat antrian lagu", {
            musicQueuePage()
        })
        menuItem("Tentang Nao Music", "Modern native music experience", {
            Toast.makeText(this, NaoLang.t("Nao Music · modern native music experience"), Toast.LENGTH_SHORT).show()
        })

        val close = Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, false)
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(close, LinearLayout.LayoutParams(-1, dp(46)))
        dialog.setContentView(NaoUiCompat.scrollable(card))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showNaoMusicSettingsPanel() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Pengaturan")
        panel.addView(tv("Pengaturan", 23f, true).apply { setPadding(0, 0, 0, dp(3)) })
        panel.addView(tv("Nao Music Lab", 11f).apply {
            setTextColor(muted)
            setPadding(0, 0, 0, dp(14))
        })

        fun setting(title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 20, false)
                setPadding(dp(15), dp(12), dp(12), dp(12))
                setOnClickListener { action(); dialog.dismiss(); showNaoMusicSettingsPanel() }
            }
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            meta.addView(tv(title, 14f, true))
            meta.addView(tv(subtitle, 11f).apply {
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))

            val switch = TextView(this).apply {
                text = if (enabled) "ON" else "OFF"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (enabled) Color.WHITE else muted)
                background = rounded(if (enabled) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(48, 53, 67)), 14, false)
                minWidth = dp(48)
                minHeight = dp(32)
            }
            row.addView(switch, LinearLayout.LayoutParams(dp(52), dp(36)))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) })
        }

        setting("♫ Audio Only",
            if (musicAudioOnly) "Hanya audio · hemat data & baterai" else "Preview video diaktifkan",
            musicAudioOnly) {
                musicAudioOnly = !musicAudioOnly
            }
        setting("◷ Sleep Timer",
            if (musicSleepTimer != null) "Timer aktif" else "Atur waktu berhenti otomatis",
            musicSleepTimer != null) {
                showMusicSleepTimer()
            }
        setting("Android",
            "MediaSession · lock screen · headset & system controls",
            true) {
                Toast.makeText(this, NaoLang.t("Android media controls aktif."), Toast.LENGTH_SHORT).show()
            }

        val close = Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, false)
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(46)))

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showMusicSleepTimer() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Sleep Timer")
        panel.addView(tv("Sleep Timer", 22f, true))
        panel.addView(tv("Pilih kapan Nao Music berhenti otomatis.", 11f).apply {
            setTextColor(muted); setPadding(0, dp(5), 0, dp(12))
        })
        fun option(label: String, minutes: Long?) {
            panel.addView(Button(this).apply {
                text = label
                setTextColor(this@MainActivity.text)
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24,29,40)), 18, true)
                setOnClickListener {
                    musicSleepTimer?.cancel()
                    if (minutes == null) {
                        musicSleepTimer = null
                        Toast.makeText(this@MainActivity, NaoLang.t("Sleep Timer off"), Toast.LENGTH_SHORT).show()
                    } else {
                        musicSleepTimer = object : CountDownTimer(minutes * 60_000L, 1_000L) {
                            override fun onTick(millisUntilFinished: Long) {}
                            override fun onFinish() {
                                musicPlayer?.pause()
                                musicSleepTimer = null
                                updateMusicMiniPlay()
                                Toast.makeText(this@MainActivity, NaoLang.t("Sleep Timer selesai"), Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                        Toast.makeText(this@MainActivity, NaoLang.t("Sleep Timer: $minutes menit"), Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0,0,0,dp(8)) })
        }
        option("Off", null)
        option("15 menit", 15L)
        option("30 menit", 30L)
        option("60 menit", 60L)
        panel.addView(Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24,29,40)), 18, true)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun saveMusicSearch(query: String) {
        val old = prefs.getStringSet("music_search_history", emptySet()).orEmpty().toMutableSet()
        old.remove(query)
        val ordered = ArrayList<String>()
        ordered.add(query)
        ordered.addAll(old)
        prefs.edit().putStringSet("music_search_history", ordered.take(8).toSet()).apply()
    }

    private fun musicSearchHistory(): List<String> =
        prefs.getStringSet("music_search_history", emptySet()).orEmpty().toList()

    private fun renderMusicRecommendations(target: LinearLayout, status: TextView? = null) {
        if (musicRecommendationBusy || musicRecommendationsLoaded) return
        musicRecommendationBusy = true
        target.removeAllViews()

        val history = musicSearchHistory()
        val seeds = ArrayList<String>()
        if (history.isNotEmpty()) seeds.addAll(history.take(2))
        if (seeds.isEmpty()) {
            seeds.add("popular songs")
            seeds.add("new music")
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        header.addView(tv("Made for you", 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tv(if (history.isEmpty()) "Fresh picks" else "Based on your listening", 10f).apply { setTextColor(muted) })
        target.addView(header)

        exec.execute {
            val collected = ArrayList<InnerTubeClient.Track>()
            seeds.forEach { seed ->
                try {
                    collected.addAll(innerTube.search(seed).take(8))
                } catch (_: Exception) {}
            }
            val unique = collected.distinctBy { it.videoId }.take(16)
            runOnUiThread {
                if (unique.isEmpty()) {
                    target.addView(tv("Search for an artist or song to build your recommendations.", 13f).apply {
                        setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(20))
                    })
                } else {
                    addMusicRecommendationSection(target, if (history.isEmpty()) "Popular for you" else "Because you listened", unique.take(8))
                    if (unique.size > 8) addMusicRecommendationSection(target, "More like this", unique.drop(8).take(8))
                }
                musicRecommendationsLoaded = true
                musicRecommendationBusy = false
                status?.text = if (unique.isEmpty()) "Search YouTube Music to start listening." else "Personalized recommendations"
            }
        }
    }

    private fun addMusicRecommendationSection(target: LinearLayout, heading: String, items: List<InnerTubeClient.Track>) {
        target.addView(tv(heading, 17f, true).apply { setPadding(dp(2), dp(12), 0, dp(8)) })
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { track ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14,18,27)), 18, true)
                setOnClickListener { queueAndPlay(track, items, items.indexOf(track)) }
            }
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.nao_music_artwork)
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(28,32,45)), 14, false)
                applyMusicThumbnailShape(this, 14)
            }
            loadMusicArtworkHd(art, track)
            card.addView(art, LinearLayout.LayoutParams(dp(150), dp(150)))
            card.addView(tv(track.title, 13f, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(8), dp(2), 0)
            }, LinearLayout.LayoutParams(dp(150), -2))
            card.addView(tv(track.artist.ifBlank { "YouTube Music" }, 11f).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(muted); setPadding(dp(2), dp(3), dp(2), dp(2))
            }, LinearLayout.LayoutParams(dp(150), -2))
            row.addView(card, LinearLayout.LayoutParams(dp(166), -2).apply { setMargins(0, 0, dp(8), 0) })
        }
        horizontal.addView(row)
        target.addView(horizontal, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun queueAndPlay(track: InnerTubeClient.Track, source: List<InnerTubeClient.Track>, index: Int, shuffleRest: Boolean = false) {
        musicPlaylistOnlyMode = false
        // Lagu yang belum ada di antrian akan MENGGANTI antrian lama, bukan
        // menambahkannya. Kalau lagu memang berasal dari antrian, sisa antrian
        // dipertahankan dan hanya lagu tersebut yang dikeluarkan.
        val fromQueue = musicQueue.any { it.videoId == track.videoId }
        if (fromQueue) {
            musicQueue.removeAll { it.videoId == track.videoId }
        } else {
            musicQueue.clear()
            musicQueueOrder = ArrayList()
            musicCyclingRepeatQueue = false
        }
        // shuffleRest = true (dipakai dari hasil pencarian): antrean TIDAK
        // diisi seluruh hasil pencarian. Cukup putar lagu yang ditekan; lagu
        // berikutnya disambung otomatis oleh Autoplay Pintar (radio lagu
        // serupa), sehingga antrian tidak penuh judul yang sama semua.
        val candidates = if (shuffleRest) {
            emptyList()
        } else {
            source.drop(index + 1)
        }
        val existing = musicQueue.map { it.videoId }.toHashSet()
        candidates.forEach { if (it.videoId != track.videoId && existing.add(it.videoId)) musicQueue.add(it) }
        if (!fromQueue) musicQueueOrder = ArrayList(musicQueue)
        playRemote(track)
        renderExpandedQueue()
    }

    private fun enqueueTrack(track: InnerTubeClient.Track, playNow: Boolean = false) {
        musicPlaylistOnlyMode = false
        if (playNow) {
            if (musicQueue.none { it.videoId == track.videoId }) {
                musicQueue.clear()
                musicQueueOrder = ArrayList()
                musicCyclingRepeatQueue = false
            } else {
                musicQueue.removeAll { it.videoId == track.videoId }
            }
            playRemote(track)
            renderExpandedQueue()
            return
        }
        if (musicQueue.none { it.videoId == track.videoId }) musicQueue.add(track)
        saveMusicSessionState()
        renderExpandedQueue()
        Toast.makeText(this, NaoLang.t("Ditambahkan ke antrian"), Toast.LENGTH_SHORT).show()
    }

    private fun handleMusicEnded() {
        val player = musicPlayer ?: return
        when (musicRepeatMode) {
            2 -> {
                player.seekTo(0L)
                player.play()
                updateMusicProgressNow()
                return
            }
            1 -> {
                if (musicQueue.isEmpty() && musicRepeatPool.isNotEmpty()) {
                    // Ulangi seluruh sesi (playlist / radio / antrean manual).
                    val cycle = ArrayList(musicRepeatPool)
                    val current = musicNow
                    val ordered = if (musicShuffleEnabled) cycle.shuffled() else cycle
                    val nextCycle = if (ordered.size > 1 && current != null) {
                        ordered.filter { it.videoId != current.videoId }
                    } else ordered
                    if (nextCycle.isNotEmpty()) {
                        musicQueue.addAll(nextCycle)
                        musicQueueOrder = ArrayList(cycle)
                        musicCyclingRepeatQueue = true
                    }
                }
            }
        }
        // playNextQueuedTrack menangani semua kasus: antrean masih ada, mode
        // ulang, Autoplay Pintar saat antrean habis, atau berhenti total.
        playNextQueuedTrack()
    }

    private fun playNextQueuedTrack() {
        if (musicQueue.isEmpty() && musicPlaylistOnlyMode && musicRepeatMode != 1) {
            // Playlist selesai: berhenti, tanpa lagu tambahan apa pun.
            musicPlaylistOnlyMode = false
            try { musicPlayer?.pause() } catch (_: Exception) {}
            updateMusicMiniPlay()
            renderExpandedQueue()
            Toast.makeText(this, NaoLang.t("Playlist selesai."), Toast.LENGTH_SHORT).show()
            NaoMediaLoadingGuard.finish()
            return
        }
        if (musicQueue.isEmpty()) {
            // Antrean habis: ulangi sesi bila mode ulang aktif, lalu coba
            // Autoplay Pintar (radio berbasis lagu terakhir, difilter supaya
            // tidak lompat bahasa/skrip), baru berhenti bila keduanya tidak berlaku.
            if (musicRepeatMode == 1 && musicRepeatPool.isNotEmpty()) {
                val cycle = ArrayList(musicRepeatPool)
                musicQueue.addAll(if (musicShuffleEnabled) cycle.shuffled() else cycle)
                musicQueueOrder = ArrayList(cycle)
            } else {
                // Autoplay Pintar selalu aktif: sambung otomatis dengan lagu
                // mirip (radio InnerTube), difilter supaya tidak lompat
                // bahasa/skrip tulisan secara tiba-tiba. Kalau benar-benar
                // tidak ada kandidat sama sekali, triggerSmartAutoplay yang
                // akan menghentikan pemutaran dengan pesan yang sesuai.
                triggerSmartAutoplay()
                return
            }
        }
        // Antrean sudah dalam urutan final (diacak saat mode acak dinyalakan),
        // jadi selalu ambil lagu paling depan agar tidak ada acak ganda.
        val next = musicQueue.removeAt(0)
        saveMusicSessionState()
        playRemote(next)
        renderExpandedQueue()
    }

    /**
     * Autoplay Pintar: ketika antrean habis, ambil rekomendasi "radio" dari
     * InnerTube berbasis lagu terakhir, lalu saring supaya sambungannya halus
     * — tidak tiba-tiba lompat ke bahasa/skrip tulisan yang berbeda jauh dari
     * lagu-lagu yang baru saja didengar (mis. dari pop Indonesia ke lagu India,
     * Thailand, dll). Dijalankan di background thread karena butuh network call.
     */
    private fun triggerSmartAutoplay() {
        val seed = musicNow ?: musicPreviousStack.lastOrNull()
        if (seed == null) {
            try { musicPlayer?.pause() } catch (_: Exception) {}
            updateMusicMiniPlay()
            renderExpandedQueue()
            NaoMediaLoadingGuard.finish()
            return
        }
        exec.execute {
            val recentContext = (musicPreviousStack.takeLast(8) + seed)
            val excluded = HashSet<String>()
            excluded.addAll(musicRepeatPool.map { it.videoId })
            excluded.addAll(musicPreviousStack.map { it.videoId })
            excluded.add(seed.videoId)
            val picked = try {
                smartAutoplayCandidates(seed, recentContext, excluded)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (picked.isEmpty()) {
                    try { musicPlayer?.pause() } catch (_: Exception) {}
                    updateMusicMiniPlay()
                    renderExpandedQueue()
                    Toast.makeText(this, NaoLang.t("Autoplay pintar tidak menemukan lagu lanjutan."), Toast.LENGTH_SHORT).show()
                    NaoMediaLoadingGuard.finish()
                    return@runOnUiThread
                }
                musicQueue.addAll(picked)
                musicQueueOrder = ArrayList(musicQueue)
                val next = musicQueue.removeAt(0)
                saveMusicSessionState()
                playRemote(next)
                renderExpandedQueue()
            }
        }
    }

    /** Ambil kandidat radio, lalu saring berdasarkan kecocokan skrip tulisan
     * dengan konteks lagu yang baru didengar. Kalau setelah difilter tidak
     * ada kandidat tersisa (jarang, biasanya lagu seed sangat unik), pakai
     * daftar mentah apa adanya supaya autoplay tetap tidak berujung/berhenti. */
    private fun smartAutoplayCandidates(
        seed: InnerTubeClient.Track,
        context: List<InnerTubeClient.Track>,
        excluded: Set<String>
    ): List<InnerTubeClient.Track> {
        val raw = try { innerTube.nextRadio(seed.videoId) } catch (_: Exception) { emptyList() }
            .filter { it.videoId !in excluded }
        val majorityScript = dominantScript(context)
        val filtered = if (majorityScript == null) raw else raw.filter { candidate ->
            val s = scriptOf(candidate)
            s == majorityScript || s == "OTHER"
        }
        var result = filtered.ifEmpty { raw }
        // Fallback 1: radio InnerTube kosong (jarang, tapi bisa terjadi untuk
        // video tertentu) — cari lagu lain dari artis yang sama.
        if (result.isEmpty() && seed.artist.isNotBlank()) {
            result = try {
                innerTube.search(seed.artist).filter { it.videoId !in excluded && it.videoId != seed.videoId }
            } catch (_: Exception) { emptyList() }
        }
        // Fallback 2: masih kosong — cari berdasarkan judul lagu supaya
        // autoplay tetap tersambung alih-alih berhenti total.
        if (result.isEmpty()) {
            result = try {
                innerTube.search(seed.title).filter { it.videoId !in excluded && it.videoId != seed.videoId }
            } catch (_: Exception) { emptyList() }
        }
        return result.take(15)
    }

    private fun scriptOf(track: InnerTubeClient.Track): String =
        dominantScriptOfText("${track.title} ${track.artist}")

    /** Skrip tulisan yang paling sering muncul di antara beberapa lagu, dipakai
     * sebagai "rasa" playlist saat ini. Null kalau tidak cukup data. */
    private fun dominantScript(tracks: List<InnerTubeClient.Track>): String? {
        val counts = HashMap<String, Int>()
        tracks.forEach { t ->
            val s = scriptOf(t)
            if (s != "OTHER") counts[s] = (counts[s] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Deteksi skrip tulisan dominan dalam sebuah teks (judul + artis) dengan
     * menghitung rentang kode Unicode-nya. Sengaja sederhana (tanpa library
     * tambahan) — cukup untuk membedakan Latin dari skrip yang jauh berbeda
     * seperti Devanagari (India), Thai, Hangul (Korea), Han (China), dst. */
    private fun dominantScriptOfText(text: String): String {
        var latin = 0; var devanagari = 0; var thai = 0; var hangul = 0
        var han = 0; var kana = 0; var arabic = 0; var cyrillic = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0x0041..0x024F -> latin++
                cp in 0x0900..0x097F -> devanagari++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 0xAC00..0xD7A3 -> hangul++
                cp in 0x4E00..0x9FFF -> han++
                cp in 0x3040..0x30FF -> kana++
                cp in 0x0600..0x06FF -> arabic++
                cp in 0x0400..0x04FF -> cyrillic++
            }
        }
        val counts = linkedMapOf(
            "LATIN" to latin, "DEVANAGARI" to devanagari, "THAI" to thai,
            "HANGUL" to hangul, "HAN" to han, "KANA" to kana,
            "ARABIC" to arabic, "CYRILLIC" to cyrillic
        )
        val best = counts.maxByOrNull { it.value }
        return if (best == null || best.value == 0) "OTHER" else best.key
    }

    private fun renderMusicQueue(target: LinearLayout) {
        target.removeAllViews()
        if (musicQueue.isEmpty()) {
            target.addView(tv("Antrian kosong. Tambahkan lagu dari Pencarian atau Rekomendasi.", 13f).apply {
                setTextColor(muted); setPadding(0, dp(18), 0, dp(18))
            })
            return
        }
        musicQueue.forEachIndexed { index, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(9))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 20, true)
            }
            row.addView(tv("${index + 1}", 11f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(muted)
            }, LinearLayout.LayoutParams(dp(34), dp(46)))
            row.addView(tv("♪", 20f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(accentOnCard)
            }, LinearLayout.LayoutParams(dp(34), dp(46)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            meta.addView(tv(track.title, 13f, true))
            meta.addView(tv(track.artist.ifBlank { "YouTube Music" }, 10f).apply { setTextColor(muted) })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("×", 20f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(muted)
                setOnClickListener { musicQueue.removeAt(index); renderMusicQueue(target); renderExpandedQueue() }
            }, LinearLayout.LayoutParams(dp(38), dp(46)))
            target.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        }
    }
    private fun renderLocalMusic(target: LinearLayout? = null) {
        val out = target ?: content
        if (target == null) return
        out.removeAllViews()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        val musicPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(musicPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(musicPermission), 7101)
            out.addView(tv("Allow music access to show songs stored on this phone.",13f).apply { setTextColor(muted) }); return
        }
        try {
            contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                var count=0
                while(c.moveToNext() && count<100) {
                    val id=c.getLong(0); val title=c.getString(1).orEmpty(); val artist=c.getString(2).orEmpty(); val duration=c.getLong(3)
                    val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(9),dp(8),dp(9)); background=rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14,18,27)),16,true) }
                    row.addView(tv("♫",24f,true).apply { setTextColor(accentOnCard); gravity=Gravity.CENTER; layoutParams=LinearLayout.LayoutParams(dp(48),dp(48)) })
                    val m=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(8),0)}
                    m.addView(tv(title,14f,true)); m.addView(tv("$artist • ${duration/60000}:${(duration/1000%60).toString().padStart(2,'0')}",11f).apply{setTextColor(muted)})
                    val play=btn("▶",true); play.setOnClickListener{
                        connectMusicController { controller ->
                            controller.setMediaItem(MediaItem.Builder().setUri(ContentUris.withAppendedId(uri,id)).setMediaMetadata(
                                MediaMetadata.Builder().setTitle(title).setArtist(artist.ifBlank { "Nao Music" }).build()
                            ).build())
                            controller.prepare(); controller.play()
                        }
                    }
                    row.addView(m,LinearLayout.LayoutParams(0,-2,1f));row.addView(play);out.addView(row,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(8))});count++
                }
                if(count==0) out.addView(tv("No local music found.",13f).apply{setTextColor(muted)})
            }
        } catch(e:SecurityException){ out.addView(tv("Music permission is required.",13f).apply{setTextColor(muted)}) }
    }

    /**
     * Called when the app is reopened after being closed/backgrounded while
     * a track was playing. The NaoMusicPlaybackService keeps ExoPlayer alive
     * (and playing) in the background, so if it is still holding the same
     * track we must NOT re-resolve the stream and reload it — that used to
     * force a fresh network fetch every reopen, which either seeked back to
     * the stale saved position (sounded like a restart) or, if the resolve
     * raced with the track nearing its end, skipped to the next song while
     * loading. Instead we just reattach the UI to the still-running
     * controller. If the service is no longer alive (closed via the
     * notification's X button, force-stopped, or killed by the system) the
     * saved "now playing" state is stale — we clear it and hide the mini
     * player instead of showing a card that can't actually be played.
     */
    private fun resumeMusicOnReopen(track: InnerTubeClient.Track) {
        connectMusicController { controller ->
            val item = controller.currentMediaItem
            val stillAlive = item != null &&
                item.mediaId == track.videoId &&
                controller.playbackState != Player.STATE_IDLE &&
                controller.playbackState != Player.STATE_ENDED
            if (stillAlive) {
                musicNow = track
                musicPlaybackStarted = true
                musicSessionRestored = true
                musicRestorePosition = 0L
                musicSwitchLock.set(false)
                showMusicMini(track)
                musicSheetTitle?.text = track.title
                musicSheetArtist?.text = track.artist.ifBlank { "YouTube Music" }
                updateMusicMiniPlay()
                startMusicProgressLoop()
                startMusicKeepAlive()
            } else {
                clearStaleMusicSession()
            }
        }
    }

    /**
     * Membersihkan sisa state "sedang memutar" yang tersimpan waktu app
     * ditutup, dipakai saat ternyata service musiknya sudah tidak berjalan
     * lagi (bukan cuma di-pause). Menyembunyikan mini player sepenuhnya,
     * bukan membiarkannya tampil tapi tidak berfungsi.
     */
    private fun clearStaleMusicSession() {
        musicNow = null
        musicQueue.clear()
        musicQueueOrder = ArrayList()
        musicPreviousStack.clear()
        musicRepeatPool.clear()
        musicCyclingRepeatQueue = false
        musicRestorePosition = 0L
        musicRestoreWasPlaying = false
        musicPlaybackStarted = false
        musicMiniPlayer?.visibility = View.GONE
        renderExpandedQueue()
        saveMusicSessionState()
    }

    private fun playRemote(track:InnerTubeClient.Track, addToHistory: Boolean = true){
        // Serialize track switches. Search rows, queue rows and transport buttons
        // can all call this method; a double tap must not create two resolvers.
        if (!musicSwitchLock.compareAndSet(false, true)) return
        // Notifikasi/panel media (Android 14/15) ikut menampilkan status
        // memuat untuk switch ini. Aman dipanggil ulang: kalau tap ini
        // berasal dari notifikasi, statusnya sudah diklaim duluan di sana
        // sebelum broadcast dikirim ke sini — begin() jadi no-op.
        NaoMediaLoadingGuard.begin()
        // Kunci UI selama stream di-resolve supaya tidak ada tap lagu beruntun.
        if (!beginProcessing("Menyiapkan lagu…")) {
            musicSwitchLock.set(false)
            NaoMediaLoadingGuard.finish()
            return
        }
        // Watchdog: overlay tidak boleh nyangkut jika resolve stream menggantung.
        root.postDelayed({
            if (musicSwitchLock.compareAndSet(true, false)) {
                endProcessing()
                NaoMediaLoadingGuard.finish()
                Toast.makeText(this, NaoLang.t("Lagu terlalu lama dimuat. Coba lagi."), Toast.LENGTH_SHORT).show()
            }
        }, 30_000L)

        val oldTrack = musicNow
        if (addToHistory && oldTrack != null && oldTrack.videoId != track.videoId) {
            musicPreviousStack.removeAll { it.videoId == oldTrack.videoId }
            musicPreviousStack.add(oldTrack)
            if (musicPreviousStack.size > 20) musicPreviousStack.removeAt(0)
        }
        musicPlaybackGeneration += 1L
        val requestGeneration = musicPlaybackGeneration
        if (musicRepeatPool.none { it.videoId == track.videoId }) {
            musicRepeatPool.add(track)
        }
        // Keep the previous title/audio in sync while the new stream resolves.
        // The UI is switched only after a playable stream has been prepared.
        // Runs on its own dedicated thread (musicResolveExec) instead of the
        // shared background pool, so it never has to wait behind lyrics,
        // video-preview, search, download or upload jobs queued elsewhere.
        musicResolveExec.execute {
            try {
                // Jalur utama: layanan ytmusicapi (stream akun, tanpa iklan). NewPipe
                // tetap dipakai sebagai cadangan bila layanan tidak bisa dihubungi.
                val fastStreams = NaoMusicBackend.resolveAudio(track.videoId)
                val audioCandidates = fastStreams
                    .filter { it.mimeType.startsWith("audio/") && it.url.isNotBlank() }
                    .map { it.url }
                if (audioCandidates.isEmpty()) error("Layanan ytmusicapi tidak mengembalikan audio")
                startResolvedMusic(track, audioCandidates, requestGeneration)
            } catch (ytError: Throwable) {
                // Reliable fallback: NewPipeExtractor. This path is only used
                // hanya dipakai bila layanan ytmusicapi tidak bisa dihubungi.
                // Throwable (not Exception) on purpose: on old Android versions
                // the extractor can raise a LinkageError/NoSuchMethodError for a
                // missing java.* API, which must not kill the worker thread.
                try {
                    val extracted = YouTubeStreamResolver.resolve(track.videoId)
                    val audioCandidates = extracted.filter { it.url.isNotBlank() }.map { it.url }
                    if (audioCandidates.isEmpty()) throw ytError
                    startResolvedMusic(track, audioCandidates, requestGeneration)
                } catch (e: Throwable) {
                    runOnUiThread {
                        musicSwitchLock.set(false)
                        endProcessing()
                        NaoMediaLoadingGuard.finish()
                        musicMiniTitle?.text = NaoLang.t("Playback unavailable")
                        musicMiniArtist?.text = e.message ?: ytError.message ?: "Pemutaran gagal"
                        updateMusicMiniPlay()
                        Toast.makeText(this, NaoLang.t("Nao Music: stream tidak tersedia. Coba lagu lain."),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    private fun installMusicPlayerSheet() {
        // Sheet bisa ditutup dengan gestur: jari ditarik ke bawah (konten ikut
        // turun) tanpa harus menekan tombol \u2304 di pojok kiri atas.
        musicPlayerSheet = object : FrameLayout(this) {
            private var startY = 0f
            private var startX = 0f
            private var dragging = false
            private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

            private fun atTop(): Boolean = (musicSheetMiniLyricScroll?.scrollY ?: 0) <= 0

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = ev.rawY; startX = ev.rawX; dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - startY
                        val dx = kotlin.math.abs(ev.rawX - startX)
                        if (!dragging && dy > slop * 1.6f && dy > dx * 1.4f && atTop()) dragging = true
                    }
                }
                return dragging
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = ev.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            if (ev.rawY - startY > slop * 1.6f && atTop()) dragging = true else return true
                        }
                        val dy = (ev.rawY - startY).coerceAtLeast(0f)
                        translationY = dy
                        alpha = (1f - dy / (height.coerceAtLeast(1) * 1.6f)).coerceIn(0.4f, 1f)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = translationY
                        dragging = false
                        if (dy > height * 0.20f) {
                            animate().translationY(height.toFloat()).alpha(0f).setDuration(170L)
                                .withEndAction {
                                    translationY = 0f
                                    alpha = 1f
                                    hideMusicPlayerSheet(animate = false)
                                }.start()
                        } else {
                            animate().translationY(0f).alpha(1f).setDuration(150L).start()
                        }
                        return true
                    }
                }
                return true
            }
        }.apply {
            visibility = View.GONE
            // Layar Now Playing juga ikut gradasi tema, bukan hitam polos,
            // supaya konsisten dengan latar utama.
            background = pageBackground()
            elevation = dp(30).toFloat()
            isClickable = true
        }

        val sheet = musicPlayerSheet!!
        // Sheet dipecah jadi 3 blok vertikal (header tetap - tengah bisa
        // scroll - footer tetap) supaya kontrol playback & tombol Antrian
        // SELALU utuh kelihatan, tidak pernah kepotong atau ketutupan
        // navigation/gesture bar walau font/ukuran tampilan HP diperbesar.
        // Yang boleh discroll cuma bagian artwork/lirik di tengah; footer
        // kontrol dikunci di luar area scroll itu.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            outer.setPadding(dp(20), dp(2) + bars.top, dp(20), dp(12) + bars.bottom)
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = tv("⌄", 26f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false)
            setOnClickListener { hideMusicPlayerSheet() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(2), dp(4), 0, dp(4)) })
        top.addView(tv("NOW PLAYING", 10f, true).apply {
            letterSpacing = .18f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(tv("⋮", 22f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false)
            setOnClickListener { showMusicTrackMenu() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), dp(2), dp(4)) })
        outer.addView(top)

        val media = FrameLayout(this).apply {
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(25, 29, 40)), 32, false)
            clipChildren = true
            elevation = dp(16).toFloat()
        }
        musicSheetArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.nao_music_artwork)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(32).toFloat())
                }
            }
        }
        media.addView(musicSheetArtwork, FrameLayout.LayoutParams(-1, -1))
        musicSheetVideo = VideoView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                visibility = View.VISIBLE
                musicSheetArtwork?.visibility = View.GONE
            }
            setOnErrorListener { _, _, _ ->
                visibility = View.GONE
                musicSheetArtwork?.visibility = View.VISIBLE
                true
            }
        }
        media.addView(musicSheetVideo, FrameLayout.LayoutParams(-1, -1))
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)), 999, false)
        }
        fun playerTab(label: String, active: Boolean, action: () -> Unit): TextView = tv(label, 12f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else muted)
            background = rounded(if (active) purple else Color.TRANSPARENT, 999, false)
            setOnClickListener { action() }
        }
        musicTabPreview = playerTab("Preview", true) { setMusicLyricsVisible(false) }
        musicTabLyrics = playerTab("Informasi", false) { setMusicLyricsVisible(true) }
        tabs.addView(musicTabPreview, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(0, 0, dp(4), 0) })
        tabs.addView(musicTabLyrics, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(4), 0, 0, 0) })

        musicSheetLyricsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(30))
        }
        musicSheetLyricsStatus = tv("Pilih lagu untuk melihat lirik.", 13f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        musicSheetLyricsBox?.addView(musicSheetLyricsStatus)
        musicSheetLyricsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            alpha = 0f
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(16, 19, 28)), 28, false)
            addView(musicSheetLyricsBox)
        }

        musicSheetStage = FrameLayout(this).apply {
            addView(media, FrameLayout.LayoutParams(-1, -1))
            addView(musicSheetLyricsScroll, FrameLayout.LayoutParams(-1, -1))
        }

        // Bagian tengah (tab Preview/Lirik + artwork/lirik + judul + progress)
        // SENGAJA TIDAK dibungkus ScrollView lagi — supaya cuma panel lirik
        // (musicSheetMiniLyricScroll) yang bisa discroll, bukan seluruh area
        // ini. Footer kontrol tetap terpisah & terkunci di luar seperti
        // sebelumnya (lihat weight di bawah).
        val scrollBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollBody.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(12), dp(2), 0) })

        // Tinggi artwork menyesuaikan tinggi layar: di HP pendek atau saat
        // font/tampilan sistem diperbesar, artwork mengecil duluan supaya
        // judul lagu dan kontrol playback di footer tidak pernah terdesak
        // keluar layar.
        val stageHeight = (resources.displayMetrics.heightPixels * 0.30f).toInt().coerceIn(dp(180), dp(360))
        scrollBody.addView(musicSheetStage, LinearLayout.LayoutParams(-1, stageHeight).apply {
            setMargins(dp(6), dp(14), dp(6), dp(24))
        })

        musicSheetTitle = tv("Nothing playing", 22f, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        musicSheetArtist = tv("Nao Music", 14f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }
        scrollBody.addView(musicSheetTitle)
        scrollBody.addView(musicSheetArtist)

        musicSheetProgress = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) musicPlayer?.let { if (it.duration > 0) it.seekTo((it.duration * progress / 1000f).toLong()) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        musicSheetProgress?.progressDrawable = slimMusicSeekDrawable()
        musicSheetProgress?.thumb = slimMusicSeekThumb()
        musicSheetProgress?.setPadding(dp(5), 0, dp(5), 0)
        musicSheetProgress?.splitTrack = false

        // Waktu berjalan / durasi lagu, tepat DI ATAS garis progress.
        musicSheetElapsed = tv("0:00", 11f).apply { setTextColor(muted) }
        musicSheetDuration = tv("0:00", 11f).apply { setTextColor(muted) }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(musicSheetElapsed, LinearLayout.LayoutParams(-2, -2))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(musicSheetDuration, LinearLayout.LayoutParams(-2, -2))
        }
        scrollBody.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(2)) })
        scrollBody.addView(musicSheetProgress, LinearLayout.LayoutParams(-1, dp(18)).apply { setMargins(dp(11), 0, dp(11), dp(4)) })

        // Tombol on/off lirik, persis di bawah garis progress lagu.
        musicLyricsToggleOn = prefs.getBoolean(PREF_LYRICS_PANEL_ON, true)
        val lyricsToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        musicLyricsToggleBtn = tv("\ud83c\udfb5", 20f, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                musicLyricsToggleOn = !musicLyricsToggleOn
                prefs.edit().putBoolean(PREF_LYRICS_PANEL_ON, musicLyricsToggleOn).apply()
                updateMusicLyricsToggleUi()
                Toast.makeText(
                    this@MainActivity,
                    if (musicLyricsToggleOn) NaoLang.t("Lyrics on") else NaoLang.t("Lyrics off"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        lyricsToggleRow.addView(musicLyricsToggleBtn)
        scrollBody.addView(lyricsToggleRow, LinearLayout.LayoutParams(-1, -2))

        // Ruang yang dulu kosong di bawah progress bar sekarang menjadi
        // panel lirik langsung di sini, gaya YouTube Music: baris demi
        // baris, baris yang sedang berjalan disorot + otomatis discroll
        // ke tengah kalau lagunya punya lirik tersinkron. Lirik statis
        // tetap tampil penuh di sini juga (tanpa sorotan), bisa discroll
        // manual. weight = 1f supaya melebar mengisi sisa ruang kosong
        // sampai ke baris tombol play/pause. Ketuk untuk lompat ke tab
        // Informasi (detail lagu + kontrol geser sinkron).
        musicSheetMiniLyricBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        musicSheetMiniLyricBox?.addView(tv("Pilih lagu untuk melihat lirik.", 14f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
        })
        musicSheetMiniLyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(musicSheetMiniLyricBox)
            setOnClickListener { setMusicLyricsVisible(true) }
        }
        scrollBody.addView(musicSheetMiniLyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(scrollBody, LinearLayout.LayoutParams(-1, 0, 1f))
        updateMusicLyricsToggleUi()

        // Footer: kontrol playback + tombol Antrian. Sengaja DILUAR panel
        // lirik di atas, dan bukan bagian dari area yang bisa scroll, supaya
        // dua-duanya selalu utuh terlihat dan tidak pernah ketutupan
        // navigation/gesture bar sistem.
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun control(icon: Int, action: () -> Unit, size: Int = 48): ImageView {
            val view = modernMusicIcon(icon, 22, action = action)
            controls.addView(view, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                setMargins(dp(8), 0, dp(8), 0)
            })
            return view
        }
        musicSheetShuffle = control(R.drawable.nao_ic_shuffle, {
            toggleMusicShuffle()
        })
        control(R.drawable.nao_ic_prev, { playPreviousMusic() })
        musicSheetPlay = modernMusicIcon(R.drawable.nao_ic_play, 26, primary = true) {
            musicPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            updateMusicMiniPlay()
            saveMusicSessionState()
        }
        controls.addView(musicSheetPlay, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            setMargins(dp(10), 0, dp(10), 0)
        })
        control(R.drawable.nao_ic_next, { playNextQueuedTrack() })
        musicSheetRepeat = control(R.drawable.nao_ic_repeat, {
            cycleMusicRepeatMode()
        })
        footer.addView(controls, LinearLayout.LayoutParams(-1, dp(80)).apply { setMargins(0, dp(6), 0, 0) })

        // Antrian dijadikan tombol yang membuka halaman terpisah (musicQueuePage),
        // bukan daftar inline di dalam sheet ini. Daftar inline dulu bikin full
        // player kepanjangan saat antrian banyak, sampai kontennya menutupi
        // navigation/gesture bar sistem.
        val queueCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(16, 19, 28)), 26, false)
            setPadding(dp(16), dp(14), dp(14), dp(14))
            setOnClickListener {
                hideMusicPlayerSheet(animate = false)
                musicQueuePage()
            }
        }
        queueCard.addView(tv("UP NEXT", 11f, true).apply { letterSpacing = .14f }, LinearLayout.LayoutParams(0, -2, 1f))
        queueCard.addView(tv("Antrian", 12f, true).apply { setTextColor(accentOnCard) })
        queueCard.addView(tv("\u203A", 20f, true).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(26), -2).apply { setMargins(dp(6), 0, 0, 0) })
        footer.addView(queueCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(16), 0, dp(4)) })

        outer.addView(footer)
        sheet.addView(outer, FrameLayout.LayoutParams(-1, -1))
        // PENTING: full player TIDAK boleh ditempel ke `root` (LinearLayout
        // vertikal). Di sana tingginya cuma sisa ruang setelah body, mini
        // player, dan bottom bar, sehingga footer kontrol (acak, sebelumnya,
        // play/pause, selanjutnya, ulangi) kepotong / tidak kelihatan.
        // Dipasang sebagai overlay penuh di content frame Activity supaya
        // benar-benar full screen dan semua tombol selalu tampil.
        val contentFrame = findViewById<FrameLayout>(android.R.id.content)
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        contentFrame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        sheet.bringToFront()
    }

    private fun showMusicPlayerSheet() {
        if (musicNow == null) return
        val sheet = musicPlayerSheet ?: return
        val distance = (if (sheet.height > 0) sheet.height else resources.displayMetrics.heightPixels).toFloat()
        sheet.animate().cancel()
        sheet.translationY = distance
        sheet.alpha = 0f
        sheet.visibility = View.VISIBLE
        sheet.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
            .start()
        musicMiniPlayer?.visibility = View.GONE
        musicSheetTitle?.text = musicNow?.title ?: "Nothing playing"
        musicSheetArtist?.text = musicNow?.artist?.ifBlank { "YouTube Music" } ?: "YouTube Music"
        musicNow?.let { track ->
            musicSheetArtwork?.let { image -> loadMusicArtworkBig(image, track) }
        }
        updateMusicMiniPlay()
        renderExpandedQueue()
        loadMusicLyrics()
    }

    private fun hideMusicPlayerSheet(animate: Boolean = true) {
        val sheet = musicPlayerSheet ?: return
        fun finish() {
            sheet.animate().cancel()
            sheet.translationY = 0f
            sheet.alpha = 1f
            sheet.visibility = View.GONE
            if (musicNow != null) musicMiniPlayer?.visibility = View.VISIBLE
        }
        if (!animate || sheet.visibility != View.VISIBLE) {
            finish()
            return
        }
        val distance = (if (sheet.height > 0) sheet.height else resources.displayMetrics.heightPixels).toFloat()
        sheet.animate().cancel()
        sheet.animate()
            .translationY(distance)
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { finish() }
            .start()
    }

    private fun renderExpandedQueue() {
        val target = musicSheetQueue ?: return
        target.removeAllViews()
        if (musicQueue.isEmpty()) {
            target.addView(tv("Antrian sudah habis.", 12f).apply {
                setTextColor(muted); setPadding(0, dp(10), 0, dp(20))
            })
            return
        }
        musicQueue.forEachIndexed { index, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener {
                    musicQueue.remove(track)
                    playRemote(track)
                    renderExpandedQueue()
                }
            }
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.nao_music_artwork)
                applyMusicThumbnailShape(this, 14)
            }
            loadMusicArtworkHd(art, track)
            row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
            meta.addView(tv(track.title, 13f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            meta.addView(tv(track.artist.ifBlank { "YouTube Music" }, 11f).apply { setTextColor(muted) })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("☰", 17f).apply { setTextColor(muted) }, LinearLayout.LayoutParams(dp(34), dp(52)))
            target.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })
        }
    }

    private fun playPreviousMusic() {
        val current = musicNow
        val previous = musicPreviousStack.removeLastOrNull()
        if (previous == null) {
            musicPlayer?.seekTo(0L)
            musicPlayer?.play()
            updateMusicMiniPlay()
            NaoMediaLoadingGuard.finish()
            return
        }
        if (current != null && current.videoId != previous.videoId) {
            musicPreviousStack.add(current)
        }
        saveMusicSessionState()
        playRemote(previous, addToHistory = false)
        renderExpandedQueue()
    }

    private fun showMusicMini(track:InnerTubeClient.Track){
        musicMiniPlayer?.visibility=View.VISIBLE
        musicSheetTitle?.text=track.title
        musicSheetArtist?.text=track.artist.ifBlank{"YouTube Music"}
        musicMiniTitle?.text=track.title
        musicMiniArtist?.text=track.artist.ifBlank{"YouTube Music"}
        musicMiniPlay?.setImageResource(R.drawable.nao_ic_pause)
        musicMiniVideo?.stopPlayback()
        musicMiniVideo?.visibility=View.GONE
        musicCurrentArtwork?.visibility=View.VISIBLE
        musicCurrentArtwork?.setImageResource(R.drawable.nao_music_artwork)
        musicCurrentArtwork?.let { loadMusicArtworkHd(it, track) }
        musicSheetArtwork?.let { loadMusicArtworkBig(it, track) }
        // Video previews are experimental and can be disabled for battery/data saving.
        if (musicAudioOnly) return
        exec.execute {
            try {
                val video = try {
                    NaoMusicBackend.resolveVideo(track.videoId)
                } catch (_: Throwable) {
                    YouTubeStreamResolver.resolveVideo(track.videoId).map {
                        NaoMusicBackend.Stream(it.url, it.mimeType, 0, it.width, it.height)
                    }
                }
                val selected = video.firstOrNull()?.url.orEmpty()
                if (selected.isNotBlank()) {
                    runOnUiThread {
                        try {
                            musicMiniVideo?.setVideoURI(Uri.parse(selected))
                            musicSheetVideo?.setVideoURI(Uri.parse(selected))
                            musicMiniVideo?.start()
                            musicSheetVideo?.start()
                        } catch (_: Throwable) {
                            musicMiniVideo?.visibility = View.GONE
                            musicCurrentArtwork?.visibility = View.VISIBLE
                            musicSheetVideo?.visibility = View.GONE
                            musicSheetArtwork?.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
                // Static artwork remains the fallback for music-only items.
            }
        }
    }
    private fun updateMusicMiniPlay() {
        val playing = musicPlayer?.isPlaying == true
        musicNow?.let {
            NaoDiscordManager.updateForTrack(
                this,
                it.title,
                it.artist.ifBlank { "Nao Music" },
                "https://i.ytimg.com/vi/${it.videoId}/maxresdefault.jpg",
                !playing,
                musicPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                musicPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                it.album
            )
        }
        val playIcon = if (playing) R.drawable.nao_ic_pause else R.drawable.nao_ic_play
        musicMiniPlay?.setImageResource(playIcon)
        musicSheetPlay?.setImageResource(playIcon)
        musicRestoreWasPlaying = playing
        updateMusicModeControls()
    }

    /**
     * Acak antrean secara nyata: saat dinyalakan urutan antrean diacak sekali,
     * saat dimatikan urutan asli dipulihkan (lagu yang sudah lewat dibuang).
     */
    private fun toggleMusicShuffle() {
        musicShuffleEnabled = !musicShuffleEnabled
        if (musicShuffleEnabled) {
            if (musicQueueOrder.isEmpty()) musicQueueOrder = ArrayList(musicQueue)
            if (musicQueue.size > 1) musicQueue.shuffle()
        } else {
            val remaining = musicQueue.map { it.videoId }.toHashSet()
            val restored = musicQueueOrder.filter { remaining.contains(it.videoId) }
            if (restored.size == musicQueue.size && restored.isNotEmpty()) {
                musicQueue.clear()
                musicQueue.addAll(restored)
            }
        }
        Toast.makeText(
            this,
            if (musicShuffleEnabled) "Acak aktif" else "Acak nonaktif",
            Toast.LENGTH_SHORT
        ).show()
        updateMusicModeControls()
        renderExpandedQueue()
        saveMusicSessionState()
        NaoMusicPlaybackService.refreshCustomLayout()
    }

    private fun cycleMusicRepeatMode() {
        musicRepeatMode = (musicRepeatMode + 1) % 3
        Toast.makeText(
            this,
            when (musicRepeatMode) {
                1 -> "Ulangi semua lagu"
                2 -> "Ulangi lagu ini"
                else -> "Ulangi nonaktif"
            },
            Toast.LENGTH_SHORT
        ).show()
        updateMusicModeControls()
        saveMusicSessionState()
        NaoMusicPlaybackService.refreshCustomLayout()
    }

    private fun updateMusicModeControls() {
        musicSheetShuffle?.apply {
            setImageResource(R.drawable.nao_ic_shuffle)
            setColorFilter(
                if (musicShuffleEnabled) bg else this@MainActivity.text,
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            background = rounded(
                if (musicShuffleEnabled) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false
            )
        }
        musicSheetRepeat?.apply {
            setImageResource(
                if (musicRepeatMode == 2) R.drawable.nao_ic_repeat_one else R.drawable.nao_ic_repeat
            )
            setColorFilter(
                if (musicRepeatMode != 0) bg else this@MainActivity.text,
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            background = rounded(
                if (musicRepeatMode != 0) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false
            )
        }
        // Mini-player intentionally exposes previous / play-pause / next only.
        musicMiniPrevious?.isEnabled = musicNow != null
        musicMiniNext?.isEnabled = musicNow != null
    }

    private fun updateMusicProgressNow() {
        val player = musicPlayer ?: return
        if (!musicUiResumed) return
        val duration = player.duration
        val position = player.currentPosition
        if (duration > 0L) {
            val value = ((position.toDouble() / duration.toDouble()) * 1000.0)
                .toInt().coerceIn(0, 1000)
            musicMiniProgress?.max = 1000
            musicMiniProgress?.progress = value
            musicSheetProgress?.max = 1000
            musicSheetProgress?.progress = value
            musicSheetElapsed?.text = formatPreviewTime(position)
            musicSheetDuration?.text = formatPreviewTime(duration)
        }
        updateMusicMiniPlay()
        // Ticker (250ms, lihat startMusicLyricsTicker) yang menjalankan
        // sorotan lirik tersinkron sekarang jalan terus selama lagu diputar
        // dan lirik tersedia — tidak lagi cuma saat tab Informasi dibuka.
        // Baris ini sebagai jaring pengaman tambahan (mis. saat ticker
        // belum sempat jalan) supaya progress bar & lirik tetap selalu
        // ikut posisi lagu yang sebenarnya.
        if (musicLyricsToggleOn && musicLyricsLines.isNotEmpty()) syncMusicLyrics(position)
    }


    // ======================= LIRIK TERSINKRON =======================

    /** Berpindah antara Preview dan Lirik dengan animasi silang. */
    private fun setMusicLyricsVisible(show: Boolean) {
        if (musicLyricsVisible == show) return
        musicLyricsVisible = show
        val media = musicSheetStage?.getChildAt(0) ?: return
        val lyrics = musicSheetLyricsScroll ?: return
        val incoming: View = if (show) lyrics else media
        val outgoing: View = if (show) media else lyrics

        musicTabPreview?.apply {
            setTextColor(if (show) muted else Color.WHITE)
            background = rounded(if (show) NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)) else purple, 999, false)
        }
        musicTabLyrics?.apply {
            setTextColor(if (show) Color.WHITE else muted)
            background = rounded(if (show) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 999, false)
        }

        outgoing.animate().cancel()
        incoming.animate().cancel()
        outgoing.animate()
            .alpha(0f)
            .translationY(if (show) -dp(18).toFloat() else dp(18).toFloat())
            .scaleX(.97f).scaleY(.97f)
            .setDuration(190L)
            .withEndAction { outgoing.visibility = View.GONE }
            .start()

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationY = if (show) dp(18).toFloat() else -dp(18).toFloat()
        incoming.scaleX = .97f
        incoming.scaleY = .97f
        incoming.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(90L)
            .setDuration(240L)
            .start()

        // Lirik selalu dimuat & disinkron terus terlepas dari tab mana yang
        // sedang aktif (lihat renderMiniLyrics/startMusicLyricsTicker) —
        // di sini cuma perlu memuat kalau belum ada.
        loadMusicLyrics()
    }

    /**
     * Menjalankan penyorotan baris lirik yang sedang berjalan tiap 250ms
     * (cukup rapat supaya perpindahan baris kelihatan pas mengikuti audio,
     * tidak kelihatan telat atau meloncat). Berjalan terus selama lagu
     * dengan lirik tersinkron diputar & toggle lirik dalam keadaan aktif —
     * tidak lagi bergantung sedang di tab Informasi atau bukan, karena
     * panel lirik sekarang selalu tampil di tab Preview juga.
     */
    private fun startMusicLyricsTicker() {
        musicLyricsTicker?.let { root.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                if (!musicLyricsToggleOn) return
                if (musicLyricsLines.isEmpty() || musicLyricsLines.first().timeMs < 0L) return
                musicPlayer?.let { syncMusicLyrics(it.currentPosition) }
                root.postDelayed(this, 250L)
            }
        }
        musicLyricsTicker = ticker
        root.post(ticker)
    }

    /** Tombol on/off panel lirik di bawah progress bar. */
    private fun updateMusicLyricsToggleUi() {
        musicSheetMiniLyricScroll?.visibility = if (musicLyricsToggleOn) View.VISIBLE else View.GONE
        musicLyricsToggleBtn?.apply {
            text = "\ud83c\udfb5"
            background = null
            alpha = if (musicLyricsToggleOn) 1f else 0.32f
            contentDescription = if (musicLyricsToggleOn) NaoLang.t("Lyrics on") else NaoLang.t("Lyrics off")
        }
        if (musicLyricsToggleOn) {
            startMusicLyricsTicker()
        } else {
            musicLyricsTicker?.let { root.removeCallbacks(it) }
            musicLyricsTicker = null
        }
    }

    private fun lyricsOffsetKey(videoId: String) = "lyrics_offset_" + videoId

    private fun loadMusicLyrics() {
        val track = musicNow ?: run {
            musicLyricsStatus("Belum ada lagu yang diputar.")
            return
        }
        if (musicLyricsVideoId == track.videoId && musicLyricsLines.isNotEmpty()) return
        if (musicLyricsLoading) {
            // Permintaan lama masih jalan untuk lagu sebelumnya: jadwalkan ulang
            // supaya lagu yang sekarang tetap dapat liriknya sendiri.
            musicLyricsPending = true
            return
        }
        musicLyricsLoading = true
        musicLyricsPending = false
        musicLyricsStatus("Memuat lirik\u2026")
        val durationSec = ((musicPlayer?.duration ?: 0L) / 1000L).toInt()
        exec.execute {
            val result = NaoLyricsClient.fetch(track.videoId, track.title, track.artist, durationSec)
            runOnUiThread {
                musicLyricsLoading = false
                if (musicNow?.videoId != track.videoId) {
                    // Lagu sudah berganti saat lirik diambil: buang hasilnya
                    // dan muat ulang untuk lagu yang sedang diputar. Selalu
                    // dimuat ulang terlepas dari tab yang aktif, karena
                    // panel lirik sekarang tampil di tab Preview juga.
                    musicLyricsPending = false
                    loadMusicLyrics()
                    return@runOnUiThread
                }
                if (musicLyricsPending) {
                    musicLyricsPending = false
                }
                musicLyricsVideoId = track.videoId
                musicLyricsOffsetMs = prefs.getLong(lyricsOffsetKey(track.videoId), 0L)
                musicLyricsLines = result.lines
                musicLyricsActiveIndex = -1
                if (result.lines.isEmpty()) {
                    musicLyricsStatus("Lirik tidak ditemukan untuk lagu ini.")
                } else {
                    renderMusicLyrics(result.synced)
                }
            }
        }
    }

    /** Blok detail lagu (judul/artis/album/durasi) di atas isi tab Informasi. */
    private fun addSongInfoHeader(box: LinearLayout) {
        val track = musicNow ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(22, 26, 37)), 20, false)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(tv("Informasi Lagu", 12f, true).apply {
            setTextColor(muted); setPadding(0, 0, 0, dp(8))
        })
        fun row(label: String, value: String) {
            if (value.isBlank()) return
            val r = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            r.addView(tv(label, 12f).apply { setTextColor(muted) }, LinearLayout.LayoutParams(dp(78), -2))
            r.addView(tv(value, 12f, true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(r)
        }
        row("Judul", track.title)
        row("Artis", track.artist.ifBlank { "Tidak diketahui" })
        row("Album", track.album)
        if (track.duration.isNotBlank()) row("Durasi", track.duration)
        box.addView(card)
        box.addView(View(this).apply { setBackgroundColor(Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(16)))
        // Catatan: "makna lagu" (interpretasi/arti lirik) sengaja tidak
        // ditampilkan sebagai teks otomatis di sini — InnerTube/YouTube
        // Music tidak menyediakan data interpretasi semacam itu per lagu,
        // jadi menampilkannya berisiko cuma karangan yang bisa salah.
        // Tab Informasi ini sengaja cuma menampilkan detail faktual lagu.
        // Teks lirik penuh ditampilkan langsung di panel atas tombol
        // play/pause (musicSheetMiniLyricBox), gaya YouTube Music — lihat
        // renderMiniLyrics().
    }

    private fun musicLyricsStatus(message: String) {
        // Tidak ada baris buat disinkron selama status ini tampil (memuat/
        // tidak ditemukan/belum ada lagu) — hentikan ticker-nya dulu.
        musicLyricsTicker?.let { root.removeCallbacks(it) }
        musicLyricsTicker = null

        val box = musicSheetLyricsBox ?: return
        box.removeAllViews()
        addSongInfoHeader(box)
        musicSheetLyricsStatus = tv(message, 13f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        box.addView(musicSheetLyricsStatus)

        val miniBox = musicSheetMiniLyricBox
        miniBox?.removeAllViews()
        musicLyricsViews.clear()
        miniBox?.addView(tv(message, 13f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
        })
    }

    /**
     * Tab Informasi HANYA menampilkan detail faktual lagu (+ kontrol geser
     * sinkron kalau lirik tersinkron). Lirik penuhnya sendiri dirender di
     * panel mini atas tombol play/pause lewat renderMiniLyrics().
     */
    private fun renderMusicLyrics(synced: Boolean) {
        val box = musicSheetLyricsBox ?: return
        box.removeAllViews()
        addSongInfoHeader(box)
        if (synced) {
            // Penyetel presisi: kalau lirik terasa telat atau kecepetan,
            // geser sendiri per 0,3 detik. Tersimpan per lagu.
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(4))
            }
            fun shift(delta: Long) {
                musicLyricsOffsetMs = (musicLyricsOffsetMs + delta).coerceIn(-8000L, 8000L)
                prefs.edit().putLong(lyricsOffsetKey(musicLyricsVideoId), musicLyricsOffsetMs).apply()
                updateMusicLyricsOffsetLabel()
                musicLyricsActiveIndex = -1
                musicPlayer?.let { syncMusicLyrics(it.currentPosition) }
            }
            fun nudge(text: String, delta: Long) = tv(text, 13f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 28, 40)), 14, false)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { shift(delta) }
            }
            val offsetLabel = tv(String.format(java.util.Locale.US, "Sinkron %+.1fs", musicLyricsOffsetMs / 1000f), 11f).apply {
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                // Reset offset dengan tap-lama pada label, kalau geseran malah
                // salah arah dan pengguna mau mulai ulang dari 0.
                setOnLongClickListener { shift(-musicLyricsOffsetMs); true }
            }
            musicLyricsOffsetLabel = offsetLabel
            tools.addView(nudge("\u2212 0.3", -300L))
            tools.addView(nudge("\u2212 0.1", -100L))
            tools.addView(offsetLabel)
            tools.addView(nudge("+ 0.1", 100L))
            tools.addView(nudge("+ 0.3", 300L))
            box.addView(tools)
            box.addView(tv("Tips: ketuk baris lirik yang sedang dinyanyikan supaya sinkron otomatis pas di situ.", 10f).apply {
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(0))
            })
        }
        musicSheetLyricsScroll?.scrollTo(0, 0)

        renderMiniLyrics(synced)
    }

    /**
     * Menampilkan lirik penuh langsung di panel atas tombol play/pause,
     * mirip tampilan lirik YouTube Music. Untuk lirik tersinkron, baris
     * yang sedang berjalan disorot & auto-scroll lewat syncMusicLyrics().
     * Untuk lirik statis, semua baris tetap tampil apa adanya (bisa
     * discroll manual, tanpa sorotan karena tidak ada timestamp-nya).
     */
    private fun renderMiniLyrics(synced: Boolean) {
        val box = musicSheetMiniLyricBox ?: return
        box.removeAllViews()
        musicLyricsViews.clear()
        musicLyricsLines.forEach { line ->
            val view = tv(line.text.ifBlank { "\u266a" }, 17f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(if (synced) muted else Color.WHITE)
                alpha = if (synced) .55f else .85f
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(9), 0, dp(9))
            }
            // Ketuk baris ini kalau lagunya lagi persis menyanyikan baris ini
            // tapi yang tersorot masih baris lain (ketinggalan/kecepetan) —
            // offset langsung dihitung dari posisi lagu saat ini, jadi selalu
            // pas walau sumber lirik beda rilis dari lagu yang diputar.
            if (synced && line.timeMs >= 0L) view.setOnClickListener { resyncLyricsToLine(line) }
            musicLyricsViews.add(view)
            box.addView(view)
        }
        musicSheetMiniLyricScroll?.scrollTo(0, 0)
        musicLyricsActiveIndex = -1
        if (synced) {
            musicPlayer?.let { syncMusicLyrics(it.currentPosition) }
            if (musicLyricsToggleOn) startMusicLyricsTicker()
        }
    }

    private fun updateMusicLyricsOffsetLabel() {
        musicLyricsOffsetLabel?.text =
            String.format(java.util.Locale.US, "Sinkron %+.1fs", musicLyricsOffsetMs / 1000f)
    }

    /**
     * Dipanggil saat pengguna mengetuk sebuah baris lirik: menghitung ulang
     * musicLyricsOffsetMs supaya baris yang diketuk itu tepat aktif di posisi
     * lagu SEKARANG. Jauh lebih presisi daripada coba-coba geser 0,1s/0,3s,
     * karena langsung dikunci ke telinga pengguna sendiri — begitu lagu
     * sampai kata itu, pengguna tinggal ketuk baris yang sedang dinyanyikan.
     */
    private fun resyncLyricsToLine(line: NaoLyricsClient.Line) {
        val player = musicPlayer ?: return
        musicLyricsOffsetMs = (line.timeMs - player.currentPosition - 120L).coerceIn(-8000L, 8000L)
        prefs.edit().putLong(lyricsOffsetKey(musicLyricsVideoId), musicLyricsOffsetMs).apply()
        updateMusicLyricsOffsetLabel()
        musicLyricsActiveIndex = -1
        syncMusicLyrics(player.currentPosition)
        Toast.makeText(this, NaoLang.t("Lirik disinkronkan ke baris ini \u2713"), Toast.LENGTH_SHORT).show()
    }

    /**
     * Menentukan baris lirik yang sedang berjalan sesuai posisi lagu, lalu
     * menyorot & men-scroll panel lirik mini (musicSheetMiniLyricBox) ke
     * baris itu — persis seperti tampilan lirik berjalan di YouTube Music.
     */
    private fun syncMusicLyrics(positionMs: Long) {
        if (musicLyricsViews.isEmpty()) return
        val lines = musicLyricsLines
        if (lines.isEmpty() || lines.first().timeMs < 0L) return
        // Offset manual + kompensasi kecil supaya baris berganti tepat saat
        // penyanyi mulai, bukan setelah baitnya lewat.
        val cursor = positionMs + 120L + musicLyricsOffsetMs
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= cursor) index = i else break
        }
        if (index < 0 || index == musicLyricsActiveIndex) return

        musicLyricsViews.getOrNull(musicLyricsActiveIndex)?.let { old ->
            old.animate().cancel()
            old.animate().alpha(.55f).scaleX(1f).scaleY(1f).setDuration(180L).start()
            old.setTextColor(muted)
        }
        musicLyricsActiveIndex = index
        val active = musicLyricsViews.getOrNull(index) ?: return
        active.setTextColor(Color.WHITE)
        active.animate().cancel()
        active.animate().alpha(1f).scaleX(1.06f).scaleY(1.06f).setDuration(220L).start()

        val scroll = musicSheetMiniLyricScroll ?: return
        val target = (active.top - scroll.height / 2 + active.height / 2).coerceAtLeast(0)
        scroll.smoothScrollTo(0, target)
    }

    // ======================= MENU TITIK TIGA =======================

    private fun showMusicTrackMenu() {
        val track = musicNow
        if (track == null) {
            Toast.makeText(this, NaoLang.t("Belum ada lagu yang diputar."), Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        val panel = musicDialogCard("Opsi Lagu")
        panel.addView(tv(track.title, 18f, true).apply { maxLines = 2 })
        panel.addView(tv(track.artist.ifBlank { "YouTube Music" }, 12f).apply {
            setTextColor(muted); setPadding(0, dp(4), 0, dp(14))
        })

        fun item(label: String, subtitle: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(20, 24, 34)), 20, false)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { dialog.dismiss(); action() }
            }
            row.addView(tv(label, 14f, true))
            row.addView(tv(subtitle, 11f).apply { setTextColor(muted); setPadding(0, dp(2), 0, 0) })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }

        item("\u2661 ${NaoLang.t(if (musicStore.isLiked(track.videoId)) "Hapus dari suka" else "Suka")}", "Simpan ke Liked Songs") {
            val liked = musicStore.toggleLike(track)
            Toast.makeText(this, NaoLang.t(if (liked) "Ditambahkan ke suka" else "Dihapus dari suka"), Toast.LENGTH_SHORT).show()
        }
        item("\u2b07 ${NaoLang.t("Unduh lagu")}", "Simpan audio ke Download/NAO") { downloadCurrentTrack(track) }
        item("\u2795 ${NaoLang.t("Tambah ke playlist")}", "Tambahkan lagu ini ke playlist kamu") { showAddToPlaylist(track) }
        item("\ud83d\udd17 Share link lagu", "Bagikan tautan lagu ke aplikasi lain") { shareTrackLink(track) }

        panel.addView(btn("Tutup", false).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)))

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.5f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun downloadCurrentTrack(track: InnerTubeClient.Track) {
        Toast.makeText(this, NaoLang.t("Menyiapkan unduhan\u2026"), Toast.LENGTH_SHORT).show()
        exec.execute {
            try {
                // Triple<url, mimeType, bitrate> agar dua sumber stream bisa disatukan.
                val streams: List<Triple<String, String, Int>> = try {
                    NaoMusicBackend.resolveAudio(track.videoId).map { Triple(it.url, it.mimeType, it.bitrate) }
                } catch (_: Throwable) {
                    YouTubeStreamResolver.resolve(track.videoId).map { Triple(it.url, it.mimeType, it.bitrate) }
                }
                val audio = streams
                    .filter { it.first.isNotBlank() && it.second.startsWith("audio/") }
                    .maxByOrNull { it.third }
                    ?: streams.firstOrNull { it.first.isNotBlank() }
                    ?: error("Stream audio tidak tersedia")

                val ext = if (audio.second.contains("webm")) ".webm" else ".m4a"
                val name = safeName("${track.title} - ${track.artist}") + ext
                runOnUiThread {
                    // Lewat pusat unduhan supaya progresnya kelihatan seperti
                    // unduhan di browser dan hilang sendiri setelah 100%.
                    NaoDownloadCenter.enqueue(this, name, "audio", audio.first)
                    Toast.makeText(this, NaoLang.t("Unduhan lagu dimulai"), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, NaoLang.t("Gagal mengunduh: ${e.message ?: "tidak diketahui"}"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareTrackLink(track: InnerTubeClient.Track) {
        val link = "https://music.youtube.com/watch?v=${track.videoId}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            putExtra(Intent.EXTRA_TEXT, "${track.title} \u2014 ${track.artist.ifBlank { "YouTube Music" }}\n$link")
        }
        startActivity(Intent.createChooser(intent, "Bagikan lagu"))
    }

    // ======================= PLAYLIST =======================

    private fun musicPlaylists(): JSONObject =
        try { JSONObject(prefs.getString("nao_playlists", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun saveMusicPlaylists(data: JSONObject) {
        prefs.edit().putString("nao_playlists", data.toString()).apply()
    }

    private fun addTrackToPlaylist(name: String, track: InnerTubeClient.Track) {
        val data = musicPlaylists()
        val list = data.optJSONArray(name) ?: JSONArray()
        for (i in 0 until list.length()) {
            if (list.optJSONObject(i)?.optString("videoId") == track.videoId) {
                Toast.makeText(this, NaoLang.t("Lagu sudah ada di \"$name\"."), Toast.LENGTH_SHORT).show()
                return
            }
        }
        list.put(JSONObject().apply {
            put("videoId", track.videoId)
            put("title", track.title)
            put("artist", track.artist)
            put("thumbnail", track.thumbnail)
        })
        data.put(name, list)
        saveMusicPlaylists(data)
        Toast.makeText(this, NaoLang.t("Ditambahkan ke playlist \"$name\"."), Toast.LENGTH_SHORT).show()
    }

    private fun showAddToPlaylist(track: InnerTubeClient.Track) {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Playlist")
        panel.addView(tv("Taruh ke Playlist", 20f, true))
        panel.addView(tv(track.title, 11f).apply { setTextColor(muted); setPadding(0, dp(4), 0, dp(12)); maxLines = 1 })

        val data = musicPlaylists()
        val names = data.keys().asSequence().toList().sorted()
        if (names.isEmpty()) {
            panel.addView(tv("Belum ada playlist. Buat playlist baru di bawah.", 11f).apply {
                setTextColor(muted); setPadding(0, 0, 0, dp(10))
            })
        }
        names.forEach { name ->
            val count = data.optJSONArray(name)?.length() ?: 0
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(20, 24, 34)), 18, false)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(tv(name, 14f, true))
                addView(tv("$count lagu", 11f).apply { setTextColor(muted) })
                setOnClickListener { dialog.dismiss(); addTrackToPlaylist(name, track) }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }

        val nameField = field("Nama playlist baru")
        panel.addView(nameField, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(8)) })
        panel.addView(btn("Buat & Tambahkan", true).apply {
            setOnClickListener {
                val name = nameField.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this@MainActivity, NaoLang.t("Nama playlist tidak boleh kosong."), Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    addTrackToPlaylist(name, track)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(btn("Tutup", false).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)))

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.5f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun startMusicProgressLoop() {
        musicProgressRunnable?.let { root.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                updateMusicProgressNow()
                // Loop ringan: 500ms cukup halus untuk seekbar & lirik,
                // tapi memangkas separuh kerja UI dibanding 250ms.
                root.postDelayed(this, if (musicUiResumed) 500L else 1500L)
            }
        }
        musicProgressRunnable = r
        root.post(r)
    }

    private fun core() {
        setNavSelected("Core")
        clear()
        title("CREDITS", "DUCKTys.", "README-inspired profile · static in-app presentation.")

        val profile = card()
        profile.setPadding(dp(18), dp(18), dp(18), dp(18))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gh = assetImage("github", 48).apply {
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 28, 38)), 999, true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(gh, LinearLayout.LayoutParams(dp(64), dp(64)))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        identity.addView(tv("DUCKTys", 23f, true))
        identity.addView(tv("@DUCKTys  ·  Open Source", 12f).apply {
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        profile.addView(header)

        profile.addView(tv("Software Developer · Open Source Builder", 15f, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(18), 0, dp(5))
        })

        val duckAvatar = ImageView(this).apply {
            setImageResource(resources.getIdentifier("ducktys_profile", "drawable", packageName))
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
            contentDescription = "DUCKTys profile"
        }
        profile.addView(duckAvatar, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        profile.addView(tv("I build useful applications and software experiences with a focus on clean code, usability, and expressive interfaces.", 12f).apply {
            setTextColor(muted)
            setLineSpacing(0f, 1.25f)
        })
        profile.addView(tv("ABOUT", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, dp(20), 0, dp(8))
        })
        profile.addView(card().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(tv("Building open-source tools, experimenting with modern interfaces, and turning ideas into practical software.", 12f).apply {
                setLineSpacing(0f, 1.25f)
            })
        }, LinearLayout.LayoutParams(-1, -2))

        profile.addView(tv("TECH STACK", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, dp(18), 0, dp(8))
        })
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stackRows = listOf("JavaScript" to "TypeScript", "Kotlin" to "HTML5", "CSS3" to "Python")
        stackRows.forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            fun chip(label: String): TextView = tv("  $label  ", 11f, true).apply {
                val themeBright = NaoThemeManager.current(this@MainActivity).bright
                gravity = Gravity.CENTER
                setTextColor(if (label == "JavaScript" || label == "Kotlin" || label == "CSS3") themeBright else Color.rgb(114, 231, 215))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(13, 17, 25)), 999, true).apply { setStroke(dp(1), Color.argb(55, Color.red(themeBright), Color.green(themeBright), Color.blue(themeBright))) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            row.addView(chip(pair.first), LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(0, 0, dp(5), 0) })
            row.addView(chip(pair.second), LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(5), 0, 0, 0) })
            stack.addView(row, LinearLayout.LayoutParams(-1, dp(38)).apply { if (index > 0) setMargins(0, dp(7), 0, 0) })
        }
        profile.addView(stack)

        profile.addView(tv("NOTE", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, dp(20), 0, dp(8))
        })
        profile.addView(tv("This page is a curated README-style profile built directly into Nao MD. It does not fetch or render the GitHub README.", 12f).apply {
            setTextColor(muted); setLineSpacing(0f, 1.22f)
        })
        profile.addView(btn("GitHub DUCKTys  ↗", true).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DUCKTys"))) }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(18), 0, 0) })

        profile.addView(tv("SUPPORT NAO MD", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, dp(20), 0, dp(8))
        })
        profile.addView(tv("Kalau aplikasi ini membantu, dukungan kecil sangat berarti. Semua tombol dibuat rounded dan tetap berada di dalam Credits.", 11f).apply {
            setTextColor(muted); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(10))
        })
        profile.addView(btn("☕ Donasi via Saweria", true).apply {
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/ducktys"))) } catch (_: Exception) {} }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0,0,0,dp(8)) })
        profile.addView(btn("Gopay · 087815632486", false).apply {
            setOnClickListener { openDonationApp("Gopay", "com.gojek.gopay", "com.gojek.app") }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0,0,0,dp(7)) })
        profile.addView(btn("Dana · 087815632486", false).apply {
            setOnClickListener { openDonationApp("DANA", "id.dana") }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0,0,0,dp(7)) })
        profile.addView(btn("OVO · 087815632486", false).apply {
            setOnClickListener { openDonationApp("OVO", "ovo.id") }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0,0,0,dp(8)) })
        profile.addView(tv("Nomor donasi: 087815632486", 10f).apply { setTextColor(muted); gravity = Gravity.CENTER })

        content.addView(profile)
    }

    private fun openDonationApp(label: String, vararg packageNames: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Nomor donasi", "087815632486"))
        for (packageName in packageNames) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                try { startActivity(launch); return } catch (_: Exception) {}
            }
        }
        Toast.makeText(this, NaoLang.t("$label belum terpasang. Nomor donasi sudah disalin."), Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------
    // Bahasa aplikasi (English, Indonesia, Jepang, dll.)
    // ---------------------------------------------------------------
    private val appLanguages = listOf(
        "" to "Ikuti Sistem / System default",
        "en" to "English",
        "in" to "Bahasa Indonesia",
        "ja" to "\u65e5\u672c\u8a9e (Japanese)",
        "ko" to "\ud55c\uad6d\uc5b4 (Korean)",
        "zh-CN" to "\u7b80\u4f53\u4e2d\u6587 (Chinese Simplified)",
        "zh-TW" to "\u7e41\u9ad4\u4e2d\u6587 (Chinese Traditional)",
        "es" to "Espa\u00f1ol",
        "fr" to "Fran\u00e7ais",
        "de" to "Deutsch",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pt-BR" to "Portugu\u00eas (Brasil)",
        "ru" to "\u0420\u0443\u0441\u0441\u043a\u0438\u0439",
        "uk" to "\u0423\u043a\u0440\u0430\u0457\u043d\u0441\u044c\u043a\u0430",
        "tr" to "T\u00fcrk\u00e7e",
        "ar" to "\u0627\u0644\u0639\u0631\u0628\u064a\u0629",
        "hi" to "\u0939\u093f\u0928\u094d\u0926\u0940",
        "th" to "\u0e44\u0e17\u0e22",
        "vi" to "Ti\u1ebfng Vi\u1ec7t",
        "ms" to "Bahasa Melayu",
        "tl" to "Filipino"
    )

    private fun currentLanguageTag(): String = prefs.getString("app_language", "") ?: ""

    private fun currentLanguageLabel(): String =
        appLanguages.firstOrNull { it.first == currentLanguageTag() }?.second
            ?: appLanguages.first().second

    private fun applySavedLanguage() {
        val tag = currentLanguageTag()
        NaoLang.load(this, tag)
        val target = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales() != target) {
            AppCompatDelegate.setApplicationLocales(target)
        }
    }

    private fun setAppLanguage(tag: String) {
        prefs.edit().putString("app_language", tag).commit()
        NaoLang.load(this, tag)
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
        // The Nao UI is built in code, so it has to be rebuilt for the new
        // language to actually appear on screen.
        saveMusicSessionState(sync = true)
        recreate()
    }

    private fun showLanguageSettings() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Bahasa")
        panel.addView(tv("Bahasa / Language", 22f, true))
        panel.addView(tv("Pilih bahasa tampilan Nao MD.", 11f).apply {
            setTextColor(muted); setPadding(0, dp(5), 0, dp(12))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selected = currentLanguageTag()
        appLanguages.forEach { (tag, label) ->
            val active = tag == selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (active) NaoThemeManager.current(this@MainActivity).dark else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, false)
                setPadding(dp(15), dp(12), dp(15), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    setAppLanguage(tag)
                }
            }
            row.addView(tv(label, 14f, active), LinearLayout.LayoutParams(0, -2, 1f))
            if (active) row.addView(tv("\u2713", 15f, true).apply { setTextColor(purple) })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        panel.addView(Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, false)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(10), 0, 0) })

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showNaoMdSettings() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Nao MD Settings")
        panel.addView(tv("Pengaturan Nao MD", 23f, true))
        panel.addView(tv("Pilih icon aplikasi dan animasi pembukaan Nao MD dari satu tempat.", 11f).apply {
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        })

        panel.addView(btn("Periksa Update Aplikasi  ·  v${appVersionName()}", false).apply {
            setOnClickListener { checkForAppUpdate(manual = true) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(btn("Izin Berjalan di Latar Belakang", false).apply {
            setOnClickListener { requestBackgroundRunPermission() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })

        panel.addView(btn("Bahasa / Language  ·  ${currentLanguageLabel()}", false).apply {
            setOnClickListener { dialog.dismiss(); showLanguageSettings() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })

        panel.addView(btn(
            "API Key Downloader (SaverAPI)  ·  " +
                if (com.nao.md.project.core.DownloaderApiConfig.isSaverApiConfigured(this)) "Terpasang" else "Belum diisi",
            false
        ).apply {
            setOnClickListener { dialog.dismiss(); showDownloaderApiSettings() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })

        panel.addView(tv("APP ICON", 10f, true).apply {
            setTextColor(muted)
            letterSpacing = .14f
            setPadding(0, 0, 0, dp(7))
        })
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun iconChoice(number: Int, res: Int) {
            val holder = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(
                    if (NaoIconManager.currentPreset(this@MainActivity) == number)
                        NaoThemeManager.current(this@MainActivity).dark else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)),
                    18, true
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    NaoIconManager.applyPreset(this@MainActivity, number)
                    applyNaoTaskIdentity()
                    Toast.makeText(this@MainActivity, NaoLang.t("Icon $number dipilih. Animasi pembukaan akan mengikuti icon ini."),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
            holder.addView(ImageView(this@MainActivity).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Icon $number"
            }, LinearLayout.LayoutParams(dp(78), dp(78)))
            holder.addView(tv("Icon $number", 10f, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
            iconRow.addView(holder, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        iconChoice(1, R.drawable.nao_icon_1)
        iconChoice(2, R.drawable.nao_icon_2)
        iconChoice(3, R.drawable.nao_icon_3)
        panel.addView(iconRow, LinearLayout.LayoutParams(-1, dp(116)))

        panel.addView(tv("TEMA WARNA", 10f, true).apply {
            setTextColor(muted)
            letterSpacing = .14f
            setPadding(0, dp(14), 0, dp(7))
        })
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun themeChoice(theme: NaoTheme) {
            val active = NaoThemeManager.current(this@MainActivity) == theme
            val holder = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(if (active) theme.dark else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(18, 22, 32)), 18, true)
                setPadding(dp(8), dp(10), dp(8), dp(8))
                setOnClickListener {
                    NaoThemeManager.applyPreset(this@MainActivity, theme)
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, NaoLang.t("Tema ${theme.label} diterapkan."), Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }
            holder.addView(View(this@MainActivity).apply {
                background = rounded(theme.ring, 999, false).apply {
                    if (active) setStroke(dp(2), Color.WHITE)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            holder.addView(tv(theme.label, 10f, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, 0)
            })
            themeRow.addView(holder, LinearLayout.LayoutParams(0, dp(90), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        themeChoice(NaoTheme.UNGU)
        themeChoice(NaoTheme.PINK)
        themeChoice(NaoTheme.MAROON)
        panel.addView(themeRow, LinearLayout.LayoutParams(-1, dp(94)))

        panel.addView(tv("DISCORD", 10f, true).apply {
            setTextColor(muted)
            letterSpacing = .14f
            setPadding(0, 0, 0, dp(7))
        })
        panel.addView(tv(
            when {
                NaoDiscordManager.isPresenceReady(this) ->
                    "Discord siap · putar lagu agar Listening to Nao Music tampil di profil."
                else ->
                    "Hubungkan Discord lewat browser — token disimpan otomatis (seperti ArchiveTune)."
            },
            11f
        ).apply { setTextColor(muted); setLineSpacing(0f, 1.2f) })
        panel.addView(btn(
            if (NaoDiscordManager.isConnected(this)) "Pengaturan Discord"
            else "Hubungkan Discord",
            true
        ).apply {
            setOnClickListener {
                dialog.dismiss()
                showDiscordSettings()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(10), 0, dp(8)) })
        panel.addView(btn("Buka Nao Music Website ↗", false).apply {
            setOnClickListener { NaoDiscordManager.openWebsite(this@MainActivity) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(10)) })

        panel.addView(btn("Tutup", false).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)))

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun applyNaoTaskIdentity() {
        try {
            val res = when (NaoIconManager.currentPreset(this)) {
                2 -> R.drawable.nao_icon_2
                3 -> R.drawable.nao_icon_3
                else -> R.drawable.nao_icon_1
            }
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, res)
            setTaskDescription(android.app.ActivityManager.TaskDescription("Nao MD", bitmap))
            bitmap?.recycle()
        } catch (_: Exception) {}
    }


    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun requestBackgroundRunPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, NaoLang.t("Nao MD sudah diizinkan berjalan di latar belakang."), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * Pop-up izin latar belakang saat aplikasi dibuka. Ditampilkan sampai izin
     * diberikan, kecuali pengguna memilih "Jangan tampilkan lagi".
     */
    private fun showBackgroundRunPromptIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (prefs.getBoolean("bg_run_prompt_muted", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val dialog = Dialog(this)
        val panel = musicDialogCard("Izin Berjalan di Latar Belakang")
        panel.addView(tv("Izinkan berjalan di latar belakang", 20f, true))
        panel.addView(tv(
            "Agar musik dan Rich Presence tetap jalan saat aplikasi ditutup atau layar mati, Nao MD perlu dikecualikan dari penghemat baterai.",
            12f
        ).apply {
            setTextColor(muted)
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(8), 0, dp(16))
        })

        panel.addView(btn("Izinkan Sekarang", true).apply {
            setOnClickListener {
                dialog.dismiss()
                requestBackgroundRunPermission()
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(btn("Nanti Saja", false).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(tv("Jangan tampilkan lagi", 11f).apply {
            setTextColor(muted)
            gravity = Gravity.CENTER
            setOnClickListener {
                prefs.edit().putBoolean("bg_run_prompt_muted", true).apply()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, dp(34)))

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.5f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Mengirim presence untuk lagu aktif (atau status idle) segera. */
    private fun activateRichPresenceNow() {
        val now = musicNow
        if (now != null) {
            NaoDiscordManager.updateForTrack(
                this,
                now.title,
                now.artist.ifBlank { "Nao Music" },
                "https://i.ytimg.com/vi/${now.videoId}/maxresdefault.jpg",
                musicPlayer?.isPlaying != true,
                musicPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                musicPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                now.album
            )
        } else {
            NaoDiscordManager.updateForTrack(this, "Nao Music", "Siap memutar musik", "", true)
        }
    }

    private fun checkForAppUpdate(manual: Boolean = false) {
        AppUpdateChecker.check(this) { release ->
            if (release == null) {
                if (manual) {
                    val err = AppUpdateChecker.lastError
                    val msg = if (err != null) "Gagal cek update: $err" else "Nao MD sudah versi terbaru."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                return@check
            }
            val dialog = Dialog(this)
            val panel = musicDialogCard("Update Nao MD")
            panel.addView(tv("Update tersedia · ${release.version}${if (release.preRelease) " (pre-release)" else ""}", 22f, true))
            panel.addView(tv("Versi aplikasi: v${appVersionName()}\nVersi terbaru: v${release.version}", 12f).apply {
                setTextColor(muted); setPadding(0, dp(6), 0, dp(14))
            })
            // Deskripsi/changelog diambil langsung dari body release GitHub —
            // jadi pengguna tahu apa yang berubah sebelum memutuskan update.
            if (release.notes.isNotBlank()) {
                panel.addView(tv("Yang baru", 12f, true).apply {
                    setTextColor(muted); setPadding(0, 0, 0, dp(4))
                })
                panel.addView(tv(release.notes.trim(), 12.5f).apply {
                    setPadding(0, 0, 0, dp(14))
                    setLineSpacing(0f, 1.2f)
                })
            }
            if (release.apkUrl.isNotBlank()) {
                // Unduh APK-nya sendiri lewat pusat unduhan aplikasi, tanpa
                // buka browser/website dulu. Progresnya kelihatan di tab
                // Berkas ("Cek Download"), dan begitu selesai langsung
                // ditawarkan pemasangannya.
                panel.addView(tv(
                    if (release.apkSize > 0) "Ukuran APK: ${formatBytes(release.apkSize)}" else "Update tersedia dalam berkas APK.",
                    11f
                ).apply { setTextColor(muted); setPadding(0, 0, 0, dp(12)) })
                panel.addView(btn("\u2b07 Unduh & Pasang di Aplikasi", true).apply {
                    setOnClickListener {
                        NaoDownloadCenter.enqueue(
                            this@MainActivity,
                            "Nao MD v${release.version}.apk",
                            "apk",
                            release.apkUrl
                        ) { job -> promptInstallApk(job) }
                        Toast.makeText(this@MainActivity, NaoLang.t("Unduhan update dimulai \u00b7 lihat tab Berkas > Cek Download"), Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0,0,0,dp(8)) })
                panel.addView(btn("Buka Website Download ↗", false).apply {
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateChecker.DOWNLOAD_SITE)))
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0,0,0,dp(8)) })
            } else {
                panel.addView(tv("Belum ada berkas APK di release ini, unduh lewat website.", 11f).apply { setTextColor(muted); setPadding(0,0,0,dp(12)) })
                panel.addView(btn("Buka Website Download ↗", true).apply {
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateChecker.DOWNLOAD_SITE)))
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0,0,0,dp(8)) })
            }
            panel.addView(btn("Nanti", false).apply { setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(46)))
            dialog.setContentView(NaoUiCompat.scrollable(panel))
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Dipanggil begitu unduhan APK update selesai (lihat checkForAppUpdate).
     * Kalau izin "instal aplikasi tidak dikenal" belum diberikan untuk Nao MD,
     * arahkan dulu ke pengaturannya — begitu diizinkan, pengguna tinggal
     * ketuk lagi berkas APK-nya di tab Berkas > Cek Download untuk memasang.
     */
    private fun promptInstallApk(job: NaoDownloadCenter.Job) {
        if (isFinishing || isDestroyed) return
        if (job.savedUri.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                NaoLang.t("Izinkan \"Instal aplikasi tidak dikenal\" untuk Nao MD, lalu ketuk lagi APK update di tab Berkas."),
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(job.savedUri), NaoDownloadCenter.mimeFor(job.filename))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, NaoLang.t("Gagal membuka installer: ${e.message ?: "tidak diketahui"}"), Toast.LENGTH_LONG).show()
        }
    }


    private fun restoreLastPage() {
        val nav = prefs.getString("last_nav", "Home") ?: "Home"
        val tool = prefs.getString("last_tool", null)
        if (nav == "Tools" && tool != null && tool in toolsList.map { it.first }) {
            openTool(tool)
        } else {
            when (nav) {
                "Tools" -> tools()
                "Music" -> music()
                "Berkas" -> berkas()
                "Core" -> core()
                else -> home()
            }
        }
    }

    private fun showDailyDonationPromptIfNeeded() {
        if (isFinishing || isDestroyed) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (prefs.getString("donation_prompt_date", "") == today) return
        prefs.edit().putString("donation_prompt_date", today).apply()

        val dialog = Dialog(this)
        val panel = musicDialogCard("Support Nao MD")
        panel.addView(tv("☕ Dukung Nao MD", 21f, true), LinearLayout.LayoutParams(-1, dp(46)))
        panel.addView(tv("Kalau Nao MD membantu aktivitas kamu, kamu bisa mendukung pengembang. Pop-up ini hanya muncul sekali sehari.", 11f).apply {
            setTextColor(muted); setLineSpacing(0f, 1.2f); setPadding(0, dp(5), 0, dp(14))
        })
        panel.addView(btn("Donasi via Saweria", true).apply {
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/ducktys"))) } catch (_: Exception) {}
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0,0,0,dp(8)) })
        panel.addView(tv("Gopay · 087815632486", 12f, true).apply { setTextColor(muted); setPadding(0,dp(4),0,dp(4)) })
        panel.addView(tv("Dana · 087815632486", 12f, true).apply { setTextColor(muted); setPadding(0,dp(4),0,dp(4)) })
        panel.addView(tv("OVO · 087815632486", 12f, true).apply { setTextColor(muted); setPadding(0,dp(4),0,dp(10)) })
        panel.addView(btn("Tutup", false).apply { setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(46)))
        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.48f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Config API key untuk provider Downloader pertama (SaverAPI). Provider
     * ke-2 (Omegatech) dan ke-3 (Nexray) tidak butuh API key, jadi dipakai
     * otomatis sebagai fallback tanpa perlu diatur di sini.
     */
    private fun showDownloaderApiSettings() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Downloader API")
        panel.addView(tv("API Downloader", 22f, true))
        panel.addView(tv(
            "Resolve URL mencoba SaverAPI dulu (kalau API key diisi), lalu otomatis pindah ke Omegatech dan Nexray sebagai cadangan bila gagal.",
            11f
        ).apply { setTextColor(muted); setPadding(0, dp(5), 0, dp(14)); setLineSpacing(0f, 1.2f) })

        panel.addView(tv("SAVERAPI API KEY", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, 0, 0, dp(7))
        })
        val keyField = field("Tempel API key SaverAPI…").apply {
            setText(com.nao.md.project.core.DownloaderApiConfig.getSaverApiKey(this@MainActivity))
        }
        panel.addView(keyField, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, 0, 0, dp(10)) })

        panel.addView(tv("ENDPOINT (opsional)", 10f, true).apply {
            setTextColor(muted); letterSpacing = .14f; setPadding(0, 0, 0, dp(7))
        })
        val endpointField = field("URL endpoint SaverAPI…").apply {
            setText(com.nao.md.project.core.DownloaderApiConfig.getSaverApiEndpoint(this@MainActivity))
        }
        panel.addView(endpointField, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, 0, 0, dp(14)) })

        val balanceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 20, true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        panel.addView(balanceCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) })

        fun renderBalance(json: JSONObject) {
            balanceCard.removeAllViews()
            balanceCard.visibility = View.VISIBLE
            val plan = json.optJSONObject("plan")?.optString("name", "-") ?: "-"
            val remaining = json.optLong("credits_remaining", -1L)
            val total = json.optLong("credits_total", -1L)
            val days = json.optJSONObject("subscription")?.optInt("days_remaining", -1) ?: -1
            balanceCard.addView(tv("Plan $plan  ·  API key valid", 13f, true).apply {
                setTextColor(Color.rgb(121, 227, 167))
            })
            if (remaining >= 0 && total >= 0) {
                balanceCard.addView(tv("Kredit tersisa: $remaining / $total", 11f).apply {
                    setTextColor(muted); setPadding(0, dp(4), 0, 0)
                })
            }
            if (days >= 0) {
                balanceCard.addView(tv("Masa aktif: $days hari lagi", 11f).apply {
                    setTextColor(muted); setPadding(0, dp(2), 0, 0)
                })
            }
        }

        lateinit var checkBalanceBtn: Button
        checkBalanceBtn = btn("Cek Saldo & Verifikasi API Key", false).apply {
            setOnClickListener {
                val currentKey = keyField.text.toString().trim()
                if (currentKey.isBlank()) {
                    Toast.makeText(this@MainActivity, NaoLang.t("Isi API key dulu."), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                com.nao.md.project.core.DownloaderApiConfig.setSaverApiKey(this@MainActivity, currentKey)
                text = NaoLang.t("Memeriksa…")
                isEnabled = false
                exec.execute {
                    try {
                        val json = com.nao.md.project.core.DownloaderClient(this@MainActivity).checkSaverApiBalance(this@MainActivity)
                        runOnUiThread {
                            renderBalance(json)
                            checkBalanceBtn.text = NaoLang.t("Cek Saldo & Verifikasi API Key")
                            checkBalanceBtn.isEnabled = true
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            balanceCard.removeAllViews()
                            balanceCard.visibility = View.VISIBLE
                            balanceCard.addView(tv("✕ ${e.message}", 12f).apply { setTextColor(Color.rgb(255,120,120)) })
                            checkBalanceBtn.text = NaoLang.t("Cek Saldo & Verifikasi API Key")
                            checkBalanceBtn.isEnabled = true
                        }
                    }
                }
            }
        }
        panel.addView(checkBalanceBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(btn("Simpan", true).apply {
            setOnClickListener {
                com.nao.md.project.core.DownloaderApiConfig.setSaverApiKey(this@MainActivity, keyField.text.toString())
                com.nao.md.project.core.DownloaderApiConfig.setSaverApiEndpoint(this@MainActivity, endpointField.text.toString())
                Toast.makeText(this@MainActivity, NaoLang.t("Pengaturan API downloader disimpan."), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        if (com.nao.md.project.core.DownloaderApiConfig.isSaverApiConfigured(this)) {
            panel.addView(btn("Hapus API Key", false).apply {
                setOnClickListener {
                    com.nao.md.project.core.DownloaderApiConfig.setSaverApiKey(this@MainActivity, "")
                    Toast.makeText(this@MainActivity, NaoLang.t("API key SaverAPI dihapus."), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(8)) })
        }

        panel.addView(Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, true)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(4), 0, 0) })

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showDiscordSettings() {
        val dialog = Dialog(this)
        val panel = musicDialogCard("Discord")
        panel.addView(tv("Discord · Nao Music", 22f, true))
        panel.addView(tv(
            "Hubungkan lewat browser seperti ArchiveTune. Token OAuth disimpan otomatis, lalu putar lagu agar Listening to Nao Music muncul.",
            11f
        ).apply { setTextColor(muted); setPadding(0, dp(5), 0, dp(14)); setLineSpacing(0f, 1.2f) })

        val presenceReady = NaoDiscordManager.isPresenceReady(this)
        val appLinked = NaoDiscordManager.isAppLinked(this)
        val name = NaoDiscordManager.accountName(this)
        val username = NaoDiscordManager.accountUsername(this)
        val live = NaoDiscordManager.isRichPresenceLive()

        val accountCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 22, true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        accountCard.addView(tv(
            when {
                live -> "Gateway aktif · Ready"
                presenceReady -> "Terhubung · menunggu gateway"
                appLinked -> name.ifBlank { "Aplikasi terhubung" }
                else -> "Belum terhubung"
            },
            16f, true
        ))
        accountCard.addView(tv(
            when {
                live && username.isNotBlank() -> "@$username · Listening siap"
                presenceReady && username.isNotBlank() -> "@$username · putar lagu / segarkan"
                presenceReady -> "Sesi OAuth siap · putar lagu atau segarkan"
                appLinked -> "Authorize selesai · menunggu sesi presence"
                else -> "Tekan Hubungkan Discord (browser)"
            },
            11f
        ).apply {
            setTextColor(if (live || presenceReady) Color.rgb(121, 227, 167) else muted)
            setPadding(0, dp(4), 0, 0)
        })
        panel.addView(accountCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })

        panel.addView(tv("Application ID  ${NaoDiscordManager.configuredClientId(this)}", 10f).apply {
            setTextColor(muted)
            setPadding(0, 0, 0, dp(10))
        })

        val rpcSwitch = TextView(this).apply {
            text = if (NaoDiscordManager.isRichPresenceEnabled(this@MainActivity)) "Rich Presence  ·  ON" else "Rich Presence  ·  OFF"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(if (NaoDiscordManager.isRichPresenceEnabled(this@MainActivity)) Color.WHITE else muted)
            background = rounded(
                if (NaoDiscordManager.isRichPresenceEnabled(this@MainActivity)) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(38, 43, 55)),
                18, true
            )
            setOnClickListener {
                val next = !NaoDiscordManager.isRichPresenceEnabled(this@MainActivity)
                NaoDiscordManager.setRichPresenceEnabled(this@MainActivity, next)
                text = if (next) "Rich Presence  ·  ON" else "Rich Presence  ·  OFF"
                setTextColor(if (next) Color.WHITE else muted)
                background = rounded(if (next) purple else NaoThemeManager.tintBox(this@MainActivity, Color.rgb(38, 43, 55)), 18, true)
                if (!next) NaoDiscordManager.clearPresence(this@MainActivity)
                else activateRichPresenceNow()
            }
        }
        panel.addView(rpcSwitch, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(8)) })

        val pausedSwitch = TextView(this).apply {
            text = if (NaoDiscordManager.showWhenPaused(this@MainActivity)) "Tampilkan saat dijeda  ·  ON" else "Tampilkan saat dijeda  ·  OFF"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(if (NaoDiscordManager.showWhenPaused(this@MainActivity)) Color.WHITE else muted)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(38, 43, 55)), 18, true)
            setOnClickListener {
                val next = !NaoDiscordManager.showWhenPaused(this@MainActivity)
                NaoDiscordManager.setShowWhenPaused(this@MainActivity, next)
                text = if (next) "Tampilkan saat dijeda  ·  ON" else "Tampilkan saat dijeda  ·  OFF"
                setTextColor(if (next) Color.WHITE else muted)
            }
        }
        panel.addView(pausedSwitch, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(8)) })

        val statusTv = tv(NaoDiscordManager.richPresenceStatus(this), 10f).apply {
            setTextColor(muted); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(10))
        }
        panel.addView(statusTv)

        // Manual refresh — reconnect gateway + push current track (ArchiveTune-style)
        panel.addView(btn("Segarkan manual", true).apply {
            setOnClickListener {
                if (!NaoDiscordManager.isPresenceReady(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, NaoLang.t("Hubungkan Discord (browser) dulu."),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                NaoDiscordManager.setRichPresenceEnabled(this@MainActivity, true)
                NaoDiscordRpc.forceReconnect(this@MainActivity)
                activateRichPresenceNow()
                statusTv.text = NaoDiscordManager.richPresenceStatus(this@MainActivity)
                Toast.makeText(this@MainActivity, NaoLang.t("Menyegarkan gateway Discord…"),
                    Toast.LENGTH_SHORT
                ).show()
                // Re-read status after a short delay so Broken pipe / Ready is visible.
                statusTv.postDelayed({
                    statusTv.text = NaoDiscordManager.richPresenceStatus(this@MainActivity)
                }, 1500L)
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(btn(
            if (appLinked || presenceReady) "Hubungkan ulang Discord (browser)" else "Hubungkan Discord (browser)",
            false
        ).apply {
            setOnClickListener {
                val ok = NaoDiscordManager.beginLogin(this@MainActivity)
                if (!ok) {
                    Toast.makeText(this@MainActivity, NaoLang.t("Tidak bisa membuka browser."), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, NaoLang.t("Membuka browser untuk otorisasi Discord…"), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        if (presenceReady || appLinked) {
            panel.addView(btn("Putuskan semua koneksi Discord", false).apply {
                setOnClickListener {
                    NaoDiscordManager.disconnect(this@MainActivity)
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, NaoLang.t("Discord diputuskan."), Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })
        }

        panel.addView(btn("Buka Website Nao Music ↗", false).apply {
            setOnClickListener { NaoDiscordManager.openWebsite(this@MainActivity) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(8)) })

        panel.addView(Button(this).apply {
            text = NaoLang.t("Tutup")
            setTextColor(this@MainActivity.text)
            background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(24, 29, 40)), 18, true)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(12), 0, 0) })

        dialog.setContentView(NaoUiCompat.scrollable(panel))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun saveMusicSessionState(sync: Boolean = false) {
        // Never overwrite a stored session with an empty one before the saved
        // session has been read back (cold start, process restart by the
        // system, or task swiped away from Recents).
        if (!musicSessionRestored && musicNow == null && musicQueue.isEmpty()) return
        val q = JSONArray()
        musicQueue.forEach { t ->
            q.put(JSONObject().apply {
                put("videoId", t.videoId); put("title", t.title); put("artist", t.artist)
                put("album", t.album); put("duration", t.duration); put("thumbnail", t.thumbnail)
            })
        }
        prefs.edit()
            .putString("music_queue", q.toString())
            .putString("music_current_id", musicNow?.videoId ?: "")
            .putString("music_current_title", musicNow?.title ?: "")
            .putString("music_current_artist", musicNow?.artist ?: "")
            .putString("music_current_album", musicNow?.album ?: "")
            .putString("music_current_thumb", musicNow?.thumbnail ?: "")
            .putString("music_previous_stack", JSONArray().apply {
                musicPreviousStack.forEach { t ->
                    put(JSONObject().apply {
                        put("videoId", t.videoId); put("title", t.title); put("artist", t.artist)
                        put("album", t.album); put("duration", t.duration); put("thumbnail", t.thumbnail)
                    })
                }
            }.toString())
            .putLong("music_current_position", currentPlaybackPositionSafe())
            .putBoolean("music_was_playing", isPlayingSafe() || musicRestoreWasPlaying)
            .putBoolean("music_shuffle", musicShuffleEnabled)
            .putInt("music_repeat_mode", musicRepeatMode)
            .putString("music_repeat_pool", JSONArray().apply {
                musicRepeatPool.forEach { t ->
                    put(JSONObject().apply {
                        put("videoId", t.videoId); put("title", t.title); put("artist", t.artist)
                        put("album", t.album); put("duration", t.duration); put("thumbnail", t.thumbnail)
                    })
                }
            }.toString())
            .let { if (sync) it.commit() else it.apply() }
    }

    /** Reading a released MediaController throws; treat it as stopped. */
    private fun isPlayingSafe(): Boolean =
        try { musicPlayer?.isPlaying == true } catch (_: Exception) { false }

    private fun currentPlaybackPositionSafe(): Long =
        try { musicPlayer?.currentPosition ?: musicRestorePosition } catch (_: Exception) { musicRestorePosition }

    private fun restoreMusicSessionState() {
        if (musicSessionRestored) return
        musicSessionRestored = true
        if (musicQueue.isNotEmpty() || musicNow != null) return
        try {
            val q = JSONArray(prefs.getString("music_queue", "[]") ?: "[]")
            for (i in 0 until q.length()) {
                val o = q.getJSONObject(i)
                musicQueue.add(InnerTubeClient.Track(
                    o.optString("videoId"), o.optString("title"), o.optString("artist"),
                    o.optString("album"), o.optString("duration"), o.optString("thumbnail")
                ))
            }
            musicRestorePosition = prefs.getLong("music_current_position", 0L)
            musicRestoreWasPlaying = prefs.getBoolean("music_was_playing", false)
            musicShuffleEnabled = prefs.getBoolean("music_shuffle", false)
            musicRepeatMode = prefs.getInt("music_repeat_mode", 0).coerceIn(0, 2)
            try {
                val previousArray = JSONArray(prefs.getString("music_previous_stack", "[]") ?: "[]")
                for (i in 0 until previousArray.length()) {
                    val o = previousArray.getJSONObject(i)
                    musicPreviousStack.add(InnerTubeClient.Track(
                        o.optString("videoId"), o.optString("title"), o.optString("artist"),
                        o.optString("album"), o.optString("duration"), o.optString("thumbnail")
                    ))
                }
            } catch (_: Exception) {}
            try {
                val repeatArray = JSONArray(prefs.getString("music_repeat_pool", "[]") ?: "[]")
                for (i in 0 until repeatArray.length()) {
                    val o = repeatArray.getJSONObject(i)
                    musicRepeatPool.add(InnerTubeClient.Track(
                        o.optString("videoId"), o.optString("title"), o.optString("artist"),
                        o.optString("album"), o.optString("duration"), o.optString("thumbnail")
                    ))
                }
            } catch (_: Exception) {}
            val id = prefs.getString("music_current_id", "") ?: ""
            if (id.isNotBlank()) {
                musicNow = InnerTubeClient.Track(
                    id,
                    prefs.getString("music_current_title", "Nao Music") ?: "Nao Music",
                    prefs.getString("music_current_artist", "") ?: "",
                    prefs.getString("music_current_album", "") ?: "",
                    "", prefs.getString("music_current_thumb", "") ?: ""
                )
                musicNow?.let {
                    // TIDAK memanggil showMusicMini() di sini lagi — itu
                    // cuma data cache dari SharedPreferences, belum tentu
                    // service musiknya masih hidup. resumeMusicOnReopen()
                    // (dipanggil dari onCreate) yang akan memverifikasi ke
                    // service dulu baru menampilkan/menyembunyikan mini
                    // player sesuai kondisi sebenarnya.
                    musicMiniPlay?.setImageResource(
                        if (musicRestoreWasPlaying) R.drawable.nao_ic_pause else R.drawable.nao_ic_play
                    )
                    musicSheetPlay?.setImageResource(
                        if (musicRestoreWasPlaying) R.drawable.nao_ic_pause else R.drawable.nao_ic_play
                    )
                }
                renderExpandedQueue()
                updateMusicModeControls()
            }
        } catch (_: Exception) {}
    }

    private fun startMusicKeepAlive() {
        try {
            val i = Intent(this, NaoMusicPlaybackService::class.java)
            // Android 8 mewajibkan startForeground() dalam 5 detik setelah
            // startForegroundService(). Media3 sendiri yang mempromosikan
            // service ke foreground saat notifikasi media siap, jadi di sini
            // cukup startService biasa supaya tidak terjadi force close.
            startService(i)
        } catch (_: Exception) {}
    }

    private fun stopMusicKeepAlive() {
        try { stopService(Intent(this, NaoMusicPlaybackService::class.java)) } catch (_: Exception) {}
    }

    override fun onStop() {
        // commit() so the session survives the process being killed while the
        // app sits in the background or is swiped away from Recents.
        saveMusicSessionState(sync = true)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Preserve the exact workspace when the user leaves for Home/Recents.
        // The page is restored by preference if Android recreates the process.
        saveMusicSessionState(sync = true)
        prefs.edit()
            .putString("last_nav", selectedNav)
            .apply { if (currentTool != null) putString("last_tool", currentTool!!) else remove("last_tool") }
            .apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(NaoDiscordManager.EXTRA_AUTH_RESULT)) {
            val success = intent.getBooleanExtra(NaoDiscordManager.EXTRA_AUTH_RESULT, false)
            if (success) {
                NaoDiscordManager.setRichPresenceEnabled(this, true)
                activateRichPresenceNow()
                val name = NaoDiscordManager.accountName(this)
                Toast.makeText(
                    this,
                    if (name.isBlank())
                        "Discord terhubung · putar lagu agar Listening muncul."
                    else
                        "Discord terhubung sebagai $name · putar lagu agar Listening muncul.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val error = intent.getStringExtra(NaoDiscordManager.EXTRA_AUTH_ERROR)
                    ?: "Discord OAuth gagal."
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
        // Lock screen / headset skip commands arrive here while the app is
        // already running, so the queue must react to them too.
        handleMusicTransportIntent(intent)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (processing.get()) {
            Toast.makeText(this, NaoLang.t("Tunggu proses selesai."), Toast.LENGTH_SHORT).show()
            return
        }
        if (musicPlayerSheet?.visibility == View.VISIBLE) {
            hideMusicPlayerSheet()
            return
        }
        if (selectedNav == "Music" && musicSubPage) {
            music()
            return
        }
        // System Back is navigation inside Nao MD, never an app-exit action
        // while the user is inside a feature or sub-page.
        if (currentTool != null) {
            currentTool = null
            prefs.edit().remove("last_tool").putString("last_nav", "Tools").apply()
            tools()
            return
        }
        if (selectedNav != "Home") {
            home()
            return
        }
        // On the Home root, keep the task alive instead of resetting/exiting.
        moveTaskToBack(true)
    }

    // ========================================================= QUEUE PAGE

    /**
     * Halaman antrian tersendiri. Dulu antrian dirender langsung di dalam
     * full player (queueCard), tapi kalau lagunya banyak, sheet jadi
     * kepanjangan dan kontennya bentrok dengan navigation/gesture bar
     * sistem. Sekarang full player cuma punya tombol "Antrian" yang
     * membuka halaman ini secara terpisah.
     */
    private fun musicQueuePage() {
        musicSubPage = true
        setNavSelected("Music")
        clear()
        title("NAO MUSIC", "Antrian", "Urutan lagu berikutnya")

        content.addView(btn("‹  Kembali ke Music").apply {
            setOnClickListener { music() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(12)) })

        renderMusicQueue(content)
    }

    // ======================================================= PLAYLIST PAGE

    /** Halaman daftar playlist, dibuka dari tombol Playlist di kanan Library. */
    private fun musicPlaylistsPage() {
        musicSubPage = true
        setNavSelected("Music")
        clear()
        title("NAO MUSIC", "Playlist", "Semua playlist yang kamu buat")

        content.addView(btn("‹  Kembali ke Music").apply {
            setOnClickListener { music() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(12)) })

        val data = musicPlaylists()
        val names = data.keys().asSequence().toList().sorted()
        if (names.isEmpty()) {
            content.addView(tv("Belum ada playlist. Tekan lama / buka menu lagu lalu pilih \u201cTaruh ke Playlist\u201d.", 13f).apply {
                setTextColor(muted); setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        names.forEach { name ->
            val list = data.optJSONArray(name) ?: JSONArray()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 20, true)
                setOnClickListener { openPlaylist(name) }
            }
            row.addView(tv("\u2630", 20f, true).apply {
                gravity = Gravity.CENTER; setTextColor(accentOnCard)
            }, LinearLayout.LayoutParams(dp(40), dp(46)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            meta.addView(tv(name, 15f, true))
            meta.addView(tv("${list.length()} lagu", 11f).apply { setTextColor(muted) })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("\u203A", 22f, true).apply {
                gravity = Gravity.CENTER; setTextColor(muted)
            }, LinearLayout.LayoutParams(dp(34), dp(46)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
    }

    private fun openPlaylist(name: String) {
        musicSubPage = true
        setNavSelected("Music")
        clear()
        title("PLAYLIST", name, "Ketuk lagu untuk memutar")

        content.addView(btn("\u2039  Semua playlist").apply {
            setOnClickListener { musicPlaylistsPage() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, 0, 0, dp(8)) })

        val data = musicPlaylists()
        val list = data.optJSONArray(name) ?: JSONArray()

        content.addView(btn("\u25B6  Putar playlist ini", true).apply {
            setOnClickListener { playPlaylist(name) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) })
        if (list.length() == 0) {
            content.addView(tv("Playlist ini masih kosong.", 13f).apply { setTextColor(muted) })
        }
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val track = InnerTubeClient.Track(
                videoId = o.optString("videoId"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                thumbnail = o.optString("thumbnail")
            )
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(9))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 20, true)
                setOnClickListener { playRemote(track) }
            }
            val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            applyMusicThumbnailShape(art, 12)
            if (track.thumbnail.isNotBlank()) loadMusicArtwork(art, track.thumbnail)
            row.addView(art, LinearLayout.LayoutParams(dp(46), dp(46)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
            meta.addView(tv(track.title, 13f, true).apply { maxLines = 1 })
            meta.addView(tv(track.artist.ifBlank { "YouTube Music" }, 10f).apply { setTextColor(muted); maxLines = 1 })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("\u00D7", 20f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(muted)
                setOnClickListener {
                    val fresh = musicPlaylists()
                    val arr = fresh.optJSONArray(name) ?: JSONArray()
                    val out = JSONArray()
                    for (j in 0 until arr.length()) if (j != i) out.put(arr.get(j))
                    if (out.length() == 0) fresh.remove(name) else fresh.put(name, out)
                    saveMusicPlaylists(fresh)
                    openPlaylist(name)
                }
            }, LinearLayout.LayoutParams(dp(38), dp(46)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        }

        content.addView(btn("Hapus playlist ini").apply {
            setOnClickListener {
                val fresh = musicPlaylists()
                fresh.remove(name)
                saveMusicPlaylists(fresh)
                Toast.makeText(this@MainActivity, NaoLang.t("Playlist \"$name\" dihapus."), Toast.LENGTH_SHORT).show()
                musicPlaylistsPage()
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(14), 0, 0) })
    }

    /** Ambil semua lagu dalam sebuah playlist. */
    private fun playlistTracks(name: String): List<InnerTubeClient.Track> {
        val arr = musicPlaylists().optJSONArray(name) ?: JSONArray()
        val out = ArrayList<InnerTubeClient.Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("videoId").isBlank()) continue
            out.add(
                InnerTubeClient.Track(
                    videoId = o.optString("videoId"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    thumbnail = o.optString("thumbnail")
                )
            )
        }
        return out
    }

    /**
     * Putar seluruh isi playlist secara berurutan. Tidak ada rekomendasi
     * otomatis dan tidak ada pengulangan: begitu lagu terakhir habis,
     * pemutaran berhenti.
     */
    private fun playPlaylist(name: String) {
        val tracks = playlistTracks(name)
        if (tracks.isEmpty()) {
            Toast.makeText(this, NaoLang.t("Playlist ini masih kosong."), Toast.LENGTH_SHORT).show()
            return
        }
        musicPlaylistOnlyMode = true
        musicQueue.clear()
        musicRepeatPool.clear()
        musicQueueOrder = ArrayList(tracks.drop(1))
        musicQueue.addAll(if (musicShuffleEnabled) tracks.drop(1).shuffled() else tracks.drop(1))
        updateMusicModeControls()
        Toast.makeText(this, NaoLang.t("Memutar playlist \"$name\" \u00b7 ${tracks.size} lagu"), Toast.LENGTH_SHORT).show()
        playRemote(tracks.first())
        renderExpandedQueue()
    }

    // ========================================================= BERKAS PAGE

    private var berkasList: LinearLayout? = null
    private var berkasSavedList: LinearLayout? = null
    private var berkasSavedLoading = false
    private data class SavedMedia(
        val name: String,
        val uri: Uri,
        val size: Long,
        val date: Long,
        val mime: String
    )
    private var berkasRefresh: Runnable? = null
    private var savedMediaSignature: String = ""

    /** Halaman "Berkas": manajer unduhan ala browser (progres, jeda, lanjut, hapus). */
    private fun berkas() {
        currentTool = null
        musicSubPage = false
        setNavSelected("Berkas")
        clear()
        NaoDownloadCenter.restore(this)
        title("BERKAS", "Cek Download", "Pantau progres unduhan, jeda, lanjutkan, atau hapus media.")

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("Buka folder Download").apply {
            setOnClickListener {
                try {
                    startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, NaoLang.t("Tidak ada aplikasi berkas."), Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        berkasList = list
        content.addView(list)

        content.addView(tv("MEDIA TERSIMPAN", 12f, true).apply {
            letterSpacing = .12f
            setPadding(dp(2), dp(20), 0, dp(4))
        })
        content.addView(tv("Semua berkas yang pernah kamu unduh atau simpan lewat Nao MD \u2014 dari fitur mana pun.", 11f).apply {
            setTextColor(muted); setPadding(dp(2), 0, 0, dp(10))
        })
        val saved = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        berkasSavedList = saved
        content.addView(saved)
        savedMediaSignature = ""
        loadSavedMedia()

        renderBerkas()

        // Refresh berkala supaya bar progres terlihat halus seperti di browser.
        berkasRefresh?.let { root.removeCallbacks(it) }
        val ticker = object : Runnable {
            private var tick = 0
            override fun run() {
                if (selectedNav != "Berkas") return
                renderBerkas()
                // Auto-refresh daftar media: berkas baru langsung muncul dan
                // berkas yang dihapus (dari mana pun) langsung hilang.
                tick++
                if (tick % 3 == 0) loadSavedMedia(silent = true)
                root.postDelayed(this, 700L)
            }
        }
        berkasRefresh = ticker
        root.postDelayed(ticker, 700L)
    }

    private fun berkasStateLabel(job: NaoDownloadCenter.Job): String = when (job.state) {
        NaoDownloadCenter.State.QUEUED -> "Menunggu antrean\u2026"
        NaoDownloadCenter.State.RUNNING -> {
            val eta = job.etaSeconds
            val speed = if (job.speedBps > 0) formatBytes(job.speedBps) + "/s" else "\u2026"
            val sisa = when {
                eta < 0 -> ""
                eta >= 60 -> " \u2022 sisa ${eta / 60}m ${eta % 60}d"
                else -> " \u2022 sisa ${eta}d"
            }
            "$speed$sisa"
        }
        NaoDownloadCenter.State.PAUSED -> "Dijeda"
        NaoDownloadCenter.State.DONE ->
            if (job.type == "apk") "Selesai \u2022 ketuk \u201cBuka\u201d untuk pasang" else "Selesai \u2022 Download/NAO"
        NaoDownloadCenter.State.FAILED -> "Gagal: ${job.error}"
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, if (v >= 10 || i == 0) "%.0f %s" else "%.1f %s", v, units[i])
    }

    private fun renderBerkas() {
        val list = berkasList ?: return
        list.removeAllViews()
        val jobs = NaoDownloadCenter.jobs()
        if (jobs.isEmpty()) {
            list.addView(tv("Belum ada unduhan aktif. Semua media yang kamu unduh atau simpan lewat Nao MD tetap tercatat di bagian \u201cMedia tersimpan\u201d di bawah.", 13f).apply {
                setTextColor(muted); setLineSpacing(0f, 1.25f); setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        jobs.forEach { job ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 20, true)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(when (job.type) { "audio" -> "\u266B"; "image" -> "\u25A3"; "apk" -> "\u2B07"; else -> "\u25B6" }, 18f, true).apply {
                gravity = Gravity.CENTER; setTextColor(accentOnCard)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(4), 0) }
            meta.addView(tv(job.filename, 13f, true).apply { maxLines = 1 })
            val sizeText = if (job.total > 0) "${formatBytes(job.done)} / ${formatBytes(job.total)}" else formatBytes(job.done)
            meta.addView(tv("$sizeText \u2022 ${berkasStateLabel(job)}", 10f).apply { setTextColor(muted); maxLines = 2 })
            head.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv(if (job.total > 0 || job.state == NaoDownloadCenter.State.DONE) "${job.percent}%" else "\u2022", 12f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(if (job.state == NaoDownloadCenter.State.FAILED) Color.rgb(240, 120, 120) else this@MainActivity.text)
            }, LinearLayout.LayoutParams(dp(48), dp(32)))
            box.addView(head)

            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = job.state == NaoDownloadCenter.State.RUNNING && job.total <= 0
                progress = job.percent
                progressTintList = android.content.res.ColorStateList.valueOf(
                    when (job.state) {
                        NaoDownloadCenter.State.DONE -> Color.rgb(121, 227, 167)
                        NaoDownloadCenter.State.FAILED -> Color.rgb(240, 120, 120)
                        NaoDownloadCenter.State.PAUSED -> Color.rgb(230, 190, 100)
                        else -> purple
                    }
                )
            }
            box.addView(bar, LinearLayout.LayoutParams(-1, dp(8)).apply { setMargins(0, dp(10), 0, dp(10)) })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (job.state) {
                NaoDownloadCenter.State.RUNNING, NaoDownloadCenter.State.QUEUED ->
                    row.addView(btn("\u23F8  Jeda").apply {
                        setOnClickListener { NaoDownloadCenter.pause(this@MainActivity, job); renderBerkas() }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                NaoDownloadCenter.State.PAUSED, NaoDownloadCenter.State.FAILED ->
                    row.addView(btn(if (job.state == NaoDownloadCenter.State.FAILED) "\u21BB  Coba lagi" else "\u25B6  Lanjutkan", true).apply {
                        setOnClickListener { NaoDownloadCenter.resume(this@MainActivity, job); renderBerkas() }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                NaoDownloadCenter.State.DONE ->
                    row.addView(btn("Buka").apply {
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(job.savedUri), NaoDownloadCenter.mimeFor(job.filename))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, NaoLang.t("Tidak ada aplikasi untuk membuka berkas ini."), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            row.addView(btn("\uD83D\uDDD1  Hapus").apply {
                setOnClickListener {
                    NaoDownloadCenter.remove(this@MainActivity, job)
                    renderBerkas()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(8), 0, 0, 0) })
            box.addView(row)

            list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })
        }
    }


    /**
     * Kumpulkan seluruh berkas yang pernah disimpan aplikasi ini: hasil
     * Downloader, "download semua media", AI Upscaler, audio Nao Music,
     * atau apa pun yang tersimpan lewat Nao MD. Patokannya folder
     * penyimpanan, bukan fitur asalnya.
     */
    private fun loadSavedMedia(silent: Boolean = false) {
        if (berkasSavedLoading) return
        berkasSavedLoading = true
        if (!silent) {
            berkasSavedList?.removeAllViews()
            berkasSavedList?.addView(tv("Memindai berkas\u2026", 12f).apply { setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) })
        }
        exec.execute {
            val items = ArrayList<SavedMedia>()
            try { items.addAll(scanMediaStoreDownloads()) } catch (_: Exception) {}
            try { items.addAll(scanLocalMediaDirs()) } catch (_: Exception) {}
            val unique = items
                .distinctBy { it.name.lowercase() + "|" + it.size }
                .sortedByDescending { it.date }
            val signature = unique.joinToString("|") { it.name + ":" + it.size }
            runOnUiThread {
                berkasSavedLoading = false
                if (selectedNav != "Berkas") return@runOnUiThread
                // Hanya gambar ulang kalau isinya benar-benar berubah supaya
                // daftar tidak berkedip saat auto-refresh.
                if (silent && signature == savedMediaSignature) return@runOnUiThread
                savedMediaSignature = signature
                renderSavedMedia(unique)
            }
        }
    }

    private fun scanMediaStoreDownloads(): List<SavedMedia> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = ArrayList<SavedMedia>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE
        )
        contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            while (c.moveToNext() && out.size < 400) {
                val name = c.getString(nameIdx) ?: continue
                out.add(
                    SavedMedia(
                        name = name,
                        uri = ContentUris.withAppendedId(collection, c.getLong(idIdx)),
                        size = c.getLong(sizeIdx),
                        date = c.getLong(dateIdx) * 1000L,
                        mime = c.getString(mimeIdx) ?: NaoDownloadCenter.mimeFor(name)
                    )
                )
            }
        }
        return out
    }

    private fun scanLocalMediaDirs(): List<SavedMedia> {
        val out = ArrayList<SavedMedia>()
        val roots = ArrayList<File>()
        try { getExternalFilesDir(null)?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            roots.add(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
            )
        } catch (_: Exception) {}
        roots.forEach { root -> collectFiles(root, out, 0) }
        return out
    }

    private fun collectFiles(dir: File, out: ArrayList<SavedMedia>, depth: Int) {
        if (depth > 3 || out.size > 400 || !dir.isDirectory) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        children.forEach { f ->
            if (f.isDirectory) {
                collectFiles(f, out, depth + 1)
            } else if (f.length() > 0 && !f.name.endsWith(".part")) {
                out.add(
                    SavedMedia(
                        name = f.name,
                        uri = shareableUri(f),
                        size = f.length(),
                        date = f.lastModified(),
                        mime = NaoDownloadCenter.mimeFor(f.name)
                    )
                )
            }
        }
    }

    /** file:// tidak boleh dibagikan ke aplikasi lain sejak Android N. */
    private fun shareableUri(file: File): Uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            this, "com.nao.md.project.fileprovider", file
        )
    } catch (_: Exception) {
        Uri.fromFile(file)
    }

    private fun renderSavedMedia(items: List<SavedMedia>) {
        val box = berkasSavedList ?: return
        box.removeAllViews()
        if (items.isEmpty()) {
            box.addView(tv("Belum ada berkas tersimpan.", 12f).apply {
                setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }
        items.take(200).forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(10), dp(11))
                background = rounded(NaoThemeManager.tintBox(this@MainActivity, Color.rgb(14, 18, 27)), 20, true)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, item.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, NaoLang.t("Tidak ada aplikasi untuk membuka berkas ini."), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val icon = when {
                item.mime.startsWith("audio") -> "\u266B"
                item.mime.startsWith("image") -> "\u25A3"
                item.mime.startsWith("video") -> "\u25B6"
                else -> "\u25C6"
            }
            row.addView(tv(icon, 17f, true).apply {
                gravity = Gravity.CENTER; setTextColor(accentOnCard)
            }, LinearLayout.LayoutParams(dp(32), dp(38)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0) }
            meta.addView(tv(item.name, 12f, true).apply { maxLines = 1 })
            meta.addView(tv(formatBytes(item.size), 10f).apply { setTextColor(muted) })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("Bagikan", 10f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(accentOnCard)
                setOnClickListener {
                    try {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = item.mime
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Bagikan berkas"
                            )
                        )
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, NaoLang.t("Berkas tidak bisa dibagikan."), Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(dp(58), dp(38)))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        musicProgressRunnable?.let { musicMiniProgress?.removeCallbacks(it) }
        musicMiniVideo?.stopPlayback()
        musicSleepTimer?.cancel()
        // Save before releasing the controller: a released controller can no
        // longer report its position, which used to store 0 / an empty state.
        saveMusicSessionState(sync = true)
        try { (musicPlayer as? MediaController)?.release() } catch (_: Exception) {}
        naoVideoPages?.release()
        naoVideoPages = null
        exec.shutdownNow()
        super.onDestroy()
    }
}

private object OkHttpClientProvider {
    val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()
}

