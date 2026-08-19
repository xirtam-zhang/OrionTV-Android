package com.orion.tv.player

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OkHttp interceptor applied to the player's HLS DataSource so ExoPlayer sees pre-filtered playlists. */
class AdSegmentFilterInterceptor(private val isEnabled: () -> Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isEnabled() || !isPlaylistResponse(request.url, response)) return response

        val body = response.body ?: return response
        val filtered = AdSegmentFilter.filter(body.string())
        return response.newBuilder()
            .body(filtered.toResponseBody(body.contentType()))
            .build()
    }

    private fun isPlaylistResponse(url: HttpUrl, response: Response): Boolean {
        if (url.encodedPath.endsWith(".m3u8", ignoreCase = true)) return true
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        return contentType.contains("mpegurl")
    }
}
