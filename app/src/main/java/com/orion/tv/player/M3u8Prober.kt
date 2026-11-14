package com.orion.tv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * @property speedBytesPerSec measured download throughput of a real media segment, null if the test failed
 * @property resolutionHeight video height (e.g. 1080), null if the playlist doesn't declare it
 * @property durationSec total playlist duration of the first episode, null if unknown
 */
data class ProbeResult(
    val speedBytesPerSec: Long?,
    val resolutionHeight: Int?,
    val durationSec: Int?
) {
    val failed: Boolean get() = speedBytesPerSec == null
}

/**
 * Real source speed test. Fetches a source's first-episode m3u8 (following a master playlist into
 * its best variant), sums the EXTINF durations, then downloads up to [SAMPLE_BYTES] of an actual
 * video segment and measures the throughput. The resolution comes from the master playlist's
 * `#EXT-X-STREAM-INF RESOLUTION=` tag.
 *
 * Playlist parsing is deliberately regex-free: java.util.regex hard-crashes the whole process
 * (JNI abort in Matcher.setInputImpl) on some older Android builds when fed a large or odd body.
 */
object M3u8Prober {

    private const val SAMPLE_BYTES = 1_500_000L
    private const val SAMPLE_MAX_MS = 5_000L
    private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private class Variant(val url: HttpUrl, val height: Int?, val bandwidth: Long)

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val playlistUrl = url.toHttpUrlOrNull() ?: return@withContext ProbeResult(null, null, null)
        try {
            doProbe(playlistUrl)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ProbeResult(null, null, null)
        }
    }

    /** Playlist text, or null if the request failed or the body isn't an m3u8 (e.g. an HTML error page). */
    private fun fetchPlaylist(url: HttpUrl): String? =
        probeClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_PLAYLIST_BYTES)
            val text = source.buffer.readUtf8(minOf(source.buffer.size, MAX_PLAYLIST_BYTES))
            if (text.trimStart().startsWith("#EXTM3U")) text else null
        }

    private fun doProbe(masterUrl: HttpUrl): ProbeResult {
        val body = fetchPlaylist(masterUrl) ?: return ProbeResult(null, null, null)

        var mediaUrl = masterUrl
        var mediaBody = body
        var height: Int? = null

        if (body.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(masterUrl, body)
            height = variants.mapNotNull { it.height }.maxOrNull()
            // Test the best variant: it's the one that would actually be played.
            val best = variants.maxWithOrNull(compareBy<Variant>({ it.height ?: 0 }, { it.bandwidth }))
            if (best != null) {
                mediaUrl = best.url
                mediaBody = try {
                    fetchPlaylist(best.url)
                } catch (e: java.io.IOException) {
                    null
                } ?: return ProbeResult(null, height, null)
            }
        }

        var duration = 0.0
        val segments = ArrayList<String>()
        for (raw in mediaBody.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") ->
                    duration += line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> segments.add(line)
            }
        }
        val durationSec = duration.toInt().takeIf { it > 0 }
        if (segments.isEmpty()) return ProbeResult(null, height, durationSec)

        // A middle segment: the first ones are often a short ad/intro on a different host.
        val segmentUrl = mediaUrl.resolve(segments[segments.size / 2])
            ?: return ProbeResult(null, height, durationSec)
        return ProbeResult(measureSpeed(segmentUrl), height, durationSec)
    }

    private fun parseVariants(base: HttpUrl, body: String): List<Variant> {
        val lines = body.lines().map { it.trim() }
        val result = mutableListOf<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val resolved = base.resolve(uri) ?: continue
            result.add(
                Variant(
                    url = resolved,
                    height = attribute(line, "RESOLUTION")?.substringAfter('x', "")?.toIntOrNull(),
                    bandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                )
            )
        }
        return result
    }

    /** Value of `NAME=value` in an HLS attribute list (whole-name match, so BANDWIDTH ignores AVERAGE-BANDWIDTH). */
    private fun attribute(line: String, name: String): String? {
        val list = line.substringAfter(':', "")
        for (part in list.split(',')) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$name=")) return trimmed.substring(name.length + 1).trim('"')
        }
        return null
    }

    /** Bytes/sec over the body transfer only (connection setup excluded), or null on failure / no data. */
    private fun measureSpeed(url: HttpUrl): Long? {
        probeClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            val startNs = System.nanoTime()
            while (total < SAMPLE_BYTES) {
                val n = source.read(buffer)
                if (n < 0) break
                total += n
                if ((System.nanoTime() - startNs) / 1_000_000 > SAMPLE_MAX_MS) break
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000).coerceAtLeast(1)
            if (total <= 0) return null
            return total * 1000 / elapsedMs
        }
    }
}
