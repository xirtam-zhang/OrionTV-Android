package com.orion.tv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ProbeResult(val latencyMs: Long?, val resolutionHeight: Int?)

/**
 * Latency-only source speed test: fetches a source's first-episode m3u8 playlist and times the
 * request (a few KB, not the actual video segments — cheap enough to run for every source on the
 * detail screen without meaningfully touching data usage). While we already have the playlist in
 * hand, this also pulls the resolution out of any `#EXT-X-STREAM-INF` variant tags (same
 * approach as OrionTV's services/m3u8.ts), which is what drives the 高清/一般 grouping.
 */
object M3u8Prober {

    private val RESOLUTION_REGEX = Regex("RESOLUTION=\\d+x(\\d+)")

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext ProbeResult(null, null)
        val startedAt = System.currentTimeMillis()
        runCatching {
            probeClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - startedAt
                val resolution = RESOLUTION_REGEX.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                ProbeResult(latency, resolution)
            }
        }.getOrDefault(ProbeResult(null, null))
    }
}
