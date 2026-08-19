package com.orion.tv.util

object UrlUtils {

    private val IP_PORT_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$")

    /** Mirrors OrionTV's settings-save URL normalization (auto-scheme, trim trailing slash). */
    fun normalizeServerUrl(raw: String): String {
        var v = raw.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = if (v.matches(IP_PORT_REGEX)) "http://$v" else "https://$v"
        }
        return v.trimEnd('/')
    }
}
