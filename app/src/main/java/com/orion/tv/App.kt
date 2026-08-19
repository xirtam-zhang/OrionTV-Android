package com.orion.tv

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.orion.tv.log.FileLogger
import java.io.InputStream

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.d("App", "onCreate versionName=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        ServiceLocator.init(this)
        initGlide()
    }

    private fun initGlide() {
        // Must run before any other Glide.get()/Glide.with() call in the process, or the disk
        // cache size below silently has no effect. Posters are cached on disk keyed by their
        // request URL (Glide's default cache-key behavior), capped at POSTER_CACHE_BYTES total.
        val glideBuilder = GlideBuilder()
            .setDiskCache(InternalCacheDiskCacheFactory(this, POSTER_CACHE_DIR, POSTER_CACHE_BYTES))
        Glide.init(this, glideBuilder)
        // /api/image-proxy requires the auth cookie, so route Glide through the same
        // authenticated OkHttpClient the rest of the app uses.
        Glide.get(this).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(ServiceLocator.networkModule.okHttpClient)
        )
    }

    companion object {
        private const val POSTER_CACHE_DIR = "orion_poster_cache"
        private const val POSTER_CACHE_BYTES = 1024L * 1024L * 1024L // 1GB
    }
}
