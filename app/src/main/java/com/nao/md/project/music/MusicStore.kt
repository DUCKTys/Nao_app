package com.nao.md.project.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local Nao Music library: likes, play history, playlists, search history.
 * Uses the same SharedPreferences keys as the existing Nao MD music feature
 * so user playlists created in earlier versions are preserved.
 */
class MusicStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nao_prefs", Context.MODE_PRIVATE)

    fun playlists(): JSONObject =
        try { JSONObject(prefs.getString("nao_playlists", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun savePlaylists(data: JSONObject) {
        prefs.edit().putString("nao_playlists", data.toString()).apply()
    }

    fun playlistNames(): List<String> = playlists().keys().asSequence().toList().sorted()

    fun playlistTracks(name: String): List<InnerTubeClient.Track> {
        val arr = playlists().optJSONArray(name) ?: JSONArray()
        val out = ArrayList<InnerTubeClient.Track>()
        for (i in 0 until arr.length()) {
            trackFrom(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun addToPlaylist(name: String, track: InnerTubeClient.Track): Boolean {
        if (name.isBlank() || track.videoId.isBlank()) return false
        val data = playlists()
        val list = data.optJSONArray(name) ?: JSONArray()
        for (i in 0 until list.length()) {
            if (list.optJSONObject(i)?.optString("videoId") == track.videoId) return false
        }
        list.put(trackToJson(track))
        data.put(name, list)
        savePlaylists(data)
        return true
    }

    fun createPlaylist(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val data = playlists()
        if (data.has(trimmed)) return false
        data.put(trimmed, JSONArray())
        savePlaylists(data)
        return true
    }

    fun deletePlaylist(name: String) {
        val data = playlists()
        data.remove(name)
        savePlaylists(data)
    }

    fun removeFromPlaylist(name: String, videoId: String) {
        val data = playlists()
        val arr = data.optJSONArray(name) ?: return
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("videoId") != videoId) out.put(o)
        }
        if (out.length() == 0) data.remove(name) else data.put(name, out)
        savePlaylists(data)
    }

    fun liked(): List<InnerTubeClient.Track> = readTrackList("nao_liked_songs")

    fun isLiked(videoId: String): Boolean = liked().any { it.videoId == videoId }

    fun toggleLike(track: InnerTubeClient.Track): Boolean {
        val list = liked().toMutableList()
        val exists = list.any { it.videoId == track.videoId }
        if (exists) list.removeAll { it.videoId == track.videoId } else list.add(0, track)
        writeTrackList("nao_liked_songs", list.take(500))
        return !exists
    }

    fun history(): List<InnerTubeClient.Track> = readTrackList("nao_play_history")

    fun recordPlay(track: InnerTubeClient.Track) {
        if (track.videoId.isBlank()) return
        val list = history().toMutableList()
        list.removeAll { it.videoId == track.videoId }
        list.add(0, track)
        writeTrackList("nao_play_history", list.take(200))
    }

    fun removeFromHistory(videoId: String) {
        val list = history().toMutableList()
        list.removeAll { it.videoId == videoId }
        writeTrackList("nao_play_history", list)
    }

    fun clearHistory() {
        prefs.edit().remove("nao_play_history").apply()
    }

    fun searchHistory(): List<String> =
        prefs.getStringSet("music_search_history", emptySet()).orEmpty().toList()

    fun saveSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val old = prefs.getStringSet("music_search_history", emptySet()).orEmpty().toMutableSet()
        old.remove(q)
        val ordered = ArrayList<String>()
        ordered.add(q)
        ordered.addAll(old)
        prefs.edit().putStringSet("music_search_history", ordered.take(12).toSet()).apply()
    }

    private fun trackToJson(track: InnerTubeClient.Track) = JSONObject().apply {
        put("videoId", track.videoId)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("duration", track.duration)
        put("thumbnail", track.thumbnail)
    }

    private fun trackFrom(o: JSONObject?): InnerTubeClient.Track? {
        if (o == null) return null
        val id = o.optString("videoId")
        if (id.isBlank()) return null
        return InnerTubeClient.Track(
            videoId = id,
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            duration = o.optString("duration"),
            thumbnail = o.optString("thumbnail")
        )
    }

    private fun readTrackList(key: String): List<InnerTubeClient.Track> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<InnerTubeClient.Track>()
            for (i in 0 until arr.length()) trackFrom(arr.optJSONObject(i))?.let { out.add(it) }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeTrackList(key: String, tracks: List<InnerTubeClient.Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(trackToJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
