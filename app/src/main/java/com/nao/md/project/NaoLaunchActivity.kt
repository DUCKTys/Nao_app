package com.nao.md.project

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Lightweight launcher hand-off. Intentionally does NOT use AndroidX SplashScreen.
 * The platform launch surface is iconless; this activity owns the visible Nao motion
 * so the launcher icon can never flash into a different splash icon.
 */
class NaoLaunchActivity : Activity() {

    // Batasi skala teks sistem agar layout tidak pecah di HP dengan
    // pengaturan "ukuran font" besar.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(NaoUiCompat.wrapDisplay(newBase))
    }
    private val handler = Handler(Looper.getMainLooper())
    private var handedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(com.nao.md.project.R.color.nao_splash_bg)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 6, 10))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
        }
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.30f)
            .toInt().coerceIn(dp(92), dp(180))
        root.addView(logo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        setContentView(root)

        loadCurrentLogo(logo)

        // Start the visible motion immediately after the first custom frame.
        root.post {
            logo.animate()
                .alpha(1f)
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    logo.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { handOffToMain() }
                        .start()
                }
                .start()
        }
    }

    private fun loadCurrentLogo(target: ImageView) {
        val id = when (NaoIconManager.currentPreset(this)) {
            2 -> R.drawable.nao_icon_2
            3 -> R.drawable.nao_icon_3
            else -> R.drawable.nao_icon_1
        }
        target.setImageResource(id)
    }

    private fun handOffToMain() {
        if (handedOff) return
        handedOff = true
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        overridePendingTransition(0, 0)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
