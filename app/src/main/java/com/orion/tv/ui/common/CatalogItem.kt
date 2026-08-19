package com.orion.tv.ui.common

/**
 * Unified poster-card row item shared by Home rows, Search results, and (later) Favorites/Detail.
 * `source`+`sourceId` are present once the item is tied to a specific resolved video (search
 * result or play record); Douban discover items only carry `searchTitle` until the user opens
 * them and a fresh multi-source search resolves a concrete source/id (mirrors OrionTV's detail
 * screen "fresh navigation" flow).
 */
data class CatalogItem(
    val title: String,
    val posterUrl: String,
    val subtitle: String? = null,
    val source: String? = null,
    val sourceId: String? = null,
    val searchTitle: String? = null,
    val progress: Float? = null
)
