package com.nao.md.project.music

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nao.md.project.NaoLang
import com.nao.md.project.NaoUiCompat
import com.nao.md.project.NaoThemeManager
import com.nao.md.project.R
import java.util.Calendar
import java.util.concurrent.ExecutorService

/**
 * ArchiveTune-class native Nao Music shell.
 *
 * Built entirely with Android Views (no WebView, no Compose) so it sits
 * inside the existing Nao MD MainActivity without touching other tools.
 */
class NaoMusicPages(
    private val activity: Activity,
    private val innerTube: InnerTubeClient,
    private val store: MusicStore,
    private val exec: ExecutorService,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun play(track: InnerTubeClient.Track, source: List<InnerTubeClient.Track>, index: Int, shuffleRest: Boolean = false)
        fun enqueue(track: InnerTubeClient.Track)
        fun addToPlaylist(track: InnerTubeClient.Track)
        fun download(track: InnerTubeClient.Track)
        fun loadArtwork(view: ImageView, track: InnerTubeClient.Track)
        fun applyThumb(view: ImageView, radiusDp: Int)
        fun showSettings()
        fun playLocalPlaylist(name: String)
        fun queueTracks(): List<InnerTubeClient.Track>
    }

    private val purple: Int get() = NaoThemeManager.current(activity).bright
    private val purpleDark: Int get() = NaoThemeManager.current(activity).dark
    private val bg = Color.rgb(8, 9, 14)
    private val surface = Color.rgb(18, 20, 28)
    private val elevated = Color.rgb(26, 29, 40)
    private val text = Color.rgb(244, 241, 234)
    private val muted = Color.rgb(180, 186, 202)
    private val chipOff = Color.rgb(31, 34, 47)

    private var tab = "Home"
    private var host: LinearLayout? = null
    private var searchField: EditText? = null
    private var tabRow: LinearLayout? = null
    private var generation = 0L
    private var cachedHome: InnerTubeClient.HomeFeed? = null
    private var cachedExplore: InnerTubeClient.HomeFeed? = null
    private var cachedMoods: InnerTubeClient.HomeFeed? = null

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false) = TextView(activity).apply {
        text = NaoLang.t(s)
        setTextColor(this@NaoMusicPages.text)
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), Color.argb(28, 255, 255, 255))
    }

    fun attach(parent: LinearLayout) {
        parent.removeAllViews()
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(18))
        }

        page.addView(buildGreeting())
        page.addView(buildSearch(), LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(0, 0, 0, dp(12))
        })
        page.addView(buildTabs(), LinearLayout.LayoutParams(-1, dp(46)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        host = body
        page.addView(body)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        parent.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        renderTab(tab)
    }

    private fun buildGreeting(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        val meta = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        meta.addView(tv(when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }, 26f, true))
        meta.addView(tv("Made for your listening", 12f).apply {
            setTextColor(muted); setPadding(0, dp(4), 0, 0)
        })
        row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("⋮", 26f, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { callbacks.showSettings() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        return row
    }

    private fun hideKeyboard(view: View) {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun buildSearch(): FrameLayout {
        val field = EditText(activity).apply {
            hint = "Search songs, artists, albums, playlists"
            setHintTextColor(muted)
            setTextColor(this@NaoMusicPages.text)
            textSize = 14f
            background = rounded(elevated, 999, true)
            setPadding(dp(18), 0, dp(52), 0)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { v, _, _ ->
                runSearch(text.toString())
                hideKeyboard(v)
                true
            }
        }
        searchField = field
        val box = FrameLayout(activity)
        box.addView(field, FrameLayout.LayoutParams(-1, dp(52)))
        box.addView(tv("⌕", 22f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(muted)
            setOnClickListener { runSearch(field.text.toString()); hideKeyboard(field) }
        }, FrameLayout.LayoutParams(dp(48), dp(52), Gravity.END))
        return box
    }

    private fun buildTabs(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabRow = row
        paintTabs()
        return row
    }

    private fun paintTabs() {
        val row = tabRow ?: return
        row.removeAllViews()
        listOf("Home", "Explore", "Library").forEach { label ->
            val active = tab == label
            val chip = tv(label, 13f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (active) Color.WHITE else chipOff, 999, false)
                setTextColor(if (active) Color.BLACK else Color.WHITE)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener {
                    tab = label
                    paintTabs()
                    renderTab(label)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(tv("☰  Playlists", 12f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(purpleDark, 999, true)
            setTextColor(purple)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { renderLocalPlaylists() }
        }, LinearLayout.LayoutParams(-2, dp(40)))
    }

    private fun renderTab(name: String) {
        generation++
        when (name) {
            "Explore" -> renderExplore()
            "Library" -> renderLibrary()
            else -> renderHome()
        }
    }

    private fun hostOrNull(): LinearLayout? = host

    private fun begin(title: String? = null): LinearLayout? {
        val h = hostOrNull() ?: return null
        h.removeAllViews()
        if (title != null) {
            h.addView(tv(title, 11f).apply {
                setTextColor(muted); setPadding(dp(2), dp(4), dp(2), dp(10))
            })
        }
        h.addView(ProgressBar(activity).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(24), 0, dp(24))
        })
        return h
    }

    private fun renderHome() {
        val h = begin("Loading your mix…") ?: return
        val gen = generation
        val history = store.history()
        val liked = store.liked()
        exec.execute {
            val feed = cachedHome ?: runCatching { innerTube.home() }.getOrNull()
            if (feed != null) cachedHome = feed
            val fallback = if (feed == null || feed.shelves.isEmpty()) {
                runCatching { innerTube.searchFiltered("trending songs 2026", InnerTubeClient.FILTER_SONG) }.getOrDefault(emptyList())
            } else emptyList()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                h.removeAllViews()
                if (history.isNotEmpty()) {
                    // shuffleRest = true: "Listen again" cuma daftar riwayat
                    // (urutan bahasa/genre campur, tidak berurutan secara
                    // sengaja), jadi menekan satu lagu di sini TIDAK boleh
                    // menjejalkan sisa daftar riwayat itu ke antrian —
                    // biarkan Autoplay Pintar yang menyambung lagu berikutnya.
                    addTrackShelf(h, "Listen again", history.take(12), shuffleRest = true)
                }
                if (liked.isNotEmpty()) {
                    addTrackShelf(h, "Liked songs", liked.take(12), shuffleRest = true)
                }
                val chips = feed?.chips.orEmpty()
                if (chips.isNotEmpty()) addChipRow(h, chips)
                else addQuickChips(h)
                feed?.shelves.orEmpty().forEach { shelf ->
                    addItemShelf(h, shelf.title, shelf.items)
                }
                if (fallback.isNotEmpty()) {
                    addItemShelf(h, "Trending now", fallback)
                }
                if (h.childCount == 0) {
                    h.addView(tv("Search for an artist or song to start building your mix.", 13f).apply {
                        setTextColor(muted); setPadding(dp(4), dp(16), dp(4), dp(16))
                    })
                }
            }
        }
    }

    private fun renderExplore() {
        val h = begin("Charts, moods & new releases") ?: return
        val gen = generation
        exec.execute {
            val explore = cachedExplore ?: runCatching { innerTube.explore() }.getOrNull()
            if (explore != null) cachedExplore = explore
            val charts = runCatching { innerTube.charts() }.getOrNull()
            val moods = cachedMoods ?: runCatching { innerTube.moods() }.getOrNull()
            if (moods != null) cachedMoods = moods
            val fresh = runCatching { innerTube.newReleases() }.getOrNull()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                h.removeAllViews()
                h.addView(tv("Explore", 22f, true).apply { setPadding(dp(2), dp(4), 0, dp(12)) })
                moods?.shelves.orEmpty().firstOrNull()?.let { addMoodRow(h, "Moods & genres", it.items) }
                if (moods?.shelves.orEmpty().isEmpty()) {
                    val moodItems = moods?.shelves.orEmpty().flatMap { it.items }
                    if (moodItems.isNotEmpty()) addMoodRow(h, "Moods & genres", moodItems)
                }
                moods?.shelves.orEmpty().drop(1).forEach { addItemShelf(h, it.title, it.items) }
                charts?.shelves.orEmpty().forEach { addItemShelf(h, it.title.ifBlank { "Charts" }, it.items) }
                fresh?.shelves.orEmpty().forEach { addItemShelf(h, it.title.ifBlank { "New releases" }, it.items) }
                explore?.shelves.orEmpty().forEach { addItemShelf(h, it.title, it.items) }
                if (h.childCount <= 1) {
                    h.addView(tv("Could not load Explore. Check your connection and pull Home again.", 13f).apply {
                        setTextColor(muted); setPadding(dp(4), dp(16), dp(4), dp(16))
                    })
                }
            }
        }
    }

    private fun renderLibrary() {
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(tv("Library", 22f, true).apply { setPadding(dp(2), dp(4), 0, dp(12)) })

        val chips = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun libChip(label: String, action: () -> Unit) {
            row.addView(tv(label, 12f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(chipOff, 999, true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        libChip("Liked") { renderLikedSongs() }
        libChip("Playlists") { renderLocalPlaylists() }
        libChip("History") { renderHistoryPage() }
        libChip("Antrian") { renderTrackList("Antrian", callbacks.queueTracks()) }
        chips.addView(row)
        h.addView(chips, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(0, 0, 0, dp(14)) })

        addStatCard(h, "Liked songs", "${store.liked().size} tracks") { renderLikedSongs() }
        addStatCard(h, "Playlists", "${store.playlistNames().size} collections") { renderLocalPlaylists() }
        addStatCard(h, "History", "${store.history().size} plays") { renderHistoryPage() }

        val liked = store.liked().take(8)
        if (liked.isNotEmpty()) addTrackShelf(h, "Pinned likes", liked, shuffleRest = true)
        val hist = store.history().take(8)
        if (hist.isNotEmpty()) addTrackShelf(h, "Jump back in", hist, shuffleRest = true)
        if (liked.isEmpty() && hist.isEmpty()) {
            h.addView(tv("Like songs and build playlists — your library lives on this device.", 13f).apply {
                setTextColor(muted); setPadding(dp(4), dp(16), dp(4), dp(16))
            })
        }
    }

    private fun addStatCard(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20, true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { action() }
        }
        card.addView(tv(title, 15f, true))
        card.addView(tv(subtitle, 11f).apply { setTextColor(muted); setPadding(0, dp(4), 0, 0) })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun clearAllButton(label: String, onClear: () -> Unit): TextView =
        tv(label, 12f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 120, 120))
            background = rounded(chipOff, 999, true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClear() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, 0, dp(10)) }
        }

    private fun renderTrackList(title: String, tracks: List<InnerTubeClient.Track>) {
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(backRow(title))
        if (tracks.isEmpty()) {
            h.addView(tv("Nothing here yet.", 13f).apply { setTextColor(muted); setPadding(dp(4), dp(16), 0, 0) })
            return
        }
        h.addView(playAllButton(tracks))
        tracks.forEachIndexed { i, t -> h.addView(songRow(t, tracks, i), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }) }
    }

    /** Liked songs, dengan tombol ✕ per lagu untuk menghapus dari suka. */
    private fun renderLikedSongs() {
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(backRow("Liked songs") { renderLibrary() })
        val tracks = store.liked()
        if (tracks.isEmpty()) {
            h.addView(tv("Belum ada lagu yang disukai.", 13f).apply { setTextColor(muted); setPadding(dp(4), dp(16), 0, 0) })
            return
        }
        h.addView(playAllButton(tracks))
        tracks.forEachIndexed { i, t ->
            h.addView(
                songRow(t, tracks, i, shuffleRest = true, onRemove = {
                    store.toggleLike(t)
                    renderLikedSongs()
                }),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
            )
        }
    }

    /** Riwayat putar, dengan tombol ✕ per lagu dan "Hapus riwayat" untuk semua. */
    private fun renderHistoryPage() {
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(backRow("Recently played") { renderLibrary() })
        val tracks = store.history()
        if (tracks.isEmpty()) {
            h.addView(tv("Belum ada riwayat putar.", 13f).apply { setTextColor(muted); setPadding(dp(4), dp(16), 0, 0) })
            return
        }
        h.addView(clearAllButton("Hapus riwayat") {
            store.clearHistory()
            renderHistoryPage()
        })
        h.addView(playAllButton(tracks))
        tracks.forEachIndexed { i, t ->
            h.addView(
                // shuffleRest = true: memutar dari riwayat tidak boleh
                // menjejalkan sisa riwayat (campur bahasa/genre) ke antrian.
                songRow(t, tracks, i, shuffleRest = true, onRemove = {
                    store.removeFromHistory(t.videoId)
                    renderHistoryPage()
                }),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
            )
        }
    }

    private fun renderLocalPlaylists() {
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(backRow("Your playlists"))
        val names = store.playlistNames()
        if (names.isEmpty()) {
            h.addView(tv("Belum ada playlist. Buka menu lagu lalu pilih “Taruh ke Playlist”.", 13f).apply {
                setTextColor(muted); setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return
        }
        names.forEach { name ->
            val count = store.playlistTracks(name).size
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(surface, 20, true)
                setPadding(dp(14), dp(12), dp(10), dp(12))
                setOnClickListener { openLocalPlaylist(name) }
            }
            row.addView(tv("☰", 18f, true).apply {
                gravity = Gravity.CENTER; setTextColor(purple)
            }, LinearLayout.LayoutParams(dp(40), dp(44)))
            val meta = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
            meta.addView(tv(name, 15f, true))
            meta.addView(tv("$count songs", 11f).apply { setTextColor(muted) })
            row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("✕", 17f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 120, 120))
                setOnClickListener {
                    store.deletePlaylist(name)
                    renderLocalPlaylists()
                }
            }, LinearLayout.LayoutParams(dp(36), dp(44)))
            h.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
    }

    private fun openLocalPlaylist(name: String) {
        val tracks = store.playlistTracks(name)
        val h = hostOrNull() ?: return
        h.removeAllViews()
        h.addView(backRow(name) { renderLocalPlaylists() })
        h.addView(clearAllButton("Hapus playlist") {
            store.deletePlaylist(name)
            renderLocalPlaylists()
        })
        if (tracks.isEmpty()) {
            h.addView(tv("Playlist ini masih kosong.", 13f).apply { setTextColor(muted) })
            return
        }
        h.addView(playAllButton(tracks, playlistMode = true, playlistName = name))
        tracks.forEachIndexed { i, t ->
            h.addView(
                songRow(t, tracks, i, onRemove = {
                    store.removeFromPlaylist(name, t.videoId)
                    openLocalPlaylist(name)
                }),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
            )
        }
    }

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isBlank()) return
        store.saveSearch(q)
        tab = "Home"
        paintTabs()
        val h = begin("Finding “$q”…") ?: return
        val gen = generation
        exec.execute {
            val songs = runCatching { innerTube.searchFiltered(q, InnerTubeClient.FILTER_SONG) }.getOrDefault(emptyList())
            val albums = runCatching { innerTube.searchFiltered(q, InnerTubeClient.FILTER_ALBUM) }.getOrDefault(emptyList())
            val artists = runCatching { innerTube.searchFiltered(q, InnerTubeClient.FILTER_ARTIST) }.getOrDefault(emptyList())
            val playlists = runCatching { innerTube.searchFiltered(q, InnerTubeClient.FILTER_PLAYLIST) }.getOrDefault(emptyList())
            val videos = runCatching { innerTube.searchFiltered(q, InnerTubeClient.FILTER_VIDEO) }.getOrDefault(emptyList())
            val fallback = if (songs.isEmpty()) {
                runCatching { innerTube.search(q).map { it.toItem() } }.getOrDefault(emptyList())
            } else emptyList()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                h.removeAllViews()
                h.addView(tv("Results for “$q”", 20f, true).apply { setPadding(dp(2), dp(4), 0, dp(10)) })
                val songItems = songs.ifEmpty { fallback }
                if (songItems.isNotEmpty()) addItemShelf(h, "Songs", songItems, listMode = true, shuffleRest = true)
                if (artists.isNotEmpty()) addItemShelf(h, "Artists", artists)
                if (albums.isNotEmpty()) addItemShelf(h, "Albums", albums)
                if (playlists.isNotEmpty()) addItemShelf(h, "Playlists", playlists)
                if (videos.isNotEmpty()) addItemShelf(h, "Videos", videos)
                if (h.childCount <= 1) {
                    h.addView(tv("No results. Try another spelling or artist name.", 13f).apply {
                        setTextColor(muted); setPadding(dp(4), dp(16), 0, 0)
                    })
                }
            }
        }
    }

    private fun InnerTubeClient.Track.toItem() = InnerTubeClient.MusicItem(
        title = title,
        subtitle = artist,
        thumbnail = thumbnail,
        videoId = videoId,
        kind = "song",
        duration = duration,
        artist = artist,
        album = album
    )

    private fun addQuickChips(parent: LinearLayout) {
        val chips = listOf(
            "For you" to "popular songs",
            "New releases" to "new music 2026",
            "Chill" to "chill music",
            "Workout" to "workout mix",
            "Focus" to "lofi hip hop",
            "Romance" to "love songs",
            "Sad" to "sad songs",
            "Party" to "party hits",
            "Indonesia" to "lagu indonesia terbaru",
            "Anime" to "anime openings"
        )
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEach { (label, query) ->
            row.addView(tv(label, 12f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(chipOff, 999, true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { searchField?.setText(query); runSearch(query) }
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        scroll.addView(row)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(0, 0, 0, dp(12)) })
    }

    private fun addChipRow(parent: LinearLayout, chips: List<InnerTubeClient.Chip>) {
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEach { chip ->
            row.addView(tv(chip.title, 12f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(chipOff, 999, true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    if (chip.browseId.isNotBlank()) openBrowse(chip.browseId, chip.params, chip.title)
                    else runSearch(chip.title)
                }
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        scroll.addView(row)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(0, 0, 0, dp(12)) })
    }

    private fun addMoodRow(parent: LinearLayout, title: String, items: List<InnerTubeClient.MusicItem>) {
        parent.addView(tv(title, 19f, true).apply { setPadding(dp(2), dp(8), 0, dp(10)) })
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        items.take(24).forEach { item ->
            val card = tv(item.title, 13f, true).apply {
                gravity = Gravity.CENTER
                background = rounded(purpleDark, 16, true)
                setPadding(dp(16), dp(18), dp(16), dp(18))
                setOnClickListener { openBrowse(item.browseId, item.params, item.title) }
            }
            row.addView(card, LinearLayout.LayoutParams(dp(148), dp(72)).apply { setMargins(0, 0, dp(8), 0) })
        }
        scroll.addView(row)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) })
    }

    private fun addTrackShelf(parent: LinearLayout, title: String, tracks: List<InnerTubeClient.Track>, shuffleRest: Boolean = false) {
        addItemShelf(parent, title, tracks.map { it.toItem() }, shuffleRest = shuffleRest)
    }

    private fun addItemShelf(parent: LinearLayout, title: String, items: List<InnerTubeClient.MusicItem>, listMode: Boolean = false, shuffleRest: Boolean = false) {
        if (items.isEmpty()) return
        parent.addView(tv(title, 19f, true).apply { setPadding(dp(2), dp(10), 0, dp(8)) })
        if (listMode || items.all { it.kind == "song" || it.kind == "video" } && items.size > 6 && title.equals("Songs", true)) {
            val tracks = items.map { it.toTrack() }.filter { it.videoId.isNotBlank() }
            items.take(20).forEachIndexed { i, item ->
                parent.addView(itemRow(item, tracks, i, shuffleRest), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
            }
            return
        }
        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        items.take(16).forEach { item ->
            row.addView(coverCard(item, items, shuffleRest), LinearLayout.LayoutParams(dp(148), -2).apply { setMargins(0, 0, dp(10), 0) })
        }
        scroll.addView(row)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun coverCard(item: InnerTubeClient.MusicItem, siblings: List<InnerTubeClient.MusicItem>, shuffleRest: Boolean = false): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18, true)
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setOnClickListener { onItem(item, siblings, shuffleRest) }
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.nao_music_artwork)
            background = rounded(elevated, if (item.kind == "artist") 999 else 14, false)
        }
        callbacks.applyThumb(art, if (item.kind == "artist") 999 else 14)
        if (item.thumbnail.isNotBlank() || item.videoId.isNotBlank()) {
            callbacks.loadArtwork(art, item.toTrack())
        }
        val size = dp(132)
        card.addView(art, LinearLayout.LayoutParams(size, size))
        card.addView(tv(item.title, 13f, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(8), dp(2), 0)
        }, LinearLayout.LayoutParams(size, -2))
        card.addView(tv(item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } }, 11f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(muted); setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(size, -2))
        return card
    }

    private fun itemRow(item: InnerTubeClient.MusicItem, tracks: List<InnerTubeClient.Track>, index: Int, shuffleRest: Boolean = false): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18, true)
            setPadding(dp(8), dp(8), dp(6), dp(8))
            setOnClickListener { onItem(item, tracks.map { InnerTubeClient.MusicItem(it.title, it.artist, it.thumbnail, it.videoId, kind = "song", artist = it.artist, album = it.album, duration = it.duration) }, shuffleRest) }
            setOnLongClickListener { showItemMenu(item); true }
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.nao_music_artwork)
        }
        callbacks.applyThumb(art, 12)
        callbacks.loadArtwork(art, item.toTrack())
        row.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))
        val meta = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        meta.addView(tv(item.title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        meta.addView(tv(item.subtitle.ifBlank { item.artist }.ifBlank { item.kind }, 11f).apply {
            setTextColor(muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("⋮", 20f).apply {
            gravity = Gravity.CENTER; setTextColor(muted)
            setOnClickListener { showItemMenu(item) }
        }, LinearLayout.LayoutParams(dp(40), dp(56)))
        return row
    }

    private fun songRow(
        track: InnerTubeClient.Track,
        source: List<InnerTubeClient.Track>,
        index: Int,
        shuffleRest: Boolean = false,
        onRemove: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18, true)
            setPadding(dp(8), dp(8), dp(6), dp(8))
            setOnClickListener { callbacks.play(track, source, index, shuffleRest) }
            setOnLongClickListener { showTrackMenu(track); true }
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.nao_music_artwork)
        }
        callbacks.applyThumb(art, 12)
        callbacks.loadArtwork(art, track)
        row.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))
        val meta = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        meta.addView(tv(track.title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        meta.addView(tv(track.artist.ifBlank { "YouTube Music" }, 11f).apply {
            setTextColor(muted); maxLines = 1; setPadding(0, dp(3), 0, 0)
        })
        row.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        if (onRemove != null) {
            row.addView(tv("✕", 17f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 120, 120))
                setOnClickListener { onRemove() }
            }, LinearLayout.LayoutParams(dp(36), dp(56)))
        }
        row.addView(tv("⋮", 20f).apply {
            gravity = Gravity.CENTER; setTextColor(muted)
            setOnClickListener { showTrackMenu(track) }
        }, LinearLayout.LayoutParams(dp(40), dp(56)))
        return row
    }

    private fun onItem(item: InnerTubeClient.MusicItem, siblings: List<InnerTubeClient.MusicItem>, shuffleRest: Boolean = false) {
        when (item.kind) {
            "song", "video" -> {
                val tracks = siblings.map { it.toTrack() }.filter { it.videoId.isNotBlank() }
                val idx = tracks.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                if (item.videoId.isNotBlank()) callbacks.play(item.toTrack(), tracks, idx, shuffleRest)
            }
            "album", "playlist", "artist", "mood" -> openBrowse(item.browseId, item.params, item.title)
            else -> if (item.videoId.isNotBlank()) callbacks.play(item.toTrack(), listOf(item.toTrack()), 0)
        }
    }

    private fun openBrowse(browseId: String, params: String, fallbackTitle: String) {
        if (browseId.isBlank()) {
            runSearch(fallbackTitle)
            return
        }
        val h = begin("Opening $fallbackTitle…") ?: return
        val gen = generation
        exec.execute {
            val page = runCatching { innerTube.browsePage(browseId, params) }.getOrNull()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                h.removeAllViews()
                if (page == null) {
                    h.addView(tv("Could not open this collection.", 13f).apply { setTextColor(muted) })
                    return@runOnUiThread
                }
                h.addView(backRow(page.title))
                if (page.subtitle.isNotBlank()) {
                    h.addView(tv(page.subtitle, 12f).apply { setTextColor(muted); setPadding(dp(2), 0, 0, dp(10)) })
                }
                if (page.tracks.isNotEmpty()) {
                    h.addView(playAllButton(page.tracks))
                    page.tracks.take(80).forEachIndexed { i, t ->
                        h.addView(songRow(t, page.tracks, i), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
                    }
                }
                page.shelves.forEach { addItemShelf(h, it.title, it.items) }
                if (page.tracks.isEmpty() && page.shelves.isEmpty()) {
                    h.addView(tv("This page has no playable songs yet.", 13f).apply { setTextColor(muted) })
                }
            }
        }
    }

    private fun backRow(title: String, onBack: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        row.addView(tv("‹  Back", 13f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(chipOff, 999, true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onBack?.invoke() ?: renderTab(tab) }
        }, LinearLayout.LayoutParams(-2, dp(38)))
        row.addView(tv(title, 18f, true).apply {
            setPadding(dp(12), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun playAllButton(tracks: List<InnerTubeClient.Track>, playlistMode: Boolean = false, playlistName: String = ""): TextView {
        return tv("▶  Play all · ${tracks.size}", 14f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = rounded(Color.WHITE, 999, false)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                if (playlistMode && playlistName.isNotBlank()) callbacks.playLocalPlaylist(playlistName)
                else if (tracks.isNotEmpty()) callbacks.play(tracks.first(), tracks, 0)
            }
        }.also {
            val lp = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(12)) }
            it.layoutParams = lp
        }
    }

    private fun showItemMenu(item: InnerTubeClient.MusicItem) {
        if (item.kind == "song" || item.kind == "video") showTrackMenu(item.toTrack())
        else openBrowse(item.browseId, item.params, item.title)
    }

    /**
     * Modern bottom-sheet style track menu (grab handle, artwork header,
     * rounded action rows) replacing the stock AlertDialog list.
     */
    private fun showTrackMenu(track: InnerTubeClient.Track) {
        val liked = store.isLiked(track.videoId)
        val dialog = android.app.Dialog(activity)

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 18, 26))
                cornerRadii = floatArrayOf(
                    dp(28).toFloat(), dp(28).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        sheet.addView(View(activity).apply {
            background = rounded(Color.rgb(58, 63, 80), 999, false)
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(16))
        })

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.nao_music_artwork)
        }
        callbacks.applyThumb(art, 16)
        callbacks.loadArtwork(art, track)
        header.addView(art, LinearLayout.LayoutParams(dp(58), dp(58)))
        val meta = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        meta.addView(tv(track.title, 15f, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(tv(track.artist.ifBlank { "YouTube Music" }, 11f).apply {
            setTextColor(muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2))

        sheet.addView(View(activity).apply {
            setBackgroundColor(Color.rgb(32, 36, 48))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, 0, 0, dp(10)) })

        fun action(icon: String, label: String, run: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(23, 26, 36), 18, false)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                setOnClickListener { dialog.dismiss(); run() }
            }
            row.addView(tv(icon, 16f).apply {
                gravity = Gravity.CENTER
                setTextColor(purple)
            }, LinearLayout.LayoutParams(dp(28), -2))
            row.addView(tv(label, 14f, true).apply { setPadding(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            sheet.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }

        action("♥", if (liked) "Hapus dari suka" else "Suka") {
            val now = store.toggleLike(track)
            Toast.makeText(
                activity,
                NaoLang.t(if (now) "Ditambahkan ke suka" else "Dihapus dari suka"),
                Toast.LENGTH_SHORT
            ).show()
        }
        action("≡", "Tambah ke antrian") { callbacks.enqueue(track) }
        action("＋", "Tambah ke playlist") { callbacks.addToPlaylist(track) }
        action("⭳", "Unduh lagu") { callbacks.download(track) }

        sheet.addView(tv("Tutup", 13f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(muted)
            background = rounded(Color.rgb(20, 23, 32), 999, true)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(4), 0, 0) })

        dialog.setContentView(NaoUiCompat.scrollable(sheet, 0.82f))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(-1, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }
}

