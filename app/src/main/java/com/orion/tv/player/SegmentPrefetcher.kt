package com.orion.tv.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the HLS segments *after* the playback position into [PlayerCache] while the network
 * is good, so playback keeps going through later dips and seeking ahead is instant. Runs from the
 * current position to the end of the episode, then the first part of the next episode.
 *
 * [client] should be the player's client so playlists pass through the same ad filter (the ad
 * segments are then never downloaded). [canRun] is polled between segments: it returns false
 * while the player itself is starved, so prefetching never competes with playback for bandwidth.
 */
class SegmentPrefetcher(
    private val client: OkHttpClient,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val canRun: () -> Boolean
) {
    private class Segment(val url: HttpUrl, val durationSec: Double)

    private var job: Job? = null
    @Volatile private var writer: CacheWriter? = null

    fun start(scope: CoroutineScope, playlistUrl: String, startPositionMs: Long, nextEpisodeUrl: String?) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                val segments = loadSegments(playlistUrl) ?: return@launch
                var elapsedSec = 0.0
                val startIndex = segments.indexOfFirst {
                    elapsedSec += it.durationSec
                    elapsedSec * 1000 > startPositionMs
                }.coerceAtLeast(0)
                download(segments.subList(startIndex, segments.size))

                if (nextEpisodeUrl != null && isActive) {
                    val next = loadSegments(nextEpisodeUrl) ?: return@launch
                    var headSec = 0.0
                    val head = next.takeWhile { segment ->
                        val within = headSec < NEXT_EPISODE_HEAD_SEC
                        headSec += segment.durationSec
                        within
                    }
                    download(head)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        writer?.cancel()
    }

    private suspend fun download(segments: List<Segment>) {
        var consecutiveFailures = 0
        for (segment in segments) {
            while (currentCoroutineContext().isActive && !canRun()) delay(2_000)
            if (!currentCoroutineContext().isActive) return
            val cacheWriter = CacheWriter(
                cacheDataSourceFactory.createDataSource(),
                DataSpec(Uri.parse(segment.url.toString())),
                null,
                null
            )
            writer = cacheWriter
            try {
                cacheWriter.cache()
                consecutiveFailures = 0
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
                delay(3_000)
            } finally {
                writer = null
            }
        }
    }

    /** Segments of the playlist (following a master playlist into its best variant), or null. */
    private fun loadSegments(url: String): List<Segment>? {
        val playlistUrl = url.toHttpUrlOrNull() ?: return null
        var baseUrl = playlistUrl
        var body = fetch(playlistUrl) ?: return null
        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines().map { it.trim() }
            var best: HttpUrl? = null
            var bestScore = -1L
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                val resolved = playlistUrl.resolve(uri) ?: continue
                val height = attribute(lines[i], "RESOLUTION")?.substringAfter('x', "")?.toLongOrNull() ?: 0L
                val bandwidth = attribute(lines[i], "BANDWIDTH")?.toLongOrNull() ?: 0L
                val score = height * 1_000_000_000L + bandwidth
                if (score > bestScore) {
                    bestScore = score
                    best = resolved
                }
            }
            baseUrl = best ?: return null
            body = fetch(baseUrl) ?: return null
        }
        val result = mutableListOf<Segment>()
        var duration = 0.0
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") ->
                    duration = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> {
                    baseUrl.resolve(line)?.let { result.add(Segment(it, duration)) }
                    duration = 0.0
                }
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Playlist text (capped at 2MB, must look like an m3u8), or null. */
    private fun fetch(url: HttpUrl): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_PLAYLIST_BYTES)
            val text = source.buffer.readUtf8(minOf(source.buffer.size, MAX_PLAYLIST_BYTES))
            if (text.trimStart().startsWith("#EXTM3U")) text else null
        }

    /** Value of `NAME=value` in an HLS attribute list (regex-free: java.util.regex can hard-crash old Android builds). */
    private fun attribute(line: String, name: String): String? {
        for (part in line.substringAfter(':', "").split(',')) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$name=")) return trimmed.substring(name.length + 1).trim('"')
        }
        return null
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val NEXT_EPISODE_HEAD_SEC = 180.0
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
