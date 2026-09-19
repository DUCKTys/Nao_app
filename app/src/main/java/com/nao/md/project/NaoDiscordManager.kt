package com.nao.md.project

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Discord connection for Nao Music — ArchiveTune style.
 *
 * Primary path (same as ArchiveTune):
 *  - OAuth browser / Custom Tabs with openid + identify + sdk.social_layer_presence
 *  - Store the OAuth access_token (+ refresh)
 *  - Publish Listening via Gateway IDENTIFY using "Bearer <access_token>"
 *  - User never pastes a token; connect once and play music
 *
 * Optional fallback: pasted Discord account token (legacy / Kizzy-style).
 *
 * Application ID: 1539020121979355247
 * Redirect URI: discord-1539020121979355247:/authorize/callback
 */
object NaoDiscordManager {
    const val WEBSITE_URL = "https://duck-tys.vercel.app/"
    const val DEFAULT_APPLICATION_ID = "1539020121979355247"
    const val MOBILE_REDIRECT_URI = "discord-1539020121979355247:/authorize/callback"
    /**
     * Same scope set ArchiveTune uses for Gateway presence.
     * Browser / Custom Tabs (not Discord in-app AUTHORIZE) accept `identify`
     * together with `sdk.social_layer_presence`.
     */
    const val OAUTH_SCOPES = "openid identify sdk.social_layer_presence"

    /**
     * Nao Music branding shown as the small image of the Rich Presence card.
     * Ganti URL ini jika logo dipindahkan; jika tidak dapat diakses, presence
     * tetap tampil tanpa small image.
     */
    const val BRANDING_IMAGE_URL = "https://duck-tys.vercel.app/nao_music_icon.png"


    const val EXTRA_AUTH_RESULT = "discord_auth_result"
    const val EXTRA_AUTH_ERROR = "discord_auth_error"

    private const val PREFS = "nao_prefs"
    private const val PREF_CONNECTED = "discord_connected"
    private const val PREF_RPC_ENABLED = "discord_rpc_enabled"
    private const val PREF_VERIFIER = "discord_pkce_verifier"
    private const val PREF_STATE = "discord_oauth_state"
    private const val PREF_ACCESS = "discord_access_token"
    private const val PREF_REFRESH = "discord_refresh_token"
    private const val PREF_EXPIRES_AT = "discord_expires_at"
    private const val PREF_USER_ID = "discord_user_id"
    private const val PREF_USERNAME = "discord_username"
    private const val PREF_DISPLAY_NAME = "discord_display_name"
    private const val PREF_AVATAR = "discord_avatar"
    private const val PREF_USER_TOKEN = "discord_user_token"
    private const val PREF_SHOW_WHEN_PAUSED = "discord_show_when_paused"

    const val DISCORD_PACKAGE = "com.discord"


    fun isDiscordAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun showWhenPaused(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_SHOW_WHEN_PAUSED, false)

    fun setShowWhenPaused(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SHOW_WHEN_PAUSED, enabled).apply()
    }

    fun isConnected(context: Context): Boolean {
        // Account token alone is enough for presence (ArchiveTune / Kizzy path).
        if (userToken(context).isNotBlank()) return true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = p.getString(PREF_ACCESS, null).orEmpty()
        val connected = p.getBoolean(PREF_CONNECTED, false)
        if (!connected || access.isBlank()) return false
        val expiresAt = p.getLong(PREF_EXPIRES_AT, 0L)
        if (expiresAt == 0L || expiresAt > System.currentTimeMillis()) return true
        // Access token expired, but a refresh token can still restore the session.
        return p.getString(PREF_REFRESH, null).orEmpty().isNotBlank()
    }

    /** True when the Discord *application* was linked via OAuth browser flow. */
    fun isAppLinked(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(PREF_CONNECTED, false)) return false
        return p.getString(PREF_ACCESS, null).orEmpty().isNotBlank() ||
            p.getString(PREF_REFRESH, null).orEmpty().isNotBlank()
    }

    fun isPresenceReady(context: Context): Boolean =
        !presenceToken(context).isNullOrBlank()

    fun isRichPresenceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_RPC_ENABLED, true)

    fun setRichPresenceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_RPC_ENABLED, enabled).apply()
    }

    fun configuredClientId(context: Context): String = DEFAULT_APPLICATION_ID

    fun accountName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_DISPLAY_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_USERNAME, null).orEmpty()

    fun accountUsername(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_USERNAME, null).orEmpty()

    fun accountId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_USER_ID, null).orEmpty()

    fun accountAvatar(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_AVATAR, null).orEmpty()

    private fun randomUrlSafe(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(
            raw,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun beginLogin(context: Context): Boolean {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(32)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_VERIFIER, verifier)
            .putString(PREF_STATE, state)
            .apply()

        val target = Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", DEFAULT_APPLICATION_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", MOBILE_REDIRECT_URI)
            .appendQueryParameter("scope", OAUTH_SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkceChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return openAuthorizeUrl(context, target)
    }

    /**
     * Open the authorize page in a browser / Custom Tab.
     *
     * Do **not** `setPackage(com.discord)`. Discord's Android app intercepts
     * discord.com/oauth2/authorize and runs its RPC AUTHORIZE command, which
     * does not accept Social SDK scopes and pops:
     * "The requested scope is invalid, unknown, or malformed."
     * Custom Tabs (or the default browser) is the supported path; Discord
     * then returns to `discord-{APP_ID}:/authorize/callback`.
     */
    private fun openAuthorizeUrl(context: Context, target: Uri): Boolean {
        val browser = preferredBrowserPackage(context)
        try {
            val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (browser != null) tabs.intent.setPackage(browser)
            tabs.launchUrl(context, target)
            return true
        } catch (_: Exception) {
        }
        return try {
            val view = Intent(Intent.ACTION_VIEW, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            if (browser != null) view.setPackage(browser)
            context.startActivity(view)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun preferredBrowserPackage(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/oauth2/authorize"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val packages = context.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != DISCORD_PACKAGE && !it.startsWith("com.discord") }
            .distinct()
        val preferred = listOf(
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx"
        )
        return preferred.firstOrNull { it in packages } ?: packages.firstOrNull()
    }

    /**
     * Validates the deep link, exchanges the code, verifies the account with /users/@me,
     * stores the session, then brings MainActivity back to the foreground.
     */
    fun handleOAuthCallback(context: Context, uri: Uri): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val expected = prefs.getString(PREF_STATE, null)

        if (uri.scheme != "discord-1539020121979355247" ||
            uri.path != "/authorize/callback" ||
            state.isNullOrBlank() ||
            expected.isNullOrBlank() ||
            state != expected
        ) {
            clearPending(prefs)
            launchResult(context, false, "Callback Discord tidak valid.")
            return false
        }

        uri.getQueryParameter("error")?.let { error ->
            clearPending(prefs)
            val description = uri.getQueryParameter("error_description")
            launchResult(context, false, description ?: error)
            return false
        }

        if (code.isNullOrBlank()) {
            clearPending(prefs)
            launchResult(context, false, "Authorization code Discord tidak ditemukan.")
            return false
        }

        val verifier = prefs.getString(PREF_VERIFIER, null)
        if (verifier.isNullOrBlank()) {
            clearPending(prefs)
            launchResult(context, false, "PKCE verifier tidak ditemukan.")
            return false
        }

        Thread {
            try {
                val token = exchangeAuthorizationCode(code, verifier)
                val access = token.optString("access_token")
                if (access.isBlank()) {
                    throw IllegalStateException(
                        token.optString(
                            "error_description",
                            token.optString("error", "Discord tidak memberikan access token.")
                        )
                    )
                }

                val user = getCurrentUser(access)
                val userId = user.optString("id")
                val username = user.optString("username")
                val globalName = user.optString("global_name")
                if (userId.isBlank() || username.isBlank()) {
                    throw IllegalStateException("Data akun Discord tidak lengkap.")
                }

                val expiresIn = token.optLong("expires_in", 604800L).coerceAtLeast(60L)
                prefs.edit()
                    .putBoolean(PREF_CONNECTED, true)
                    .putString(PREF_ACCESS, access)
                    .putString(PREF_REFRESH, token.optString("refresh_token"))
                    .putLong(PREF_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                    .putString(PREF_USER_ID, userId)
                    .putString(PREF_USERNAME, username)
                    .putString(PREF_DISPLAY_NAME, globalName.ifBlank { username })
                    .putString(PREF_AVATAR, user.optString("avatar"))
                    .remove(PREF_VERIFIER)
                    .remove(PREF_STATE)
                    .apply()

                launchResult(context, true, null)
            } catch (e: Exception) {
                prefs.edit().putBoolean(PREF_CONNECTED, false).apply()
                clearPending(prefs)
                launchResult(context, false, e.message ?: "Discord OAuth gagal.")
            }
        }.start()
        return true
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): JSONObject {
        val body = form(
            "client_id" to DEFAULT_APPLICATION_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to MOBILE_REDIRECT_URI,
            "code_verifier" to verifier
        )
        return postJson("https://discord.com/api/v10/oauth2/token", body)
    }

    private fun getCurrentUser(accessToken: String): JSONObject =
        requestJson(
            "https://discord.com/api/v10/users/@me",
            "GET",
            null,
            accessToken
        )

    private fun refreshToken(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val refresh = prefs.getString(PREF_REFRESH, null).orEmpty()
        if (refresh.isBlank()) return false
        return try {
            val token = postJson(
                "https://discord.com/api/v10/oauth2/token",
                form(
                    "client_id" to DEFAULT_APPLICATION_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh
                )
            )
            val access = token.optString("access_token")
            if (access.isBlank()) return false
            val expiresIn = token.optLong("expires_in", 604800L).coerceAtLeast(60L)
            prefs.edit()
                .putBoolean(PREF_CONNECTED, true)
                .putString("discord_access_token", access)
                .putString("discord_refresh_token", token.optString("refresh_token", refresh))
                .putLong(PREF_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                .apply()
            true
        } catch (_: Exception) {
            prefs.edit().putBoolean(PREF_CONNECTED, false).apply()
            false
        }
    }

    fun getValidAccessToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_CONNECTED, false)) return null
        val token = prefs.getString(PREF_ACCESS, null).orEmpty()
        val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
        if (token.isBlank()) return null
        if (expiresAt == 0L || System.currentTimeMillis() + 60_000L < expiresAt) return token
        return if (refreshToken(context)) {
            prefs.getString(PREF_ACCESS, null)
        } else null
    }

    fun setConnected(context: Context, connected: Boolean) {
        if (!connected) disconnect(context)
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_CONNECTED)
            .remove(PREF_ACCESS)
            .remove(PREF_REFRESH)
            .remove(PREF_EXPIRES_AT)
            .remove(PREF_USER_ID)
            .remove(PREF_USERNAME)
            .remove(PREF_DISPLAY_NAME)
            .remove(PREF_AVATAR)
            .remove(PREF_USER_TOKEN)
            .remove("discord_presence_payload")
            .apply()
        NaoDiscordRpc.disconnect()
    }

    private fun clearPending(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(PREF_VERIFIER).remove(PREF_STATE).apply()
    }

    private fun launchResult(context: Context, success: Boolean, error: String?) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AUTH_RESULT, success)
        if (error != null) intent.putExtra(EXTRA_AUTH_ERROR, error)
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun postJson(url: String, body: String): JSONObject =
        requestJson(url, "POST", body, null)

    private fun requestJson(
        url: String,
        method: String,
        body: String?,
        bearer: String?
    ): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = method == "POST"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (status !in 200..299) {
            val msg = try {
                val j = JSONObject(raw)
                j.optString("error_description", j.optString("message", raw))
            } catch (_: Exception) { raw }
            throw IllegalStateException("HTTP $status: $msg")
        }
        return JSONObject(raw)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") {
            "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}"
        }

    fun openWebsite(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)))
        true
    } catch (_: Exception) { false }

    /**
     * Rich Presence token (Discord account token).
     *
     * Android has no local Discord IPC socket and the OAuth2 `identify` scope is
     * not allowed to publish activities, so the gateway transport needs the
     * account token to show "Listening to Nao Music".
     */
    fun userToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_USER_TOKEN, null).orEmpty().trim()

    fun setUserToken(context: Context, token: String) {
        val clean = token.trim()
            .removePrefix("Bearer ")
            .removeSurrounding("\"")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_USER_TOKEN, clean)
            .putBoolean(PREF_CONNECTED, clean.isNotBlank())
            .apply()
        if (clean.isBlank()) {
            NaoDiscordRpc.disconnect()
        } else {
            // Account token is what makes Listening appear — enable RPC by default.
            setRichPresenceEnabled(context, true)
        }
    }

    fun isRichPresenceLive(): Boolean = NaoDiscordRpc.connected

    /**
     * Token used by the gateway transport (ArchiveTune path).
     *
     * Prefer the OAuth access token from browser connect. Optional fallback:
     * a pasted account token. [NaoDiscordRpc] prefixes OAuth tokens with
     * "Bearer " the same way ArchiveTune's GatewayClient does.
     */
    fun presenceToken(context: Context): String? {
        val oauth = getValidAccessToken(context)
        if (!oauth.isNullOrBlank()) return oauth
        val user = userToken(context)
        return user.takeIf { it.isNotBlank() }
    }

    /** True when the active presence credential is an OAuth access token. */
    fun isOauthPresenceToken(context: Context): Boolean {
        if (!getValidAccessToken(context).isNullOrBlank()) return true
        return false
    }

    /**
     * Authorization header value for Gateway IDENTIFY and external-assets.
     * OAuth → "Bearer <access>"; account token → raw value (Kizzy-style).
     */
    fun gatewayAuthorization(context: Context): String? {
        val oauth = getValidAccessToken(context)
        if (!oauth.isNullOrBlank()) {
            return if (oauth.startsWith("Bearer ", ignoreCase = true)) oauth else "Bearer $oauth"
        }
        val user = userToken(context)
        return user.takeIf { it.isNotBlank() }
    }

    fun richPresenceStatus(context: Context): String = when {
        presenceToken(context).isNullOrBlank() ->
            "Belum terhubung. Tekan Hubungkan Discord (browser) — token disimpan otomatis seperti ArchiveTune."
        !isRichPresenceEnabled(context) -> "Rich Presence dimatikan."
        NaoDiscordRpc.connected -> "Rich Presence aktif · Listening to Nao Music tampil di Discord."
        NaoDiscordRpc.lastError != null -> "Gagal: ${NaoDiscordRpc.lastError}"
        else -> "Menyambungkan ke Discord gateway…"
    }

    fun updateForTrack(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        paused: Boolean,
        durationMs: Long = 0L,
        positionMs: Long = 0L,
        album: String = ""
    ) {
        if (!isRichPresenceEnabled(context)) return
        val payload = JSONObject().apply {
            put("application_id", DEFAULT_APPLICATION_ID)
            put("details", title)
            put("state", if (paused) "Paused · $artist" else artist)
            put("large_image_url", artworkUrl)
            put("small_image_url", BRANDING_IMAGE_URL)
            put("album", album)
            put("button_label", "Open Nao Music")
            put("button_url", WEBSITE_URL)
            put("platform", "Android")
            put("source", "Nao Music")
            put("rich_presence_version", "3")
            put("position_ms", positionMs)
            put("duration_ms", durationMs)
            put("timestamp", System.currentTimeMillis())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("discord_presence_payload", payload.toString()).apply()

        // Real transport: publish the activity through the Discord gateway.
        NaoDiscordRpc.setActivity(
            context,
            NaoDiscordRpc.Activity(
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
                paused = paused,
                durationMs = durationMs,
                positionMs = positionMs,
                album = album
            )
        )
    }


    fun clearPresence(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("discord_presence_payload").apply()
        NaoDiscordRpc.clear()
    }
}

