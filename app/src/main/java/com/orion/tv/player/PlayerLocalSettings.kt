package com.orion.tv.player

import android.content.Context

/**
 * Per-video intro/outro markers. Mirrors OrionTV's PlayerSettingsManager: these are always kept
 * on-device only (never synced to the server), regardless of the MoonTV storage mode.
 */
class PlayerLocalSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getIntroEndMs(source: String, id: String): Long? = get(introKey(source, id))

    fun setIntroEndMs(source: String, id: String, millis: Long) {
        prefs.edit().putLong(introKey(source, id), millis).apply()
    }

    fun getOutroStartMs(source: String, id: String): Long? = get(outroKey(source, id))

    fun setOutroStartMs(source: String, id: String, millis: Long) {
        prefs.edit().putLong(outroKey(source, id), millis).apply()
    }

    /** Persists the last-selected playback rate per title, mirroring OrionTV 1.3.3's PlayerSettings.playbackRate. */
    fun getPlaybackRate(source: String, id: String): Float =
        prefs.getFloat(rateKey(source, id), 1.0f)

    fun setPlaybackRate(source: String, id: String, rate: Float) {
        prefs.edit().putFloat(rateKey(source, id), rate).apply()
    }

    private fun get(key: String): Long? {
        if (!prefs.contains(key)) return null
        return prefs.getLong(key, 0L)
    }

    private fun introKey(source: String, id: String) = "intro_${source}_$id"
    private fun outroKey(source: String, id: String) = "outro_${source}_$id"
    private fun rateKey(source: String, id: String) = "rate_${source}_$id"

    companion object {
        private const val PREFS_NAME = "orion_player_settings"
    }
}
