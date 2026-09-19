package com.nao.md.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.File

/**
 * Keeps Nao Music's playback process at foreground priority while the app UI
 * is in the background. The actual ExoPlayer remains owned by MainActivity;
 * this service is intentionally lightweight and returns START_STICKY.
 */
class MusicKeepAliveService : Service() {
    private var playbackWakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "nao_music_playback"
        private const val NOTIFICATION_ID = 9101
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquirePlaybackWakeLock()
        startForegroundWithCurrentTrack()
    }

    private fun startForegroundWithCurrentTrack() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val title = getSharedPreferences("nao_prefs", MODE_PRIVATE)
            .getString("music_current_title", "Nao Music") ?: "Nao Music"
        val artist = getSharedPreferences("nao_prefs", MODE_PRIVATE)
            .getString("music_current_artist", "") ?: ""
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.nao_music)
            .setContentTitle(title)
            .setContentText(artist.ifBlank { "Nao Music" })
            .setLargeIcon(loadArtwork())
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { playbackWakeLock?.release() } catch (_: Exception) {}
        playbackWakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun acquirePlaybackWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            playbackWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "NaoMD:NaoMusicPlayback"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }


    private fun loadArtwork(): Bitmap? {
        return try {
            BitmapFactory.decodeFile(File(filesDir, "nao_music_artwork.png").absolutePath)
                ?: BitmapFactory.decodeFile(File(filesDir, "nao_music_artwork.jpg").absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Nao Music Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menjaga playback Nao Music tetap aktif saat aplikasi di background."
                }
            )
        }
    }
}
