package com.nao.md.project

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Membuka otorisasi Discord di **browser sistem** (bukan WebView).
 * Callback kembali ke app lewat DiscordOAuthCallbackActivity.
 */
class DiscordTokenLoginActivity : AppCompatActivity() {

    // Batasi skala teks sistem agar layout tidak pecah di HP dengan
    // pengaturan "ukuran font" besar.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(NaoUiCompat.wrapDisplay(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = NaoDiscordManager.beginLogin(this)
        Toast.makeText(
            this,
            if (ok) "Membuka browser untuk otorisasi Discord…"
            else "Tidak bisa membuka browser.",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
