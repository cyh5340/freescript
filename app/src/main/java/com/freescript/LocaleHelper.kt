package com.freescript

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

object LocaleHelper {

    private const val PREFS     = "freescript_settings"
    private const val KEY_LANG  = "app_language"   // "en", "zh", or "" (system default)
    private const val KEY_THEME = "app_theme"

    // ── Language ───────────────────────────────────────────────────────────

    fun getLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""

    fun setLanguage(ctx: Context, lang: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()

    /** Wraps the base Context with the user-selected locale for attachBaseContext overrides. */
    fun wrap(base: Context): Context {
        val lang = getLanguage(base)
        if (lang.isEmpty()) return base
        val locale = if (lang == "zh") Locale("zh", "TW") else Locale.ENGLISH
        Locale.setDefault(locale)
        val config = base.resources.configuration
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    // ── Theme ──────────────────────────────────────────────────────────────

    fun getNightMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun applyNightMode(ctx: Context) =
        AppCompatDelegate.setDefaultNightMode(getNightMode(ctx))

    fun setNightMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

}

