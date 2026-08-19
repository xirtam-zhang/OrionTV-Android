package com.orion.tv.data.remote

import android.content.Context
import com.orion.tv.log.FileLoggingInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the shared OkHttpClient (single persistent CookieJar for the MoonTV `auth` cookie) and
 * per-base-URL Retrofit instances, since the server address is user-configurable at runtime.
 */
class NetworkModule(context: Context) {

    val cookieJar = AuthCookieJar(context)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // Logs every request to FileLogger (see SettingsActivity's "导出日志") since release
        // builds have no attached logcat for the user to hand back for debugging.
        .addInterceptor(FileLoggingInterceptor())
        .build()

    private var cachedBaseUrl: String? = null
    private var cachedRetrofit: Retrofit? = null

    fun retrofitFor(baseUrl: String): Retrofit {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        cachedRetrofit?.let { if (cachedBaseUrl == normalized) return it }
        val retrofit = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        cachedBaseUrl = normalized
        cachedRetrofit = retrofit
        return retrofit
    }
}
