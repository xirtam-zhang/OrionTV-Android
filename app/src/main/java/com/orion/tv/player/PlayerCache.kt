package com.orion.tv.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide on-disk video cache shared by the player and [SegmentPrefetcher]. Storage is cheap
 * here, so the cap is generous (up to [MAX_CACHE_BYTES], but never more than a third of the free
 * space), with LRU eviction. On top of that the whole cache is wiped every [CLEAN_INTERVAL_MS] so
 * stale videos don't pile up forever; [cleanIfDue] must run before the first [get].
 */
object PlayerCache {

    private const val DIR_NAME = "orion_video_cache"
    private const val PREFS = "orion_video_cache_prefs"
    private const val KEY_LAST_CLEAN = "last_clean_at"
    private const val CLEAN_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_CACHE_BYTES = 10L * 1024 * 1024 * 1024

    private var cache: SimpleCache? = null

    /** Wipes the cache directory if the last wipe was more than a week ago. Call from Application.onCreate. */
    @Synchronized
    fun cleanIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CLEAN, 0L)
        if (last == 0L) {
            prefs.edit().putLong(KEY_LAST_CLEAN, now).apply()
            return
        }
        if (now - last < CLEAN_INTERVAL_MS) return
        if (cache == null) File(context.cacheDir, DIR_NAME).deleteRecursively()
        prefs.edit().putLong(KEY_LAST_CLEAN, now).apply()
    }

    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val limit = minOf(MAX_CACHE_BYTES, (dir.usableSpace / 3).coerceAtLeast(256L * 1024 * 1024))
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(limit), StandaloneDatabaseProvider(context.applicationContext))
            .also { cache = it }
    }
}
