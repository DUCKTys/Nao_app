package com.nao.md.project.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lirik tersinkron ala YouTube Music / Spotify.
 *
 * Sumber utama: LRCLIB (publik, tanpa API key). Karena judul video YouTube
 * sering mengandung embel-embel ("Official MV", "feat.", "[Lyrics]", nama
 * channel, dsb), pencarian dilakukan berlapis: exact-get, judul+artis,
 * judul saja, lalu query bebas. Kalau LRCLIB tetap kosong, dicoba mirror
 * lyrics publik lain sebagai cadangan supaya jauh lebih jarang "tidak
 * ditemukan".
 */
object NaoLyricsClient {
    data class Line(val timeMs: Long, val text: String)
    data class Result(val lines: List<Line>, val synced: Boolean, val plain: String)

    private val cache = HashMap<String, Result>()

    private val EMPTY = Result(emptyList(), false, "")

    fun fetch(videoId: String, title: String, artist: String, durationSec: Int): Result {
        cache[videoId]?.let { if (it.lines.isNotEmpty()) return it }
        var result = try {
            lookup(title, artist, durationSec)
        } catch (_: Exception) {
            EMPTY
        }
        // Kalau tidak ada versi tersinkron, ambil caption/CC resmi YouTube —
        // sama seperti subtitle yang tampil di aplikasi YouTube, jadi
        // waktunya pasti pas dengan audio yang sedang diputar.
        if (!result.synced) {
            val cc = try { youtubeCaptions(videoId) } catch (_: Exception) { EMPTY }
            if (cc.synced && cc.lines.size >= 3) result = cc
        }
        // Hasil kosong tidak di-cache permanen supaya percobaan berikutnya
        // (mis. setelah koneksi membaik) masih bisa menemukan lirik.
        if (result.lines.isNotEmpty()) cache[videoId] = result
        return result
    }

    fun clearCache() = cache.clear()

    // ------------------------------------------------------------- pencarian

    private fun lookup(rawTitle: String, rawArtist: String, durationSec: Int): Result {
        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(rawArtist)
        // Banyak judul YouTube berbentuk "Artis - Judul".
        val split = splitTitle(rawTitle)
        val altArtist = split?.first ?: ""
        val altTitle = split?.second ?: ""

        val attempts = ArrayList<() -> Result>()
        if (artist.isNotBlank()) attempts.add { exactGet(title, artist, durationSec) }
        if (altArtist.isNotBlank()) attempts.add { exactGet(altTitle, altArtist, durationSec) }
        if (artist.isNotBlank()) attempts.add { search("track_name=${enc(title)}&artist_name=${enc(artist)}", durationSec, title, artist) }
        if (altArtist.isNotBlank()) attempts.add { search("track_name=${enc(altTitle)}&artist_name=${enc(altArtist)}", durationSec, altTitle, altArtist) }
        attempts.add { search("track_name=${enc(title)}", durationSec, title, artist) }
        if (altTitle.isNotBlank()) attempts.add { search("track_name=${enc(altTitle)}", durationSec, altTitle, altArtist) }
        attempts.add { search("q=${enc(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" "))}", durationSec, title, artist) }
        attempts.add { search("q=${enc(title)}", durationSec, title, artist) }

        var plainFallback = EMPTY
        for (attempt in attempts) {
            val r = try { attempt() } catch (_: Exception) { EMPTY }
            if (r.synced && r.lines.isNotEmpty()) return r
            if (r.lines.isNotEmpty() && plainFallback.lines.isEmpty()) plainFallback = r
        }
        if (plainFallback.lines.isNotEmpty()) return plainFallback

        // Cadangan terakhir: mirror lirik publik (plain text saja).
        val fbTitle = title.ifBlank { rawTitle }
        val fbArtist = artist.ifBlank { altArtist }
        val backup = try { lyristFallback(fbTitle, fbArtist) } catch (_: Exception) { EMPTY }
        if (backup.lines.isNotEmpty()) return backup
        val ovh = try { lyricsOvhFallback(fbTitle, fbArtist) } catch (_: Exception) { EMPTY }
        if (ovh.lines.isNotEmpty()) return ovh
        // Judul YouTube sering "Artis - Judul (feat X)"; coba tukar posisinya.
        if (altTitle.isNotBlank() && altArtist.isNotBlank()) {
            val swapped = try { lyricsOvhFallback(altTitle, altArtist) } catch (_: Exception) { EMPTY }
            if (swapped.lines.isNotEmpty()) return swapped
        }
        return EMPTY
    }

    private fun exactGet(title: String, artist: String, durationSec: Int): Result {
        if (title.isBlank() || artist.isBlank()) return EMPTY
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            if (durationSec > 0) "&duration=$durationSec" else ""
        val body = get(url) ?: return EMPTY
        return fromItem(JSONObject(body), durationSec)
    }

    private fun search(
        query: String,
        durationSec: Int,
        wantTitle: String = "",
        wantArtist: String = ""
    ): Result {
        val body = get("https://lrclib.net/api/search?$query") ?: return EMPTY
        val arr = JSONArray(body)
        if (arr.length() == 0) return EMPTY
        var best: JSONObject? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val hasSync = item.optString("syncedLyrics").isNotBlank()
            val hasPlain = item.optString("plainLyrics").isNotBlank()
            if (!hasSync && !hasPlain) continue

            // Relevansi judul wajib: tanpa ini LRCLIB gampang mengembalikan
            // lagu lain dengan judul mirip, sehingga lirik tidak sinkron
            // dengan lagu yang sedang diputar.
            val titleScore = if (wantTitle.isBlank()) 1f
                else similarity(wantTitle, item.optString("trackName"))
            if (wantTitle.isNotBlank() && titleScore < 0.55f) continue
            val artistScore = if (wantArtist.isBlank()) 0f
                else similarity(wantArtist, item.optString("artistName"))
            // Durasi yang meleset jauh hampir pasti versi/lagu yang berbeda.
            val diff = if (durationSec > 0) kotlin.math.abs(item.optInt("duration", 0) - durationSec) else 0
            if (durationSec > 0 && item.optInt("duration", 0) > 0 && diff > 25 && artistScore < 0.5f) continue

            val score = (if (hasSync) 0 else 800) +
                diff.coerceAtMost(600) +
                ((1f - titleScore) * 500).toInt() +
                ((1f - artistScore) * 300).toInt()
            if (score < bestScore) { bestScore = score; best = item }
        }
        return best?.let { fromItem(it, durationSec) } ?: EMPTY
    }

    /** Kemiripan kasar berbasis token, 0f..1f. */
    private fun similarity(a: String, b: String): Float {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val common = ta.count { tb.contains(it) }
        return common.toFloat() / kotlin.math.max(ta.size, tb.size)
    }

    private fun tokens(s: String): List<String> = s.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 1 }

    private fun fromItem(item: JSONObject, trackDurationSec: Int = 0): Result {
        val synced = item.optString("syncedLyrics", "")
        val plain = item.optString("plainLyrics", "")
        if (synced.isNotBlank()) {
            val lines = align(parseLrc(synced), item.optInt("duration", 0), trackDurationSec)
            if (lines.isNotEmpty()) return Result(lines, true, plain)
        }
        if (plain.isNotBlank()) return plainResult(plain)
        return EMPTY
    }

    private fun lyristFallback(title: String, artist: String): Result {
        if (title.isBlank()) return EMPTY
        val path = if (artist.isBlank()) enc(title) else "${enc(title)}/${enc(artist)}"
        val body = get("https://lyrist.vercel.app/api/$path") ?: return EMPTY
        val plain = JSONObject(body).optString("lyrics", "")
        if (plain.isBlank()) return EMPTY
        return plainResult(plain)
    }

    /** Mirror publik kedua: lyrics.ovh (butuh artis + judul, plain text). */
    private fun lyricsOvhFallback(title: String, artist: String): Result {
        if (title.isBlank() || artist.isBlank()) return EMPTY
        val body = get("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}") ?: return EMPTY
        val plain = JSONObject(body).optString("lyrics", "")
        if (plain.isBlank()) return EMPTY
        return plainResult(plain.replace("\r\n", "\n"))
    }

    private fun plainResult(plain: String) = Result(
        plain.lines().filter { it.isNotBlank() }.map { Line(-1L, it.trim()) },
        false,
        plain
    )

    /**
     * Sesuaikan stempel waktu LRC dengan durasi lagu yang benar-benar diputar.
     * Versi lirik sering berasal dari rilis lain yang sedikit lebih panjang
     * atau pendek, dan itulah yang membuat lirik terasa telat/kecepetan.
     */
    private fun align(lines: List<Line>, lyricDurationSec: Int, trackDurationSec: Int): List<Line> {
        if (lines.isEmpty() || lyricDurationSec <= 0 || trackDurationSec <= 0) return lines
        val ratio = trackDurationSec.toDouble() / lyricDurationSec.toDouble()
        if (ratio in 0.98..1.02 || ratio < 0.88 || ratio > 1.14) return lines
        return lines.map { Line((it.timeMs * ratio).toLong().coerceAtLeast(0L), it.text) }
    }

    // ------------------------------------------------- caption resmi YouTube

    /** Ambil subtitle/CC YouTube (termasuk auto-generated) sebagai lirik tersinkron. */
    private fun youtubeCaptions(videoId: String): Result {
        if (videoId.isBlank()) return EMPTY
        val payload = JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "21.03.37")
                .put("androidSdkVersion", 35)
                .put("hl", "en")
                .put("gl", "US")))
        val body = post(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload.toString()
        ) ?: return EMPTY
        val tracks = JSONObject(body)
            .optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return EMPTY
        if (tracks.length() == 0) return EMPTY
        // Utamakan caption manual (lebih rapi) daripada hasil ASR otomatis.
        var chosen: JSONObject? = null
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            if (t.optString("kind") != "asr") { chosen = t; break }
        }
        val track = chosen ?: tracks.getJSONObject(0)
        val base = track.optString("baseUrl")
        if (base.isBlank()) return EMPTY
        val json = get(base + (if (base.contains("?")) "&" else "?") + "fmt=json3") ?: return EMPTY
        val events = JSONObject(json).optJSONArray("events") ?: return EMPTY
        val lines = ArrayList<Line>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) sb.append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
            val text = sb.toString().replace("\n", " ").trim()
            if (text.isBlank() || text == "[Music]" || text == "[Musik]") continue
            lines.add(Line(e.optLong("tStartMs", 0L), text))
        }
        if (lines.size < 3) return EMPTY
        return Result(lines.sortedBy { it.timeMs }, true, lines.joinToString("\n") { it.text })
    }

    private fun post(url: String, body: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "com.google.android.youtube/21.03.37 (Linux; Android 15)")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------- utilitas

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NaoMusic/2.0 (https://duck-tys.vercel.app)")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("(?i)\\((?:[^)]*?(official|lyric|lyrics|video|audio|mv|m/v|hd|4k|visualizer|performance|live|cover|remaster[^)]*)[^)]*)\\)"), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("(?i)\\b(official (music )?video|official audio|lyric[s]? video|music video|full album|visualizer)\\b"), " ")
        .replace(Regex("(?i)\\s*[|｜]\\s*.*$"), " ")
        .replace(Regex("(?i)\\s*(feat\\.?|ft\\.?|with)\\s+[^-–—]*$"), " ")
        .replace(Regex("[\"'“”‘’]"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trim('-', '–', '—', ' ')

    private fun cleanArtist(artist: String): String = artist
        .replace(Regex("(?i)\\s*-\\s*topic$"), "")
        .replace(Regex("(?i)\\bvevo\\b"), "")
        .replace(Regex("(?i)\\s*(official|channel)\\s*$"), "")
        .substringBefore(",")
        .substringBefore(" & ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /** "Artis - Judul" -> Pair(artis, judul). */
    private fun splitTitle(raw: String): Pair<String, String>? {
        val m = Regex("^(.{2,60}?)\\s*[-–—]\\s*(.{2,80})$").find(raw.trim()) ?: return null
        val left = cleanArtist(m.groupValues[1])
        val right = cleanTitle(m.groupValues[2])
        if (left.isBlank() || right.isBlank()) return null
        return left to right
    }

    private fun parseLrc(raw: String): List<Line> {
        val stamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<Line>()
        raw.lines().forEach { line ->
            val matches = stamp.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                out.add(Line(min * 60_000L + sec * 1000L + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
