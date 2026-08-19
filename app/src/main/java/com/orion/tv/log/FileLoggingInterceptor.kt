package com.orion.tv.log

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Logs every request this app makes (method, URL, response code/size, or failure) to FileLogger. */
class FileLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.currentTimeMillis()
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startedAt
            FileLogger.e("Http", "FAILED ${request.method} ${request.url} (${elapsed}ms)", e)
            throw e
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val contentLength = response.body?.contentLength() ?: -1
        FileLogger.d("Http", "${request.method} ${request.url} -> ${response.code} (${elapsed}ms, ${contentLength}B)")
        return response
    }
}
