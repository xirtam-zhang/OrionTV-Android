package com.orion.tv.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ServerConfig(
    val SiteName: String,
    val StorageType: String
)

data class LoginRequest(
    val username: String? = null,
    val password: String? = null
)

data class LoginResponse(
    val ok: Boolean = false,
    val error: String? = null
)

data class SuccessResponse(
    val success: Boolean = false
)

data class SearchResponse(
    val results: List<SearchResult>? = null,
    val error: String? = null
)

data class SearchResult(
    val id: String,
    val title: String,
    val poster: String,
    val episodes: List<String> = emptyList(),
    val source: String,
    val source_name: String,
    @SerializedName("class") val clazz: String? = null,
    val year: String,
    val desc: String? = null,
    val type_name: String? = null,
    val douban_id: Long? = null
)

data class DoubanItem(
    val id: String,
    val title: String,
    val poster: String,
    val rate: String? = null
)

data class DoubanResult(
    val code: Int,
    val message: String? = null,
    val list: List<DoubanItem> = emptyList()
)

data class ApiSite(
    val key: String,
    val api: String,
    val name: String,
    val detail: String? = null
)

data class Favorite(
    val source_name: String,
    val total_episodes: Int,
    val title: String,
    val year: String,
    val cover: String,
    val save_time: Long = 0,
    val search_title: String? = null
)

data class FavoritePostBody(
    val key: String,
    val favorite: Favorite
)

data class PlayRecord(
    val title: String,
    val source_name: String,
    val cover: String,
    val year: String,
    val index: Int,
    val total_episodes: Int,
    val play_time: Long,
    val total_time: Long,
    val save_time: Long = 0,
    val search_title: String? = null
)

data class PlayRecordPostBody(
    val key: String,
    val record: PlayRecord
)

data class SearchHistoryPostBody(
    val keyword: String
)
