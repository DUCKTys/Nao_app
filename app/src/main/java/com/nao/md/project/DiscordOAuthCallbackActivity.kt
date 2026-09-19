package com.nao.md.project

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives Discord's mobile OAuth deep-link and hands the code to
 * NaoDiscordManager for PKCE token exchange.
 */
class DiscordOAuthCallbackActivity : Activity() {

    // Batasi skala teks sistem agar layout tidak pecah di HP dengan
    // pengaturan "ukuran font" besar.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(NaoUiCompat.wrapDisplay(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { NaoDiscordManager.handleOAuthCallback(this, it) }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { NaoDiscordManager.handleOAuthCallback(this, it) }
        finish()
    }
}