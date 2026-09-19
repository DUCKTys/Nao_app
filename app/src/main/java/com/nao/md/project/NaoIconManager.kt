package com.nao.md.project

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icon presets only. Custom gallery icons are intentionally unsupported.
 */
object NaoIconManager {
    private const val PREFS = "nao_prefs"
    private const val PREF_ICON = "nao_launcher_icon"

    private fun alias(context: Context, number: Int) = ComponentName(
        context,
        "com.nao.md.project.NaoIcon${number}Alias"
    )

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_ICON, "1") ?: "1"

    fun currentPreset(context: Context): Int =
        current(context).toIntOrNull()?.coerceIn(1, 3) ?: 1

    fun applyPreset(context: Context, choice: Int) {
        if (choice !in 1..3) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_ICON, choice.toString()).apply()

        val pm = context.packageManager
        for (number in 1..3) {
            try {
                pm.setComponentEnabledSetting(
                    alias(context, number),
                    if (number == choice)
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
    }
}
