package com.orion.tv.data.repo

import com.orion.tv.data.local.SettingsStore
import com.orion.tv.data.remote.MoonTvApi
import com.orion.tv.data.remote.NetworkModule
import com.orion.tv.data.remote.dto.ApiSite
import com.orion.tv.data.remote.dto.DoubanItem
import com.orion.tv.data.remote.dto.SearchResult
import java.net.URLEncoder

/**
 * Search / Douban discovery / per-source lookups — the read side of the MoonTV API that backs
 * Home, Search, and (in a later phase) the Detail screen's multi-source fetch.
 */
class CatalogRepository(
    private val network: NetworkModule,
    private val settings: SettingsStore
) {

    private fun api(): MoonTvApi {
        val baseUrl = settings.serverBaseUrl ?: error("Server URL is not configured")
        return network.retrofitFor(baseUrl).create(MoonTvApi::class.java)
    }

    suspend fun search(query: String): List<SearchResult> = api().search(query).results.orEmpty()

    suspend fun searchOne(query: String, resourceId: String): List<SearchResult> =
        runCatching { api().searchOne(query, resourceId).results.orEmpty() }.getOrDefault(emptyList())

    suspend fun resources(): List<ApiSite> = api().getResources()

    suspend fun detail(id: String, source: String): SearchResult = api().getDetail(id, source)

    suspend fun douban(type: String, tag: String, pageSize: Int = 20, pageStart: Int = 0): List<DoubanItem> =
        api().getDouban(type, tag, pageSize, pageStart).list

    /** Routes a raw poster URL through MoonTV's /api/image-proxy, same as OrionTV's getImageProxyUrl. */
    fun imageProxyUrl(rawUrl: String): String {
        val baseUrl = settings.serverBaseUrl ?: return rawUrl
        if (rawUrl.isBlank()) return rawUrl
        return "$baseUrl/api/image-proxy?url=" + URLEncoder.encode(rawUrl, "UTF-8")
    }
}
