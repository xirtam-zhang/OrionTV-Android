package com.orion.tv

import android.content.Context
import com.orion.tv.data.local.SettingsStore
import com.orion.tv.data.remote.NetworkModule
import com.orion.tv.data.repo.AuthRepository
import com.orion.tv.data.repo.CatalogRepository
import com.orion.tv.data.repo.FavoriteRepository
import com.orion.tv.data.repo.PlayRecordRepository
import com.orion.tv.player.PlayerLocalSettings

/**
 * Minimal hand-rolled DI container. Deliberately avoids Hilt/Dagger to keep build times and
 * runtime overhead low, which matters on the low-end/older Android TV hardware this app targets.
 */
object ServiceLocator {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var networkModule: NetworkModule
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var catalogRepository: CatalogRepository
        private set

    lateinit var playRecordRepository: PlayRecordRepository
        private set

    lateinit var favoriteRepository: FavoriteRepository
        private set

    lateinit var playerLocalSettings: PlayerLocalSettings
        private set

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        settingsStore = SettingsStore(appContext)
        networkModule = NetworkModule(appContext)
        authRepository = AuthRepository(networkModule, settingsStore)
        catalogRepository = CatalogRepository(networkModule, settingsStore)
        playRecordRepository = PlayRecordRepository(appContext)
        favoriteRepository = FavoriteRepository(appContext)
        playerLocalSettings = PlayerLocalSettings(appContext)
        initialized = true
    }
}
