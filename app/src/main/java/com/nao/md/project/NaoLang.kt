package com.nao.md.project

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Runtime UI translation for the hand-built Nao MD interface.
 *
 * The Nao screens are created in code with hardcoded strings, so switching the
 * app locale through AppCompatDelegate alone never changed a single label.
 * This object loads assets/nao_lang.json (source string -> translation for each
 * supported language) and every UI helper routes its text through [t].
 */
object NaoLang {

    private var loadedTag: String? = null
    private var table: Map<String, String> = emptyMap()
    private var catalog: JSONObject? = null

    /** Language tags shipped in assets/nao_lang.json. */
    private val supported = listOf(
        "en", "in", "ja", "ko", "zh-CN", "zh-TW", "es", "fr", "de", "it", "nl",
        "pt-BR", "ru", "uk", "tr", "ar", "hi", "th", "vi", "ms", "tl"
    )

    fun savedTag(context: Context): String =
        context.applicationContext
            .getSharedPreferences("nao_prefs", Context.MODE_PRIVATE)
            .getString("app_language", "") ?: ""

    /** Loads the table for [tag]; a blank tag follows the system language. */
    fun load(context: Context, tag: String) {
        val resolved = resolve(if (tag.isBlank()) systemTag(context) else tag)
        if (loadedTag == resolved) return
        loadedTag = resolved
        table = emptyMap()
        if (resolved == null) return
        try {
            val root = catalog ?: JSONObject(
                context.applicationContext.assets.open("nao_lang.json")
                    .bufferedReader().use { it.readText() }
            ).also { catalog = it }
            val obj = root.optJSONObject(resolved) ?: return
            val out = HashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                if (v.isNotBlank()) out[k] = v
            }
            table = out
        } catch (_: Exception) {
            table = emptyMap()
        }
    }

    /** Translates a source label, falling back to the original text. */
    fun t(s: String): String = if (s.isBlank()) s else table[s] ?: s

    private fun systemTag(context: Context): String {
        val locales = context.resources.configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        val language = locale.language.lowercase(Locale.US)
        val country = locale.country.uppercase(Locale.US)
        return if (country.isBlank()) language else "$language-$country"
    }

    /** Maps a BCP-47 tag onto the closest shipped language. */
    private fun resolve(tag: String): String? {
        if (tag.isBlank()) return null
        val normalized = tag.replace('_', '-')
        supported.firstOrNull { it.equals(normalized, true) }?.let { return it }
        val language = normalized.substringBefore('-').lowercase(Locale.US)
        val region = normalized.substringAfter('-', "").uppercase(Locale.US)
        // Indonesian ships under both the legacy "in" and modern "id" codes.
        if (language == "id" || language == "in") return "in"
        if (language == "zh") {
            return if (region == "TW" || region == "HK" || region == "MO") "zh-TW" else "zh-CN"
        }
        if (language == "pt") return "pt-BR"
        if (language == "fil") return "tl"
        return supported.firstOrNull { it.substringBefore('-') == language }
    }
}
