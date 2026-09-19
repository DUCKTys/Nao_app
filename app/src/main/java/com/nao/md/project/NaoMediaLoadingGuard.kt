package com.nao.md.project

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Status global "sedang pindah lagu", dipakai bersama oleh
 * [NaoMusicPlaybackService] (notifikasi media & panel media system Android
 * 14/15) dan [MainActivity] (proses resolve stream lagu berikutnya).
 *
 * Kegunaannya dua hal:
 *  1. Mencegah tap Next/Previous yang beruntun dari notifikasi atau panel
 *     media memicu dua proses ganti lagu sekaligus — sebelumnya ini bisa
 *     membuat satu lagu di antrean "termakan" diam-diam karena antrean
 *     sudah diambil duluan sebelum lagu pertama selesai di-resolve.
 *  2. Memberi tahu listener (notifikasi/panel media) supaya tombol
 *     Next/Previous ikut terlihat nonaktif + menampilkan status buffering
 *     selama lagu berikutnya sedang disiapkan.
 */
object NaoMediaLoadingGuard {
    @Volatile private var loading = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Klaim status loading. Return false kalau sudah ada proses lain berjalan. */
    fun begin(): Boolean {
        synchronized(this) {
            if (loading) return false
            loading = true
        }
        notifyListeners()
        return true
    }

    /** Lepas status loading. Aman dipanggil berkali-kali / dari mana saja. */
    fun finish() {
        synchronized(this) {
            if (!loading) return
            loading = false
        }
        notifyListeners()
    }

    fun isLoading(): Boolean = loading

    /** Dipanggil NaoMusicPlaybackService supaya notifikasi & panel media
     * ikut diperbarui begitu status loading berubah. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { it() }
        }
    }
}
