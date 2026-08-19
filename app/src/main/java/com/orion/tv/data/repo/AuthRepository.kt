package com.orion.tv.data.repo

import com.orion.tv.data.local.SettingsStore
import com.orion.tv.data.remote.MoonTvApi
import com.orion.tv.data.remote.NetworkModule
import com.orion.tv.data.remote.dto.LoginRequest
import com.orion.tv.data.remote.dto.ServerConfig

/**
 * There's no user-facing account system in this app — MoonTV's middleware still requires its
 * `auth` session cookie on nearly every route, so [login] is called transparently in the
 * background (see MainActivity) rather than exposed as a login screen.
 */
class AuthRepository(
    private val network: NetworkModule,
    private val settings: SettingsStore
) {

    private fun api(baseUrl: String): MoonTvApi =
        network.retrofitFor(baseUrl).create(MoonTvApi::class.java)

    private fun requireApi(): MoonTvApi {
        val baseUrl = settings.serverBaseUrl ?: error("Server URL is not configured")
        return api(baseUrl)
    }

    suspend fun fetchServerConfig(baseUrl: String): ServerConfig = api(baseUrl).getServerConfig()

    suspend fun login(username: String?, password: String?): Boolean {
        val response = requireApi().login(LoginRequest(username = username, password = password))
        return response.isSuccessful && response.body()?.ok == true
    }
}
