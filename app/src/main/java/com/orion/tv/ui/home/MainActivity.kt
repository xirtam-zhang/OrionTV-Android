package com.orion.tv.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.log.FileLogger
import com.orion.tv.ui.login.ServerSetupActivity
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

/**
 * Hosts the app's single-activity flow. There is no user-facing account system: MoonTV's
 * middleware still requires its `auth` session cookie on almost every API route, so this app
 * transparently performs a passwordless login in the background on every launch (matching
 * OrionTV's behavior for `localstorage`-mode servers) instead of showing a login screen —
 * favorites/watch history are kept entirely on-device (see FavoriteRepository/PlayRecordRepository).
 */
class MainActivity : FragmentActivity(R.layout.activity_main) {

    private val serverSetupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        FileLogger.d(TAG, "ServerSetupActivity result=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            proceedToHome()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            route()
        }
    }

    private fun route() {
        val serverUrl = ServiceLocator.settingsStore.serverBaseUrl
        FileLogger.d(TAG, "route() serverUrl=$serverUrl")
        if (serverUrl.isNullOrBlank()) {
            serverSetupLauncher.launch(Intent(this, ServerSetupActivity::class.java))
        } else {
            proceedToHome()
        }
    }

    private fun proceedToHome() {
        lifecycleScope.launch {
            val ok = runCatching { ServiceLocator.authRepository.login(null, null) }.getOrDefault(false)
            FileLogger.d(TAG, "silent login ok=$ok")
            showHome()
        }
    }

    private fun showHome() {
        FileLogger.d(TAG, "showHome()")
        supportFragmentManager.commit {
            replace(R.id.content_frame, HomeFragment())
        }
    }
}
