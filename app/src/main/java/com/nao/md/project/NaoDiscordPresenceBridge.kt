package com.nao.md.project

import android.content.Context
import android.util.Log

/**
 * Discord presence integration point.
 *
 * Single source of truth for current playback; forwards every change to the
 * gateway transport (NaoDiscordRpc) so the activity really shows up on Discord.
 */
object NaoDiscordPresenceBridge {
    private const val TAG = "NaoDiscordPresence"

    data class Track(
        val title: String,
        val artist: String,
        val artworkUrl: String = "",
        val duration: Long = 0L,
        val paused: Boolean = false,
        val position: Long = 0L,
        val album: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    @Volatile
    private var current: Track? = null

    fun update(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String = "",
        duration: Long = 0L,
        paused: Boolean = false,
        position: Long = 0L,
        album: String = ""
    ) {
        if (title.isBlank()) return
        val previous = current
        val artwork = artworkUrl.ifBlank { previous?.artworkUrl.orEmpty() }
        val safeDuration = if (duration > 0L) duration else previous?.duration ?: 0L
        val next = Track(title, artist, artwork, safeDuration, paused, position, album)

        // Ignore no-op refreshes, but always let a seek (>5s drift) through so
        // the Discord progress bar stays in sync with the player.
        if (previous != null &&
            previous.title == next.title &&
            previous.artist == next.artist &&
            previous.album == next.album &&
            previous.paused == next.paused &&
            previous.artworkUrl == next.artworkUrl &&
            Math.abs(previous.position - next.position) < 5_000L
        ) return
        current = next

        Log.d(TAG, "Presence update: ${next.title} - ${next.artist} (paused=${next.paused})")
        NaoDiscordManager.updateForTrack(
            context,
            next.title,
            next.artist,
            artwork,
            paused,
            safeDuration,
            position,
            album
        )
    }

    fun clear() {
        current = null
        NaoDiscordRpc.clear()
    }

    fun currentTrack(): Track? = current
}
