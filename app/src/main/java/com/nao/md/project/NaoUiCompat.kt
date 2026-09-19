package com.nao.md.project

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * Kompatibilitas tampilan:
 *
 * 1. [wrapDisplay] membatasi skala teks sistem supaya layout tidak pecah di HP
 *    yang memakai "ukuran font" sangat besar.
 * 2. [scrollable] membungkus isi dialog dengan ScrollView bertinggi maksimum,
 *    sehingga tombol paling bawah (mis. "Tutup") selalu bisa dijangkau.
 */
object NaoUiCompat {

    /** Batas atas skala font yang masih aman untuk layout aplikasi. */
    const val MAX_FONT_SCALE = 1.15f

    /**
     * Lebar layar logis (dp) yang dikunci untuk aplikasi ini, mengikuti
     * device referensi pengguna (Developer Options → Minimum width 424dp).
     * Diset 423dp — sedikit di bawah 424dp — supaya konsisten walau ada
     * pembulatan dp/px di device lain.
     */
    const val LOCKED_WIDTH_DP = 423f

    fun wrapDisplay(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        var changed = false

        if (config.fontScale > MAX_FONT_SCALE) {
            config.fontScale = MAX_FONT_SCALE
            changed = true
        }

        // "Ukuran tampilan" (Display size) di pengaturan sistem/Opsi
        // Pengembang sebenarnya cuma mengubah densitas layar, bukan
        // resolusi fisiknya — makanya app bisa terlihat lebih besar/kecil
        // saat pengaturan itu diubah pengguna. widthPixels di bawah ini
        // adalah resolusi fisik asli (tidak berubah oleh setting itu), jadi
        // dari situ kita hitung ulang densitas yang dibutuhkan supaya lebar
        // layar logis aplikasi selalu terkunci di LOCKED_WIDTH_DP dp,
        // apa pun "Ukuran tampilan" yang dipilih pengguna.
        val metrics = base.resources.displayMetrics
        val realWidthPx = metrics.widthPixels
        val realHeightPx = metrics.heightPixels
        if (realWidthPx > 0) {
            val targetDensityDpi =
                (realWidthPx * android.util.DisplayMetrics.DENSITY_DEFAULT / LOCKED_WIDTH_DP).toInt()
            if (targetDensityDpi > 0 && config.densityDpi != targetDensityDpi) {
                config.densityDpi = targetDensityDpi
                if (realHeightPx > 0) {
                    config.screenWidthDp = (realWidthPx * android.util.DisplayMetrics.DENSITY_DEFAULT / targetDensityDpi)
                    config.screenHeightDp = (realHeightPx * android.util.DisplayMetrics.DENSITY_DEFAULT / targetDensityDpi)
                }
                changed = true
            }
        }

        if (!changed) return base
        return base.createConfigurationContext(config)
    }

    /** ScrollView yang tingginya dibatasi sebagian dari tinggi layar. */
    class MaxHeightScrollView(
        context: Context,
        private val maxHeightRatio: Float = 0.86f,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (context.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            val spec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    /**
     * Bungkus [content] agar bisa di-scroll. Aman dipanggil berkali-kali:
     * kalau sudah dibungkus, view yang sama dikembalikan.
     */
    fun scrollable(content: View, maxHeightRatio: Float = 0.86f): View {
        val parent = content.parent
        if (parent is MaxHeightScrollView) return parent
        if (parent is ViewGroup) parent.removeView(content)
        return MaxHeightScrollView(content.context, maxHeightRatio).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }
}
