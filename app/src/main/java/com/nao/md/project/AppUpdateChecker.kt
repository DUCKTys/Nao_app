package com.nao.md.project

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {
    private const val OWNER_REPO = "DUCKTys/DUCKTys"
    private const val API_LIST = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=30"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val RELEASES = "https://github.com/$OWNER_REPO/releases"
    const val DOWNLOAD_SITE = "https://duck-tys.vercel.app/"

    /** Terisi setelah check() selesai; berguna untuk pesan diagnosa saat cek manual. */
    @Volatile var lastError: String? = null
        private set

    data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val releaseUrl: String,
        val preRelease: Boolean,
        val apkUrl: String,
        val apkSize: Long
    )

    fun check(context: Context, callback: (Release?) -> Unit) {
        Thread {
            lastError = null
            var result: Release? = null
            try {
                val local = normalize(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
                // Ambil SEMUA release (termasuk pre-release), bukan hanya /releases/latest
                // karena endpoint latest mengabaikan pre-release & draft.
                val candidates = ArrayList<Release>()
                val listBody = fetch(context, API_LIST)
                if (listBody != null) {
                    val arr = JSONArray(listBody)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optBoolean("draft", false)) continue
                        candidates.add(toRelease(obj) ?: continue)
                    }
                }
                if (candidates.isEmpty()) {
                    val single = fetch(context, API_LATEST)?.let { toRelease(JSONObject(it)) }
                    if (single != null) candidates.add(single)
                }
                val newest = candidates.maxWithOrNull { a, b -> compare(a.version, b.version) }
                if (newest == null) {
                    if (lastError == null) lastError = "Belum ada release yang dipublikasikan."
                } else if (compare(newest.version, local) > 0) {
                    result = newest
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Gagal memeriksa pembaruan."
            }
            val out = result
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(out) }
        }.start()
    }

    private fun toRelease(obj: JSONObject): Release? {
        val raw = obj.optString("tag_name").ifBlank { obj.optString("name") }
        val version = normalize(raw)
        if (version.isBlank() || version.firstOrNull()?.isDigit() != true) return null
        // Ambil aset .apk dari release GitHub supaya bisa diunduh langsung
        // dari dalam aplikasi, tanpa buka browser/website dulu.
        var apkUrl = ""
        var apkSize = 0L
        obj.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        return Release(
            version,
            obj.optString("name").ifBlank { "Nao MD $version" },
            obj.optString("body"),
            obj.optString("html_url", RELEASES),
            obj.optBoolean("prerelease", false),
            apkUrl,
            apkSize
        )
    }

    private fun fetch(context: Context, url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Nao-MD/${context.packageName}")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                lastError = "GitHub HTTP $code"
                null
            } else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            lastError = e.message ?: "Koneksi gagal"
            null
        } finally { connection?.disconnect() }
    }

    private fun normalize(v: String): String =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').trim()

    private fun compare(a: String, b: String): Int {
        val aa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val bb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val x = aa.getOrElse(i) { 0 }; val y = bb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
