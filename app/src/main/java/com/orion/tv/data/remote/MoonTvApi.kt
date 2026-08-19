package com.orion.tv.data.remote

import com.orion.tv.data.remote.dto.ApiSite
import com.orion.tv.data.remote.dto.DoubanResult
import com.orion.tv.data.remote.dto.Favorite
import com.orion.tv.data.remote.dto.FavoritePostBody
import com.orion.tv.data.remote.dto.LoginRequest
import com.orion.tv.data.remote.dto.LoginResponse
import com.orion.tv.data.remote.dto.PlayRecord
import com.orion.tv.data.remote.dto.PlayRecordPostBody
import com.orion.tv.data.remote.dto.SearchHistoryPostBody
import com.orion.tv.data.remote.dto.SearchResponse
import com.orion.tv.data.remote.dto.SearchResult
import com.orion.tv.data.remote.dto.ServerConfig
import com.orion.tv.data.remote.dto.SuccessResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * MoonTV REST surface. Every path/param here is aligned 1:1 with MoonTV-main's
 * src/app/api route handlers, so this client works against any compatible MoonTV deployment.
 */
interface MoonTvApi {

    @GET("api/server-config")
    suspend fun getServerConfig(): ServerConfig

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @GET("api/search")
    suspend fun search(@Query("q") query: String): SearchResponse

    @GET("api/search/one")
    suspend fun searchOne(@Query("q") query: String, @Query("resourceId") resourceId: String): SearchResponse

    @GET("api/search/resources")
    suspend fun getResources(): List<ApiSite>

    @GET("api/detail")
    suspend fun getDetail(@Query("id") id: String, @Query("source") source: String): SearchResult

    @GET("api/douban")
    suspend fun getDouban(
        @Query("type") type: String,
        @Query("tag") tag: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageStart") pageStart: Int
    ): DoubanResult

    @GET("api/favorites")
    suspend fun getFavorites(): Map<String, Favorite>

    @GET("api/favorites")
    suspend fun getFavorite(@Query("key") key: String): Favorite?

    @POST("api/favorites")
    suspend fun saveFavorite(@Body body: FavoritePostBody): SuccessResponse

    @DELETE("api/favorites")
    suspend fun deleteAllFavorites(): SuccessResponse

    @DELETE("api/favorites")
    suspend fun deleteFavorite(@Query("key") key: String): SuccessResponse

    @GET("api/playrecords")
    suspend fun getPlayRecords(): Map<String, PlayRecord>

    @POST("api/playrecords")
    suspend fun savePlayRecord(@Body body: PlayRecordPostBody): SuccessResponse

    @DELETE("api/playrecords")
    suspend fun deleteAllPlayRecords(): SuccessResponse

    @DELETE("api/playrecords")
    suspend fun deletePlayRecord(@Query("key") key: String): SuccessResponse

    @GET("api/searchhistory")
    suspend fun getSearchHistory(): List<String>

    @POST("api/searchhistory")
    suspend fun addSearchHistory(@Body body: SearchHistoryPostBody): List<String>

    @DELETE("api/searchhistory")
    suspend fun clearSearchHistory(): Response<Unit>

    @DELETE("api/searchhistory")
    suspend fun deleteSearchHistoryKeyword(@Query("keyword") keyword: String): Response<Unit>
}
