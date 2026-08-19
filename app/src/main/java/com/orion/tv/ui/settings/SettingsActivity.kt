package com.orion.tv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.log.FileLogger
import com.orion.tv.util.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : FragmentActivity(R.layout.activity_settings) {

    private lateinit var serverUrlInput: EditText
    private lateinit var m3uUrlInput: EditText
    private lateinit var adFilterSwitch: Switch
    private lateinit var progressBar: ProgressBar

    private var pendingJob: Job? = null

    private val cancelPendingOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            pendingJob?.cancel()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverUrlInput = findViewById(R.id.server_url_input)
        m3uUrlInput = findViewById(R.id.m3u_url_input)
        adFilterSwitch = findViewById(R.id.ad_filter_switch)
        progressBar = findViewById(R.id.settings_progress)
        val serverUrlSaveButton = findViewById<TextView>(R.id.server_url_save_button)
        val m3uUrlSaveButton = findViewById<TextView>(R.id.m3u_url_save_button)
        val exportLogButton = findViewById<TextView>(R.id.export_log_button)

        serverUrlInput.setText(ServiceLocator.settingsStore.serverBaseUrl.orEmpty())
        m3uUrlInput.setText(ServiceLocator.settingsStore.m3uUrl.orEmpty())
        adFilterSwitch.isChecked = ServiceLocator.settingsStore.adFilterEnabled

        adFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
            ServiceLocator.settingsStore.adFilterEnabled = isChecked
        }
        serverUrlSaveButton.setOnClickListener { saveServerUrl() }
        m3uUrlSaveButton.setOnClickListener { saveM3uUrl() }
        exportLogButton.setOnClickListener { exportLog() }

        onBackPressedDispatcher.addCallback(this, cancelPendingOnBack)
    }

    private fun saveServerUrl() {
        val raw = serverUrlInput.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = UrlUtils.normalizeServerUrl(raw)
        pendingJob?.cancel()
        pendingJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val result = runCatching { ServiceLocator.authRepository.fetchServerConfig(normalized) }
            progressBar.visibility = View.GONE
            result.onSuccess {
                ServiceLocator.settingsStore.serverBaseUrl = normalized
                Toast.makeText(this@SettingsActivity, "服务器地址已更新", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "无法连接到服务器，请检查地址", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportLog() {
        val file = FileLogger.logFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出日志"))
    }

    private fun saveM3uUrl() {
        ServiceLocator.settingsStore.m3uUrl = m3uUrlInput.text?.toString()?.trim()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        pendingJob?.cancel()
        super.onDestroy()
    }
}
