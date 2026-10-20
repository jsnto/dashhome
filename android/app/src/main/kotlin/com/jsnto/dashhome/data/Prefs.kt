package com.jsnto.dashhome.data

import android.content.Context

/**
 * The app's client-side persistence: the dashhome server URL and the
 * user's light/dark theme choice (the web persists it in
 * localStorage['dashhome-theme']). The dashboard has no login, so these
 * are the only client-side settings; they live in SharedPreferences and
 * are restored into the app on startup (MainActivity) and when the app
 * (re)connects.
 */
object Prefs {

    private const val FILE = "dashhome"
    private const val KEY_BASE = "server_url"
    private const val KEY_THEME = "theme"

    private fun store(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The saved server URL; "" when no server is set. */
    fun base(context: Context): String =
        store(context).getString(KEY_BASE, "").orEmpty()

    fun setBase(context: Context, url: String) {
        store(context).edit().putString(KEY_BASE, url).apply()
    }

    fun hasServer(context: Context): Boolean =
        base(context).isNotBlank()

    fun clear(context: Context) {
        store(context).edit().remove(KEY_BASE).apply()
    }

    /**
     * The user's explicit theme choice ("light" or "dark"), or null when
     * the user never picked (the OS preference applies and is followed
     * live) - the web's readStoredTheme.
     */
    fun theme(context: Context): String? {
        val t = store(context).getString(KEY_THEME, null)
        return if (t == "light" || t == "dark") t else null
    }

    /** Persist the user's theme choice (the web's storeTheme). */
    fun setTheme(context: Context, theme: String) {
        if (theme == "light" || theme == "dark") {
            store(context).edit().putString(KEY_THEME, theme).apply()
        }
    }
}
