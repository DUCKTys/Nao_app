package com.nao.md.project.video

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nao.md.project.NaoLang
import com.nao.md.project.NaoThemeManager
import java.util.Calendar
import java.util.HashMap
import java.util.concurrent.ExecutorService

/**
 * Shell halaman Video Nao MD berbasis Invidious.
 *
 * Dibangun penuh dengan Android Views (tanpa WebView, tanpa Compose) sehingga
 * menyatu dengan MainActivity yang ada. Aliran:
 *   cari/trending -> daftar video -> detail -> pemutar ExoPlayer di tempat.
 *
 * Pemutar memakai media3-exoplayer dengan TextureView, data source
 * DefaultHttpDataSource yang konsisten dengan User-Agent stream yang dipakai
 * Nao Music, supaya URL googlevideo hasil NewPipe benar-benar bisa diputar.
 */
class NaoVideoPages(
    private val activity: Activity,
    private val api: InvidiousApi,
    private val exec: ExecutorService,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun loadThumb(view: ImageView, url: String, videoId: String)
        fun applyThumb(view: ImageView, radiusDp: Int)
        fun showSettings()
        fun openInBrowser(videoId: String)
    }

    private val purple: Int get() = Color.rgb(255, 0, 0)
    private val bg = Color.rgb(15, 15, 15)
    private val surface = Color.rgb(39, 39, 39)
    private val text = Color.rgb(241, 241, 241)
    private val muted = Color.rgb(170, 170, 170)
    private val chipOff = Color.rgb(39, 39, 39)

    private val categories = listOf("Semua" to "", "Musik" to "music", "Game" to "gaming", "Film" to "movies", "Berita" to "news")

    private var list: LinearLayout? = null
    private var searchField: EditText? = null
    private var statusText: TextView? = null
    private var chipViews = HashMap<String, TextView>()
    @Volatile
    private var generation = 0L
    private var currentCategory = ""

    // Pemutar
    private var player: ExoPlayer? = null
    private var texture: TextureView = TextureView(activity)
    private var playerTitle: TextView? = null
    private var playerMeta: TextView? = null
    private var playBtn: TextView? = null
    private var seekBar: SeekBar? = null
    private var elapsedTv: TextView? = null
    private var durationTv: TextView? = null
    private var buffering: ProgressBar? = null
    private var loadingTv: TextView? = null
    private var errorTv: TextView? = null
    private var playerSource: TextView? = null
    private var activeVideoId: String = ""
    private val pendingStreams = ArrayList<InvidiousApi.Stream>()
    private var triedYoutubeFallback = false
    private var userSeeking = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var released = false

    // Fullscreen & quality
    private var isFullscreen = false
    private var isTheaterMode = false
    private var fullscreenBtn: TextView? = null
    private var theaterModeBtn: TextView? = null
    private var qualityBtn: TextView? = null
    private var speedBtn: TextView? = null
    private var captionsBtn: TextView? = null
    private var autoplayBtn: TextView? = null
    private var availableStreams = ArrayList<InvidiousApi.Stream>()
    private var currentStreamIndex = 0
    private var playbackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    private var currentSpeedIndex = 3 // 1.0x
    private var channelAvatar: ImageView? = null
    private var channelName: TextView? = null
    private var channelSubs: TextView? = null
    private var channelVerified: TextView? = null
    private var subscribeBtn: TextView? = null
    private var joinBtn: TextView? = null
    private var likeBtn: LinearLayout? = null
    private var dislikeBtn: LinearLayout? = null
    private var shareBtn: LinearLayout? = null
    private var saveBtn: LinearLayout? = null
    private var clipBtn: LinearLayout? = null
    private var videoDescription: TextView? = null
    private var isDescriptionExpanded = false
    private var chaptersContainer: LinearLayout? = null
    private var videoChapters = ArrayList<Chapter>()
    private var isMiniPlayer = false
    private var miniPlayerContainer: FrameLayout? = null
    private val dpCache = mutableMapOf<Int, Int>()

    data class Chapter(
        val title: String,
        val startTime: Long, // in seconds
        val endTime: Long
    )

    private fun dp(v: Int) = dpCache.getOrPut(v) { (v * activity.resources.displayMetrics.density).toInt() }

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false) = TextView(activity).apply {
        text = NaoLang.t(s)
        setTextColor(this@NaoVideoPages.text)
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun tvPrimary(s: String, size: Float = 14f, bold: Boolean = false) = TextView(activity).apply {
        text = NaoLang.t(s)
        setTextColor(this@NaoVideoPages.text)
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun tvMuted(s: String, size: Float = 12f) = TextView(activity).apply {
        text = NaoLang.t(s)
        setTextColor(muted)
        textSize = size
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), Color.argb(28, 255, 255, 255))
    }

    private fun roundedStroke(color: Int, radius: Int, strokeColor: Int = Color.argb(28, 255, 255, 255)) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), strokeColor)
    }

    fun attach(parent: LinearLayout) {
        released = false
        parent.removeAllViews()
        parent.setBackgroundColor(bg)
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }

        page.addView(buildGreeting())
        page.addView(buildSearch(), LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(0, 0, 0, dp(10))
        })
        page.addView(buildChips(), LinearLayout.LayoutParams(-1, dp(42)).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        list = body
        page.addView(body)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(bg)
            addView(page)
        }
        parent.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        loadTrending("")
    }

    // ---------- Kepala halaman ----------

    private fun buildGreeting(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val mark = TextView(activity).apply {
            text = "▶"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(purple, 7, false)
            includeFontPadding = false
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(31), dp(22)))
        row.addView(tv("Nao Video", 21f, true).apply {
            setPadding(dp(8), 0, 0, 0)
            letterSpacing = -.02f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("◉", 21f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Notifikasi"
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(tv("⋮", 27f, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Pengaturan video"
            setOnClickListener { callbacks.showSettings() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun hideKeyboard(view: View) {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun buildSearch(): FrameLayout {
        val wrap = FrameLayout(activity)
        val field = EditText(activity).apply {
            hint = "Telusuri"
            setHintTextColor(muted)
            setTextColor(this@NaoVideoPages.text)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setBackgroundResource(android.R.color.transparent)
            setPadding(dp(18), dp(2), dp(52), dp(2))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString()); hideKeyboard(this); true
                } else false
            }
        }
        searchField = field
        val go = TextView(activity).apply {
            text = "⌕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(this@NaoVideoPages.text)
            background = rounded(surface, 999, false)
            isClickable = true
            contentDescription = "Cari"
            setOnClickListener { runSearch(field.text.toString()); hideKeyboard(field) }
        }
        wrap.addView(field, FrameLayout.LayoutParams(-1, -1))
        wrap.addView(go, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, dp(2), 0)
        })
        wrap.background = roundedStroke(Color.rgb(18, 18, 18), 24, Color.rgb(70, 70, 70))
        return wrap
    }

    private fun buildChips(): HorizontalScrollView {
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        categories.forEach { (label, type) ->
            val chip = TextView(activity).apply {
                text = NaoLang.t(label)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(this@NaoVideoPages.text)
                setPadding(dp(15), dp(7), dp(15), dp(7))
                background = roundedStroke(chipOff, 8, Color.rgb(58, 58, 58))
                setOnClickListener {
                    currentCategory = type
                    for ((name, view) in chipViews) {
                        val active = name == label
                        view.background = if (active) rounded(this@NaoVideoPages.text, 8, false)
                            else roundedStroke(chipOff, 8, Color.rgb(58, 58, 58))
                        view.setTextColor(if (active) bg else this@NaoVideoPages.text)
                    }
                    if (type == "") loadTrending("") else loadTrending(type)
                }
            }
            chipViews[label] = chip
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        scroll.addView(row)
        // Trending aktif saat pertama kali dibuka.
        chipViews["Semua"]?.apply {
            background = rounded(this@NaoVideoPages.text, 8, false)
            setTextColor(this@NaoVideoPages.bg)
        }
        return scroll
    }

    // ---------- Daftar hasil ----------

    private fun setStatus(message: String) {
        val target = list ?: return
        target.removeAllViews()
        val status = tv(message, 13f).apply {
            setTextColor(muted)
            setPadding(dp(4), dp(16), dp(4), dp(4))
        }
        statusText = status
        target.addView(status)
    }

    private fun loadTrending(type: String) {
        // PENTING: pre-increment. `generation++` mengembalikan nilai LAMA sehingga
        // `gen != generation` selalu true dan setiap hasil dibuang -> status
        // "Memuat…"/"Mencari…" tidak pernah berganti menjadi daftar video.
        val gen = ++generation
        setStatus("Memuat\u2026")
        exec.execute {
            var failure: String? = null
            val items = try {
                api.trending(type)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                emptyList()
            }
            if (gen != generation || released) return@execute
            activity.runOnUiThread {
                if (items.isEmpty()) setStatus(
                    "Trending tidak tersedia. Periksa instance Invidious dan koneksi internet." +
                        (failure?.let { "\n($it)" } ?: "")
                )
                else renderList(items)
            }
        }
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        setStatus("Mencari\u2026")
        val gen = ++generation // pre-increment, lihat catatan di loadTrending()
        exec.execute {
            var failure: String? = null
            val items = try {
                api.search(q)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                emptyList()
            }
            if (gen != generation || released) return@execute
            activity.runOnUiThread {
                if (items.isEmpty()) setStatus(
                    if (failure != null) "Pencarian gagal: $failure"
                    else "Tidak ada hasil untuk \u201C$q\u201D."
                )
                else renderList(items)
            }
        }
    }

    private fun renderList(items: List<InvidiousApi.VideoItem>) {
        val target = list ?: return
        target.removeAllViews()
        if (statusText != null) statusText = null
        items.forEach { card ->
            val row = videoCard(card)
            target.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(6))
            })
        }
        target.addView(tv("\u2022 Dibuat untuk kamu lewat Invidious", 10f).apply {
            setTextColor(muted); setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }

    private fun videoCard(item: InvidiousApi.VideoItem): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
            isClickable = true
            setOnClickListener { openDetail(item) }
        }
        val thumbBox = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(28, 28, 28)) }
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            callbacks.loadThumb(this, item.thumbnail, item.videoId)
            callbacks.applyThumb(this, 0)
        }
        thumbBox.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        if (item.lengthSeconds > 0L) {
            val durationBadge = tv(formatDuration(item.lengthSeconds), 11f, true).apply {
                setTextColor(Color.WHITE)
                background = rounded(Color.argb(205, 0, 0, 0), 4, false)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }
            thumbBox.addView(durationBadge, FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(5), dp(5))
            })
        }
        card.addView(thumbBox, LinearLayout.LayoutParams(-1, dp(203)))

        val infoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(2), dp(12), 0, 0)
        }
        val avatar = TextView(activity).apply {
            text = item.author.trim().take(1).uppercase().ifBlank { "N" }
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(70, 70, 70), 999, false)
        }
        infoRow.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))
        val meta = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        meta.addView(tv(item.title, 15f, true).apply { maxLines = 2 })
        val stats = ArrayList<String>()
        if (item.author.isNotBlank()) stats.add(item.author)
        if (item.viewCount >= 0L) stats.add(formatCount(item.viewCount) + " x ditonton")
        if (item.publishedText.isNotBlank()) stats.add(item.publishedText)
        meta.addView(tv(stats.joinToString(" · "), 12f).apply {
            setTextColor(muted); maxLines = 2; setPadding(0, dp(4), 0, 0)
        })
        infoRow.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        infoRow.addView(tv("⋮", 24f, true).apply {
            gravity = Gravity.CENTER; contentDescription = "Opsi video"
        }, LinearLayout.LayoutParams(dp(36), dp(42)))
        card.addView(infoRow)
        return card
    }

    // ---------- Detail & pemutar ----------

    private fun openDetail(item: InvidiousApi.VideoItem) {
        val gen = ++generation
        activeVideoId = item.videoId
        pendingStreams.clear()
        triedYoutubeFallback = false
        pausePlayback()
        renderPlayerSection(item, gen)

        exec.execute {
            // Fetch full video detail for streams, channel info, and description
            val detail = try {
                api.video(item.videoId)
            } catch (e: Exception) {
                null
            }
            val resolved = try {
                api.streams(item.videoId)
            } catch (e: Exception) {
                emptyList()
            }
            if (gen != generation || released) return@execute
            activity.runOnUiThread {
                if (resolved.isEmpty()) {
                    showPlaybackError("Stream tidak tersedia untuk video ini.", shouldRetry = true)
                } else {
                    triedYoutubeFallback = resolved.any { it.source == "youtube" }
                    pendingStreams.clear()
                    pendingStreams.addAll(resolved.drop(1))
                    initPlayer(resolved.first())
                    playerSource?.text =
                        if (resolved.first().source == "youtube") "via YouTube" else "via Invidious"
                    // Update quality selector with all available streams
                    updateAvailableStreams(resolved)
                    // Update channel info from detail
                    detail?.let { d ->
                        channelSubs?.text = d.subCountText.ifBlank { "" }
                        if (d.video.author.isNotBlank()) {
                            loadChannelAvatar(d.video.author)
                        }
                    }
                }
            }
        }
    }

    private fun renderPlayerSection(item: InvidiousApi.VideoItem, gen: Long) {
        val target = list ?: return
        target.removeAllViews()

        target.addView(btn("‹  Kembali") {
            generation++
            stopPlayback()
            loadTrending(currentCategory)
        })

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, 0, 0, dp(10))
        }
        val stage = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            setOnClickListener {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                updatePlayUi()
            }
        }
        (texture.parent as? android.view.ViewGroup)?.removeView(texture)
        stage.addView(texture, FrameLayout.LayoutParams(-1, -1))
        buffering = ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        stage.addView(
            buffering,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
        )
        loadingTv = tv("Menyiapkan\u2026", 12f).apply {
            setTextColor(Color.rgb(210, 216, 228))
            gravity = Gravity.CENTER
        }
        stage.addView(loadingTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        errorTv = tv("", 12f).apply {
            setTextColor(Color.rgb(250, 200, 200))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        stage.addView(errorTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        card.addView(stage, LinearLayout.LayoutParams(-1, dp(218)).apply {
            setMargins(0, dp(8), 0, 0)
        })

        // Fullscreen button overlay on video
        fullscreenBtn = TextView(activity).apply {
            text = "\u2922" // ⤢ fullscreen icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(180, 0, 0, 0), 999, false)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setOnClickListener { toggleFullscreen() }
        }
        stage.addView(fullscreenBtn, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(8), dp(8), 0)
        })

        // Quality button overlay
        qualityBtn = TextView(activity).apply {
            text = "720p \u25BE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(180, 0, 0, 0), 12, false)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            setOnClickListener { showQualitySelector() }
        }
        stage.addView(qualityBtn, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply {
            setMargins(dp(8), dp(8), 0, 0)
        })

        // Kontrol: play/pause + seek + waktu + fullscreen + quality.
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        playBtn = tv("▶", 20f).apply {
            gravity = Gravity.CENTER
            setTextColor(if (purple != 0) bg else surface)
            background = rounded(Color.argb(170, 0, 0, 0), 999, false)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                updatePlayUi()
            }
        }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        elapsedTv = tv("0:00", 10f).apply { setTextColor(muted); setTextAlignment(View.TEXT_ALIGNMENT_CENTER) }
        controls.addView(elapsedTv, LinearLayout.LayoutParams(0, -2, 1.2f).apply { setMargins(dp(8), 0, dp(8), 0) })
        seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            isIndeterminate = false
            setPaddingRelative(dp(4), paddingTop, dp(4), paddingBottom)
            progressTintList = android.content.res.ColorStateList.valueOf(purple)
            thumbTintList = android.content.res.ColorStateList.valueOf(purple)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) player?.let { p ->
                        if (p.duration > 0) elapsedTv?.text = formatDuration((p.duration * progress / 1000L) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    val progress = seekBar?.progress ?: return
                    player?.let { p ->
                        if (p.duration > 0) p.seekTo(p.duration * progress / 1000L)
                    }
                }
            })
        }
        controls.addView(seekBar, LinearLayout.LayoutParams(0, -2, 2f))
        durationTv = tv("0:00", 10f).apply { setTextColor(muted); setTextAlignment(View.TEXT_ALIGNMENT_CENTER) }
        controls.addView(durationTv, LinearLayout.LayoutParams(0, -2, 1.2f).apply { setMargins(dp(8), 0, 0, 0) })

        // Fullscreen button in controls
        val fullscreenCtrl = TextView(activity).apply {
            text = "\u2922"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(this@NaoVideoPages.text)
            background = rounded(Color.TRANSPARENT, 999, false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { toggleFullscreen() }
        }
        controls.addView(fullscreenCtrl, LinearLayout.LayoutParams(dp(40), dp(40)))

        // Quality button in controls
        val qualityCtrl = TextView(activity).apply {
            text = "\u2699\uFE0F" // gear icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(this@NaoVideoPages.text)
            background = rounded(Color.TRANSPARENT, 999, false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showQualitySelector() }
        }
        controls.addView(qualityCtrl, LinearLayout.LayoutParams(dp(40), dp(40)))

        card.addView(controls, LinearLayout.LayoutParams(-1, -2))

        playerTitle = tv(item.title, 18f, true).apply {
            setPadding(dp(2), dp(14), dp(2), dp(6))
            maxLines = 2
        }
        card.addView(playerTitle)

        // Channel info section
        val channelSection = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }

        channelAvatar = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 30, 40))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
        }
        channelSection.addView(channelAvatar, LinearLayout.LayoutParams(dp(40), dp(40)))

        val channelInfo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        channelName = tv(item.author, 13f, true).apply {
            setTextColor(this@NaoVideoPages.text)
        }
        channelInfo.addView(channelName)
        channelSubs = tv("", 11f).apply {
            setTextColor(muted)
        }
        channelInfo.addView(channelSubs)
        channelSection.addView(channelInfo, LinearLayout.LayoutParams(0, -2, 1f))

        subscribeBtn = tv("Subscribe", 12f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(this@NaoVideoPages.bg)
            background = rounded(this@NaoVideoPages.text, 999, false)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { handleSubscribe() }
        }
        channelSection.addView(subscribeBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(channelSection)

        // Action buttons: like, dislike, share
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(10))
        }

        likeBtn = createActionBtn("\uD83D\uDC4D", "Suka") { handleLike() }
        actionRow.addView(likeBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { setMargins(0, 0, dp(8), 0) })

        dislikeBtn = createActionBtn("\uD83D\uDC4E", "Tidak suka") { handleDislike() }
        actionRow.addView(dislikeBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { setMargins(0, 0, dp(8), 0) })

        shareBtn = createActionBtn("\uD83D\uDCF1", "Bagikan") { handleShare(item.videoId) }
        actionRow.addView(shareBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { setMargins(0, 0, dp(8), 0) })

        // Download button
        val downloadBtn = createActionBtn("\u2B07\uFE0F", "Download") { handleDownload() }
        actionRow.addView(downloadBtn, LinearLayout.LayoutParams(-2, dp(44)))

        val actionScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(actionRow)
        }
        card.addView(actionScroll)

        val metaPart = ArrayList<String>()
        if (item.viewCount >= 0L) metaPart.add(formatCount(item.viewCount) + " x ditonton")
        if (item.publishedText.isNotBlank()) metaPart.add(item.publishedText)
        playerMeta = tv(metaPart.joinToString(" \u00B7 "), 11f).apply {
            setTextColor(muted); setPadding(dp(2), 0, dp(2), dp(8))
        }
        card.addView(playerMeta)
        playerSource = tv("via Invidious", 10f).apply {
            setTextColor(muted); setPadding(dp(2), 0, dp(2), 0)
            gravity = Gravity.END
        }
        card.addView(playerSource)
        target.addView(card, LinearLayout.LayoutParams(-1, -2))

        // Deskripsi + video terkait diisi setelah stream siap.
        val extra = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        target.addView(extra, LinearLayout.LayoutParams(-1, -2))

        exec.execute {
            val detail = try {
                api.video(item.videoId)
            } catch (e: Exception) {
                null
            }
            if (gen != generation || released) return@execute
            activity.runOnUiThread {
                addDetailExtras(extra, item, detail?.description.orEmpty(), detail)
                addRelated(extra, detail?.related.orEmpty())
            }
        }
    }

    private fun addDetailExtras(target: LinearLayout, item: InvidiousApi.VideoItem, description: String, detail: InvidiousApi.VideoDetail? = null) {
        if (description.isNotBlank()) {
            val descriptionView = tv(description.trim(), 12f).apply {
                setTextColor(this@NaoVideoPages.text)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = rounded(surface, 10, false)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            videoDescription = descriptionView
            descriptionView.setOnClickListener {
                isDescriptionExpanded = !isDescriptionExpanded
                if (isDescriptionExpanded) {
                    videoDescription?.maxLines = Int.MAX_VALUE
                    videoDescription?.ellipsize = null
                } else {
                    videoDescription?.maxLines = 3
                    videoDescription?.ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            target.addView(descriptionView)
        }

        // Update channel info from detail if available
        detail?.let { d ->
            channelSubs?.text = d.subCountText.ifBlank { "" }
            // Try to load channel avatar
            if (d.video.author.isNotBlank()) {
                // Invidious doesn't directly provide channel avatar in video detail
                // We could fetch it separately, but for now use a placeholder
                loadChannelAvatar(d.video.author)
            }
        }
    }

    private fun addRelated(target: LinearLayout, related: List<InvidiousApi.VideoItem>) {
        if (related.isEmpty()) return
        target.addView(tv("Berikutnya", 18f, true).apply {
            setPadding(dp(2), dp(20), 0, dp(12))
        })
        related.take(10).forEach { item ->
            val row = videoCard(item)
            target.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(6))
            })
        }
    }

    // ---------- Pemutar ----------

    private fun initPlayer(stream: InvidiousApi.Stream) {
        // URL googlevideo (hasil NewPipe) butuh UA klien YouTube; URL yang diproksikan
        // instance Invidious memakai UA non-browser yang sama dengan panggilan API
        // supaya tidak terkena tantangan anti-bot.
        val userAgent = if (stream.url.contains("googlevideo.com"))
            com.nao.md.project.music.InnerTubeClient.STREAM_USER_AGENT
        else InvidiousApi.API_USER_AGENT
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(activity)
            .setDataSourceFactory(httpDataSourceFactory)
        val newPlayer = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(
                DefaultRenderersFactory(activity)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            )
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true
                )
                setHandleAudioBecomingNoisy(true)
            }

        newPlayer.setVideoTextureView(texture)
        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        loadingTv?.visibility = View.GONE
                        if (newPlayer.duration > 0L) {
                            durationTv?.text = formatDuration(newPlayer.duration / 1000L)
                            buffering?.visibility = View.GONE
                        }
                        updatePlayUi()
                    }
                    Player.STATE_BUFFERING -> {
                        buffering?.visibility = View.VISIBLE
                        playBtn?.text = "\u2026"
                    }
                    Player.STATE_ENDED -> {
                        playBtn?.text = "\u25B6"
                        buffering?.visibility = View.GONE
                        updatePlaybackPosition(0L)
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (newPlayer == player) {
                    if (isPlaying) buffering?.visibility = View.GONE
                    updatePlayUi()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (newPlayer == player) handlePlayerError(error)
            }
        })

        val old = player
        player = newPlayer
        stopTicker()
        newPlayer.setMediaItem(MediaItem.fromUri(stream.url))
        newPlayer.prepare()
        newPlayer.play()
        updatePlayUi()
        startedTicker()
        old?.let { runCatching { it.release() } }

        loadingTv?.text = "Menyiapkan stream\u2026"
    }

    /**
     * Stream gagal diputar (mis. 403 karena URL terikat IP, atau instance
     * memblokir): coba kandidat berikutnya, lalu jalur cadangan NewPipe, dan
     * baru menyerah dengan pesan bila semuanya gagal.
     */
    private fun handlePlayerError(error: PlaybackException) {
        val next = if (pendingStreams.isNotEmpty()) pendingStreams.removeAt(0) else null
        if (next != null) {
            loadingTv?.apply { text = "Mencoba stream lain\u2026"; visibility = View.VISIBLE }
            initPlayer(next)
            return
        }
        val id = activeVideoId
        if (!triedYoutubeFallback && id.isNotBlank()) {
            triedYoutubeFallback = true
            val gen = generation
            loadingTv?.apply { text = "Mencoba jalur cadangan\u2026"; visibility = View.VISIBLE }
            exec.execute {
                val alt = try {
                    api.resolveViaYoutube(id)
                } catch (e: Exception) {
                    emptyList()
                }
                if (gen != generation || released) return@execute
                activity.runOnUiThread {
                    if (alt.isEmpty()) {
                        showPlaybackError("Gagal memutar: ${error.errorCodeName}", shouldRetry = true)
                    } else {
                        pendingStreams.clear()
                        pendingStreams.addAll(alt.drop(1))
                        initPlayer(alt.first())
                        playerSource?.text = "via YouTube"
                    }
                }
            }
            return
        }
        stopPlayback()
        showPlaybackError("Gagal memutar: ${error.errorCodeName}", shouldRetry = true)
    }

    private fun updatePlaybackPosition(positionMs: Long) {
        if (!userSeeking) {
            seekBar?.let { sb ->
                player?.let { p ->
                    if (p.duration > 0) sb.progress = (positionMs * 1000L / p.duration).toInt()
                }
            }
        }
        elapsedTv?.text = formatDuration(positionMs / 1000L)
    }

    private fun startedTicker() {
        stopTicker()
        val run = object : Runnable {
            override fun run() {
                if (player?.playbackState == Player.STATE_READY) {
                    updatePlaybackPosition(player?.currentPosition ?: 0L)
                }
                mainHandler.postDelayed(this, 400L)
            }
        }
        ticker = run
        mainHandler.post(run)
    }

    private fun stopTicker() {
        ticker?.let { mainHandler.removeCallbacks(it) }
        ticker = null
    }

    private fun updatePlayUi() {
        val p = player ?: return
        playBtn?.text = if (p.isPlaying) "Ⅱ" else "\u25B6"
    }

    fun pausePlayback() {
        player?.let { if (it.isPlaying) it.pause() }
    }

    fun stopPlayback() {
        stopTicker()
        player?.let {
            runCatching { it.stop() }
            runCatching { it.clearMediaItems() }
            runCatching { it.release() }
        }
        player = null
        playBtn?.text = "\u25B6"
        loadingTv?.text = "Siap memutar"
        buffering?.visibility = View.GONE
        errorTv?.visibility = View.GONE
    }

    /** Dipanggil saat tab Video ditinggalkan / Activity ditutup. */
    fun release() {
        released = true
        stopTicker()
        player?.let { runCatching { it.release() } }
        player = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ---------- Fullscreen & Quality ----------

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val decorView = activity.window.decorView
        if (isFullscreen) {
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            fullscreenBtn?.text = "\u2921"
            fullscreenBtn?.visibility = View.GONE
            qualityBtn?.visibility = View.GONE
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            fullscreenBtn?.text = "\u2922"
            fullscreenBtn?.visibility = View.VISIBLE
            qualityBtn?.visibility = View.VISIBLE
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun showQualitySelector() {
        if (availableStreams.isEmpty()) {
            Toast.makeText(activity, "Tidak ada pilihan kualitas", Toast.LENGTH_SHORT).show()
            return
        }
        val options = availableStreams.mapIndexed { index, stream ->
            "${stream.resolution.ifBlank { "Unknown" }} (${stream.mimeType})"
        }
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Pilih Kualitas")
        builder.setSingleChoiceItems(options.toTypedArray(), currentStreamIndex) { _, which ->
            if (which != currentStreamIndex) {
                currentStreamIndex = which
                val selectedStream = availableStreams[which]
                initPlayer(selectedStream)
                qualityBtn?.text = "${selectedStream.resolution} \u25BE"
                qualityBtn?.visibility = View.VISIBLE
            }
        }
        builder.setNegativeButton("Batal", null)
        builder.show()
    }

    private fun updateAvailableStreams(streams: List<InvidiousApi.Stream>) {
        availableStreams.clear()
        availableStreams.addAll(streams.distinctBy { it.resolution })
        if (availableStreams.isNotEmpty()) {
            qualityBtn?.visibility = View.VISIBLE
            qualityBtn?.text = "${availableStreams[0].resolution} \u25BE"
        }
    }

    // ---------- Action Buttons ----------

    private fun createActionBtn(icon: String, label: String, action: () -> Unit): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(surface, 999, false)
            setPadding(dp(13), 0, dp(13), 0)
            isClickable = true
            setOnClickListener { action() }
        }
        val btn = TextView(activity).apply {
            text = icon
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(this@NaoVideoPages.text)
        }
        val lbl = tv(label, 12f, true).apply { setPadding(dp(7), 0, 0, 0) }
        container.addView(btn, LinearLayout.LayoutParams(-2, -1))
        container.addView(lbl, LinearLayout.LayoutParams(-2, -1))
        return container
    }

    private fun handleLike() {
        likeBtn?.let { container: LinearLayout ->
            val iconView = container.getChildAt(0) as TextView
            val isLiked = iconView.tag as? Boolean == true
            iconView.tag = !isLiked
            iconView.text = if (!isLiked) "\uD83D\uDC4D" else "\uD83D\uDC4D"
            iconView.setTextColor(if (!isLiked) purple else this@NaoVideoPages.text)
            val msg = if (!isLiked) "Disukai" else "Batal suka"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDislike() {
        dislikeBtn?.let { container: LinearLayout ->
            val iconView = container.getChildAt(0) as TextView
            val isDisliked = iconView.tag as? Boolean == true
            iconView.tag = !isDisliked
            iconView.text = if (!isDisliked) "\uD83D\uDC4E" else "\uD83D\uDC4E"
            iconView.setTextColor(if (!isDisliked) purple else this@NaoVideoPages.text)
            val msg = if (!isDisliked) "Tidak disukai" else "Batal tidak suka"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleShare(videoId: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
            putExtra(Intent.EXTRA_SUBJECT, "Watch this video")
        }
        try {
            activity.startActivity(Intent.createChooser(shareIntent, "Bagikan via"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Tidak ada aplikasi untuk berbagi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDownload() {
        Toast.makeText(activity, "Fitur download belum tersedia", Toast.LENGTH_SHORT).show()
    }

    private fun handleSubscribe() {
        subscribeBtn?.apply {
            val isSubscribed = tag as? Boolean == true
            tag = !isSubscribed
            text = if (!isSubscribed) "Subscribed" else "Subscribe"
            setBackgroundColor(if (!isSubscribed) Color.rgb(100, 100, 100) else purple)
            val msg = if (!isSubscribed) "Berlangganan ke channel" else "Batalkan berlangganan"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadChannelAvatar(channelName: String) {
        channelAvatar?.apply {
            setImageDrawable(null)
            setBackgroundColor(Color.rgb(50, 50, 70))
        }
    }

    // ---------- Util ----------

    private fun showPlaybackError(message: String, shouldRetry: Boolean) {
        stopPlayback()
        errorTv?.apply {
            text = message
            visibility = View.VISIBLE
        }
        if (shouldRetry && activeVideoId.isNotBlank()) {
            playerSource?.apply {
                text = "Buka di YouTube ↗"
                setTextColor(purple)
                isClickable = true
                setOnClickListener { callbacks.openInBrowser(activeVideoId) }
            }
        } else {
            playerSource?.text = "via Invidious"
            playerSource?.setOnClickListener(null)
        }
        Toast.makeText(activity, NaoLang.t(message), Toast.LENGTH_LONG).show()
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 0) return "0:00"
        val totalSec = seconds
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000_000L -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000L -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000L -> "%.0fK".format(n / 1_000.0)
        else -> n.toString()
    }

    private fun btn(s: String, action: () -> Unit) = TextView(activity).apply {
        text = NaoLang.t(s)
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(this@NaoVideoPages.text)
        background = rounded(NaoThemeManager.tintBox(activity, Color.rgb(23, 27, 38)), 18, true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        stateListAnimator = null
        setOnClickListener { action() }
    }
}