package com.orion.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import com.orion.tv.util.UrlUtils

/**
 * Mirrors OrionTV's AsyncStorage-backed AppSettings: server base URL, M3U live source URL,
 * ad-filter toggle, and per-source enable map.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverBaseUrl: String?
        get() = prefs.getString(KEY_SERVER_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_BASE_URL, normalize(value)).apply()

    var m3uUrl: String?
        get() = prefs.getString(KEY_M3U_URL, null)
        set(value) = prefs.edit().putString(KEY_M3U_URL, value).apply()

    var adFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_FILTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_FILTER_ENABLED, value).apply()

    fun isSourceEnabled(sourceKey: String): Boolean =
        prefs.getBoolean(KEY_SOURCE_PREFIX + sourceKey, true)

    fun setSourceEnabled(sourceKey: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOURCE_PREFIX + sourceKey, enabled).apply()
    }

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return UrlUtils.normalizeServerUrl(url)
    }

    companion object {
        private const val PREFS_NAME = "orion_settings"
        private const val KEY_SERVER_BASE_URL = "server_base_url"
        private const val KEY_M3U_URL = "m3u_url"
        private const val KEY_AD_FILTER_ENABLED = "ad_filter_enabled"
        private const val KEY_SOURCE_PREFIX = "source_enabled_"
    }
}
