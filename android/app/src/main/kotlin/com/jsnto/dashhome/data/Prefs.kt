package com.jsnto.dashhome.data

import android.content.Context

/**
 * The app's client-side persistence: the dashhome server URL. The
 * dashboard has no login, so the URL is the only client-side setting; it
 * lives in SharedPreferences and is restored into the API client on
 * startup (MainActivity) and when the app (re)connects.
 */
object Prefs {

    private const val FILE = "dashhome"
    private const val KEY_BASE = "server_url"

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
}
