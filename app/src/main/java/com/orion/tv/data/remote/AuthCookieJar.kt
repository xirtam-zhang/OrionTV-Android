package com.orion.tv.data.remote

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * MoonTV auth is a single non-httpOnly cookie named "auth" (see /api/login). There's no bearer
 * token, so a normal persistent CookieJar is all that's needed to keep sessions across app
 * restarts and across the base-URL changes a user can make in Settings.
 */
class AuthCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        memoryCache[host] = cookies.toMutableList()
        persist(host, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return memoryCache.getOrPut(url.host) { loadPersisted(url.host).toMutableList() }
    }

    fun clear() {
        memoryCache.clear()
        prefs.edit().clear().apply()
    }

    private fun persist(host: String, cookies: List<Cookie>) {
        val serialized = cookies.map { "${it.name}=${it.value}" }.toSet()
        val hosts = (prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()).toMutableSet()
        hosts.add(host)
        prefs.edit()
            .putStringSet(KEY_COOKIES_PREFIX + host, serialized)
            .putStringSet(KEY_HOSTS, hosts)
            .apply()
    }

    private fun loadPersisted(host: String): List<Cookie> {
        val raw = prefs.getStringSet(KEY_COOKIES_PREFIX + host, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            Cookie.Builder()
                .name(entry.substring(0, idx))
                .value(entry.substring(idx + 1))
                .domain(host)
                .path("/")
                .build()
        }
    }

    companion object {
        private const val PREFS_NAME = "orion_cookies"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_COOKIES_PREFIX = "cookies_"
    }
}
