package com.orion.tv.ui.login

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.util.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Plain EditText/TextView screen rather than GuidedStepSupportFragment: GuidedStepSupportFragment's
 * editable actions are built around D-pad focus + OK-press and don't reliably respond to a touch
 * tap on phones, whereas a standard EditText works identically on touch and D-pad/TV-keyboard.
 */
class ServerSetupActivity : FragmentActivity(R.layout.activity_server_setup) {

    private lateinit var progressBar: ProgressBar
    private var submitJob: Job? = null

    private val cancelSubmitOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            submitJob?.cancel()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urlInput = findViewById<EditText>(R.id.server_url_input)
        val submitButton = findViewById<TextView>(R.id.submit_button)
        progressBar = findViewById(R.id.server_setup_progress)

        urlInput.setText(ServiceLocator.settingsStore.serverBaseUrl.orEmpty())
        submitButton.setOnClickListener { submit(urlInput.text?.toString().orEmpty()) }
        urlInput.setOnEditorActionListener { _, _, _ ->
            submit(urlInput.text?.toString().orEmpty())
            true
        }
        onBackPressedDispatcher.addCallback(this, cancelSubmitOnBack)
    }

    private fun submit(raw: String) {
        if (raw.isBlank()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = UrlUtils.normalizeServerUrl(raw)
        submitJob?.cancel()
        submitJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val result = runCatching { ServiceLocator.authRepository.fetchServerConfig(normalized) }
            progressBar.visibility = View.GONE
            result.onSuccess {
                ServiceLocator.settingsStore.serverBaseUrl = normalized
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure {
                Toast.makeText(this@ServerSetupActivity, "无法连接到服务器，请检查地址", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        submitJob?.cancel()
        super.onDestroy()
    }
}
