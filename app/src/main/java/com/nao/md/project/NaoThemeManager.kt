package com.nao.md.project

import android.content.Context
import android.graphics.Color

/**
 * Tema warna aksen Nao MD — graffiti palette (Ungu Neon / Pink Soft / Merah Maroon),
 * mengikuti sistem tema di website Nao Music (theme.tsx / styles.css).
 *
 * Setiap tema menyediakan 5 warna semantik yang dipakai di seluruh MainActivity
 * menggantikan literal Color.rgb(...) yang sebelumnya hardcoded ke warna ungu:
 *  - ring       : warna solid utama (dipakai untuk glow ring dgn alpha, gradient dot, dsb)
 *  - bright     : varian terang untuk teks/ikon/highlight di atas background gelap
 *  - veryLight  : varian nyaris putih untuk background tombol/segment primer
 *  - mid        : varian gelap untuk ripple/pressed state
 *  - dark       : varian gelap untuk background item terpilih / tab aktif
 */
enum class NaoTheme(
    val id: String,
    val label: String,
    val desc: String,
    val ring: Int,
    val bright: Int,
    val veryLight: Int,
    val mid: Int,
    val dark: Int,
    val softCard: Boolean = false,
) {
    UNGU(
        id = "ungu",
        label = "Ungu Neon",
        desc = "Semprotan violet elektrik di tembok malam",
        ring = Color.rgb(139, 92, 246),
        bright = Color.rgb(183, 170, 255),
        veryLight = Color.rgb(236, 233, 255),
        mid = Color.rgb(74, 66, 105),
        dark = Color.rgb(42, 35, 70),
    ),
    PINK(
        id = "pink",
        label = "Pink Soft",
        desc = "Rona pink lembut, hangat dan santai",
        ring = Color.rgb(255, 105, 180),
        bright = Color.rgb(255, 143, 197),
        veryLight = Color.rgb(255, 214, 230),
        mid = Color.rgb(190, 24, 93),
        dark = Color.rgb(150, 20, 80),
        softCard = true,
    ),
    MAROON(
        id = "maroon",
        label = "Merah Maroon",
        desc = "Darah bata, tag klasik old school",
        ring = Color.rgb(194, 21, 47),
        bright = Color.rgb(252, 69, 64),
        veryLight = Color.rgb(254, 208, 207),
        mid = Color.rgb(93, 23, 39),
        dark = Color.rgb(55, 23, 37),
    );

    companion object {
        fun fromId(id: String?): NaoTheme = entries.firstOrNull { it.id == id } ?: UNGU
    }
}

object NaoThemeManager {
    private const val PREFS = "nao_prefs"
    private const val PREF_THEME = "nao_theme_choice"

    fun current(context: Context): NaoTheme =
        NaoTheme.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_THEME, NaoTheme.UNGU.id)
        )

    fun applyPreset(context: Context, theme: NaoTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_THEME, theme.id).apply()
    }

    /**
     * Mewarnai ulang warna netral gelap (background kotak/card/tab/track yang dulu
     * selalu abu-navy statis apa pun temanya) supaya BENAR-BENAR mengikuti hue warna
     * tema yang aktif — bukan cuma disentuh sedikit seperti sebelumnya.
     *
     * Untuk tema dengan [NaoTheme.softCard] = true (mis. Pink Soft), kotak dibuat
     * lebih terang & lembut (pastel) supaya kebalikan dari tombol/state aktif yang
     * sengaja dibuat pink gelap & pekat lewat [NaoTheme.dark]/[NaoTheme.mid].
     * Tema lain tetap pakai gaya lama: kotak gelap & jenuh mengikuti hue tema.
     */
    fun tintBox(context: Context, neutral: Int): Int = tintBox(current(context), neutral)

    fun tintBox(theme: NaoTheme, neutral: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(neutral, hsv)
        val themeHsv = FloatArray(3)
        Color.colorToHSV(theme.ring, themeHsv)
        return if (theme.softCard) {
            // Pastel: saturasi disaring lebih rendah, kecerahan dinaikkan jauh lebih
            // tinggi (dengan lantai tersendiri) supaya kotak kelihatan lembut & cerah,
            // bukan gelap pekat seperti tombol.
            val saturation = themeHsv[1].coerceIn(0.48f, 0.62f)
            // Jaga kartu tetap lembut tetapi cukup cerah supaya pink tetap kelihatan
            // jelas & berwarna — bukan redup seperti tidak berwarna.
            val value = (hsv[2] * 0.72f + 0.34f).coerceIn(0.42f, 0.66f)
            Color.HSVToColor(floatArrayOf(themeHsv[0], saturation, value))
        } else {
            // Saturasi dipatok minimal 0.75 — kalau tidak, warna aksen yang lembut/
            // pastel bakal jatuh jadi kusam/kecoklatan begitu digelapkan untuk jadi
            // background kotak. Dengan saturasi tinggi, huenya tetap jelas terbaca.
            val saturation = themeHsv[1].coerceAtLeast(0.75f)
            val value = (hsv[2] * 1.8f + 0.05f).coerceIn(hsv[2], 0.68f)
            Color.HSVToColor(floatArrayOf(themeHsv[0], saturation, value))
        }
    }

    /** Sama seperti [tintBox], tapi mempertahankan channel alpha asli (untuk overlay/scrim). */
    fun tintBoxArgb(context: Context, alpha: Int, r: Int, g: Int, b: Int): Int {
        val tinted = tintBox(context, Color.rgb(r, g, b))
        return Color.argb(alpha, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
    }
}
