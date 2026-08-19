package com.orion.tv.ui.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.data.remote.dto.PlayRecord
import com.orion.tv.data.remote.dto.SearchResult
import com.orion.tv.player.AdSegmentFilterInterceptor
import com.orion.tv.player.PlaybackProgressTracker
import com.orion.tv.ui.detail.EpisodeAdapter
import com.orion.tv.ui.detail.SourceAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Custom-controls VOD player (mirrors OrionTV's play.tsx + PlayerControls + useTVRemoteHandler):
 * D-pad left/right seeks +/-20s, center toggles play/pause, Back collapses panels before exiting,
 * controls auto-hide after 5s, progress is resumed/saved against /api/playrecords, and manual
 * intro/outro markers auto-skip/auto-advance on replay. HLS playlists are fetched through
 * AdSegmentFilterInterceptor so spliced-in ad segments never reach the player.
 */
class PlaybackActivity : FragmentActivity(R.layout.activity_player) {

    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var controlsOverlay: View
    private lateinit var titleText: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var playPauseButton: TextView
    private lateinit var markIntroButton: TextView
    private lateinit var markOutroButton: TextView
    private lateinit var episodesButton: TextView
    private lateinit var sourcesButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var episodesPanel: View
    private lateinit var sourcesPanel: View
    private lateinit var panelScrim: View
    private lateinit var episodesPanelGrid: VerticalGridView
    private lateinit var sourcesPanelGrid: VerticalGridView

    private var player: ExoPlayer? = null
    private lateinit var progressTracker: PlaybackProgressTracker

    private val episodeAdapter = EpisodeAdapter { index -> onEpisodeSelected(index) }
    private val sourceAdapter = SourceAdapter { onSourceSelected(it) }

    private var source: String = ""
    private var sourceId: String = ""
    private var title: String = ""
    private var searchTitle: String = ""
    private var sourceName: String = ""
    private var year: String = ""
    private var poster: String = ""
    private var episodeUrls: List<String> = emptyList()
    private var currentEpisodeIndex: Int = 0
    private var leftPressStartAt: Long? = null
    private var rightPressStartAt: Long? = null
    private var altSources: List<SearchResult> = emptyList()
    private var loadJob: Job? = null
    private var sourceSwitchJob: Job? = null
    private var isUserSeeking = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val loadTimeoutRunnable = Runnable {
        Toast.makeText(this, "播放超时，请重试", Toast.LENGTH_SHORT).show()
    }
    private val progressRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        bindViews()
        readExtras()
        setupPlayer()
        setupPanels()
        setupControlListeners()

        titleText.text = "$title  第${currentEpisodeIndex + 1}集  ($sourceName)"
        loadJob = lifecycleScope.launch {
            val resumeMs = resolveResumePositionMs(currentEpisodeIndex)
            loadEpisode(currentEpisodeIndex, resumeMs)
        }
    }

    /** Full-screen video, no status/nav bars — on phones this app otherwise runs windowed (unlike a TV, which has no system bars to hide in the first place). */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        loadingSpinner = findViewById(R.id.loading_spinner)
        controlsOverlay = findViewById(R.id.controls_overlay)
        titleText = findViewById(R.id.player_title_text)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        progressBar = findViewById(R.id.progress_bar)
        playPauseButton = findViewById(R.id.play_pause_button)
        markIntroButton = findViewById(R.id.mark_intro_button)
        markOutroButton = findViewById(R.id.mark_outro_button)
        episodesButton = findViewById(R.id.episodes_button)
        sourcesButton = findViewById(R.id.sources_button)
        speedButton = findViewById(R.id.speed_button)
        episodesPanel = findViewById(R.id.episodes_panel)
        sourcesPanel = findViewById(R.id.sources_panel)
        panelScrim = findViewById(R.id.panel_scrim)
        episodesPanelGrid = findViewById(R.id.episodes_panel_grid)
        sourcesPanelGrid = findViewById(R.id.sources_panel_grid)
        panelScrim.setOnClickListener { hideEpisodesPanel(); hideSourcesPanel() }
        setupSeekBar()
    }

    /** Phone-friendly scrubbing: tap or drag anywhere on the bar to seek there. */
    private fun setupSeekBar() {
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration?.takeIf { it > 0 } ?: return
                positionText.text = formatTime(duration * progress / seekBar.max)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
                resetHideControlsTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = false
                val exoPlayer = player ?: return
                val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
                exoPlayer.seekTo(duration * seekBar.progress / seekBar.max)
                updateProgressUi(exoPlayer)
            }
        })
    }

    private fun readExtras() {
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        searchTitle = intent.getStringExtra(EXTRA_SEARCH_TITLE) ?: title
        sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty()
        year = intent.getStringExtra(EXTRA_YEAR).orEmpty()
        poster = intent.getStringExtra(EXTRA_POSTER).orEmpty()
        episodeUrls = intent.getStringArrayExtra(EXTRA_EPISODE_URLS)?.toList().orEmpty()
        currentEpisodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0).coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))
        altSources = intent.getStringArrayExtra(EXTRA_ALT_SOURCES).orEmpty().mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 3) return@mapNotNull null
            val count = parts[2].toIntOrNull() ?: 0
            SearchResult(
                id = "",
                title = title,
                poster = "",
                episodes = List(count) { "" },
                source = parts[0],
                source_name = parts[1],
                year = ""
            )
        }
    }

    private fun setupPlayer() {
        val playerOkHttpClient = ServiceLocator.networkModule.okHttpClient.newBuilder()
            .addInterceptor(AdSegmentFilterInterceptor { ServiceLocator.settingsStore.adFilterEnabled })
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(playerOkHttpClient)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.addListener(playerListener)
        playerView.player = exoPlayer
        player = exoPlayer
        progressTracker = PlaybackProgressTracker(ServiceLocator.playRecordRepository, lifecycleScope)
    }

    private fun setupPanels() {
        episodesPanelGrid.setNumColumns(1)
        episodesPanelGrid.adapter = episodeAdapter
        sourcesPanelGrid.setNumColumns(1)
        sourcesPanelGrid.adapter = sourceAdapter
    }

    private fun setupControlListeners() {
        playPauseButton.setOnClickListener { togglePlayPause() }
        markIntroButton.setOnClickListener { markIntro() }
        markOutroButton.setOnClickListener { markOutro() }
        episodesButton.setOnClickListener { toggleEpisodesPanel() }
        sourcesButton.setOnClickListener { toggleSourcesPanel() }
        speedButton.setOnClickListener { cycleSpeed() }
        playerView.setOnClickListener { if (controlsOverlay.visibility == View.VISIBLE) hideControls() else showControls() }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    loadingSpinner.visibility = View.GONE
                    handler.removeCallbacks(loadTimeoutRunnable)
                }
                Player.STATE_BUFFERING -> loadingSpinner.visibility = View.VISIBLE
                Player.STATE_ENDED -> advanceToNextEpisodeIfAny()
                else -> Unit
            }
        }
    }

    private fun loadEpisode(index: Int, resumePositionMs: Long) {
        val exoPlayer = player ?: return
        if (index !in episodeUrls.indices) return
        currentEpisodeIndex = index
        val mediaItem = MediaItem.Builder()
            .setUri(episodeUrls[index])
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        val introEnd = ServiceLocator.playerLocalSettings.getIntroEndMs(source, sourceId) ?: 0L
        val startPositionMs = if (resumePositionMs > 0) resumePositionMs else introEnd
        if (startPositionMs > 0) exoPlayer.seekTo(startPositionMs)
        exoPlayer.playWhenReady = true
        val savedRate = ServiceLocator.playerLocalSettings.getPlaybackRate(source, sourceId)
        exoPlayer.setPlaybackSpeed(savedRate)
        updateSpeedLabel(savedRate)
        loadingSpinner.visibility = View.VISIBLE
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        titleText.text = "$title  第${index + 1}集  ($sourceName)"
        episodeAdapter.submit(episodeUrls.size, currentEpisodeIndex)
        sourceAdapter.submit(altSources, source)
    }

    private fun cycleSpeed() {
        val exoPlayer = player ?: return
        val currentRate = exoPlayer.playbackParameters.speed
        val currentIndex = SPEED_OPTIONS.indexOfFirst { kotlin.math.abs(it - currentRate) < 0.01f }.coerceAtLeast(0)
        val nextRate = SPEED_OPTIONS[(currentIndex + 1) % SPEED_OPTIONS.size]
        exoPlayer.setPlaybackSpeed(nextRate)
        ServiceLocator.playerLocalSettings.setPlaybackRate(source, sourceId, nextRate)
        updateSpeedLabel(nextRate)
    }

    private fun updateSpeedLabel(rate: Float) {
        speedButton.text = "${rate}x"
    }

    private suspend fun resolveResumePositionMs(requestedIndex: Int): Long {
        val records = runCatching { ServiceLocator.playRecordRepository.getAll() }.getOrDefault(emptyMap())
        val record = records["$source+$sourceId"] ?: return 0L
        return if (record.index - 1 == requestedIndex && record.play_time > 0) record.play_time * 1000 else 0L
    }

    private fun tick() {
        val exoPlayer = player ?: return
        updateProgressUi(exoPlayer)
        checkOutroAutoAdvance(exoPlayer)
        if (exoPlayer.isPlaying) saveProgress(force = false)
    }

    private fun updateProgressUi(exoPlayer: ExoPlayer) {
        val duration = exoPlayer.duration
        if (duration <= 0) return
        durationText.text = formatTime(duration)
        if (isUserSeeking) return // don't fight an in-progress drag with the playback-position tick
        progressBar.max = 1000
        progressBar.progress = ((exoPlayer.currentPosition * 1000) / duration).toInt().coerceIn(0, 1000)
        positionText.text = formatTime(exoPlayer.currentPosition)
    }

    private fun checkOutroAutoAdvance(exoPlayer: ExoPlayer) {
        val outroStart = ServiceLocator.playerLocalSettings.getOutroStartMs(source, sourceId) ?: return
        val duration = exoPlayer.duration
        if (duration <= 0) return
        if (exoPlayer.currentPosition >= duration - outroStart) advanceToNextEpisodeIfAny()
    }

    private fun advanceToNextEpisodeIfAny() {
        saveProgress(force = true)
        val nextIndex = currentEpisodeIndex + 1
        if (nextIndex < episodeUrls.size) loadEpisode(nextIndex, resumePositionMs = 0)
    }

    private fun saveProgress(force: Boolean) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        if (duration <= 0 || source.isBlank() || sourceId.isBlank()) return
        val record = PlayRecord(
            title = title,
            source_name = sourceName,
            cover = poster,
            year = year,
            index = currentEpisodeIndex + 1,
            total_episodes = episodeUrls.size,
            play_time = exoPlayer.currentPosition / 1000,
            total_time = duration / 1000,
            save_time = System.currentTimeMillis(),
            search_title = searchTitle
        )
        progressTracker.onProgress(source, sourceId, record, force)
    }

    private fun togglePlayPause() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    private fun markIntro() {
        val exoPlayer = player ?: return
        ServiceLocator.playerLocalSettings.setIntroEndMs(source, sourceId, exoPlayer.currentPosition)
        Toast.makeText(this, "已标记片头结束位置", Toast.LENGTH_SHORT).show()
        saveProgress(force = true)
    }

    private fun markOutro() {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        if (duration <= 0) return
        ServiceLocator.playerLocalSettings.setOutroStartMs(source, sourceId, duration - exoPlayer.currentPosition)
        Toast.makeText(this, "已标记片尾开始位置", Toast.LENGTH_SHORT).show()
        saveProgress(force = true)
    }

    private fun onEpisodeSelected(index: Int) {
        hideEpisodesPanel()
        if (index == currentEpisodeIndex) return
        saveProgress(force = true)
        loadEpisode(index, resumePositionMs = 0)
    }

    private fun onSourceSelected(result: SearchResult) {
        if (result.source == source) {
            hideSourcesPanel()
            return
        }
        // Cancel any switch already in flight first: if the user taps a second source before the
        // first one's lookup returns, the abandoned request must not go on to apply its (now
        // stale) result over the newer selection, and its network call should stop immediately
        // rather than keep running in the background for a source we've already left.
        sourceSwitchJob?.cancel()
        sourceSwitchJob = lifecycleScope.launch {
            loadingSpinner.visibility = View.VISIBLE
            val matches = runCatching { ServiceLocator.catalogRepository.searchOne(searchTitle, result.source) }.getOrDefault(emptyList())
            val match = matches.firstOrNull()
            if (match == null) {
                loadingSpinner.visibility = View.GONE
                Toast.makeText(this@PlaybackActivity, "该源暂无匹配结果", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val exoPlayer = player
            val resumeMs = exoPlayer?.currentPosition ?: 0L
            saveProgress(force = true)
            source = match.source
            sourceId = match.id
            sourceName = match.source_name
            episodeUrls = match.episodes
            currentEpisodeIndex = currentEpisodeIndex.coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))
            loadEpisode(currentEpisodeIndex, resumeMs)
            hideSourcesPanel()
        }
    }

    private fun toggleEpisodesPanel() {
        if (episodesPanel.visibility == View.VISIBLE) {
            hideEpisodesPanel()
        } else {
            sourcesPanel.visibility = View.GONE
            episodesPanel.visibility = View.VISIBLE
            panelScrim.visibility = View.VISIBLE
            episodesPanelGrid.requestFocus()
        }
    }

    private fun toggleSourcesPanel() {
        if (sourcesPanel.visibility == View.VISIBLE) {
            hideSourcesPanel()
        } else {
            episodesPanel.visibility = View.GONE
            sourcesPanel.visibility = View.VISIBLE
            panelScrim.visibility = View.VISIBLE
            sourcesPanelGrid.requestFocus()
        }
    }

    private fun hideEpisodesPanel() {
        episodesPanel.visibility = View.GONE
        updatePanelScrimVisibility()
    }

    private fun hideSourcesPanel() {
        sourcesPanel.visibility = View.GONE
        updatePanelScrimVisibility()
    }

    private fun updatePanelScrimVisibility() {
        panelScrim.visibility = if (episodesPanel.visibility == View.VISIBLE || sourcesPanel.visibility == View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showControls() {
        controlsOverlay.visibility = View.VISIBLE
        playPauseButton.requestFocus()
        resetHideControlsTimer()
    }

    private fun hideControls() {
        controlsOverlay.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsOverlay.visibility == View.VISIBLE) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
        val newPosition = (exoPlayer.currentPosition + deltaMs).coerceIn(0, duration)
        exoPlayer.seekTo(newPosition)
        updateProgressUi(exoPlayer)
    }

    /**
     * Graduated seek: a quick tap moves BASE_SEEK_STEP_MS; holding the key ramps the per-repeat
     * step up through ACCEL_STEPS_MS as long as it's held, capped at the last tier. Android
     * auto-repeats a held D-pad key as further ACTION_DOWN events with an increasing
     * repeatCount, so this only needs to track how long the *current* press has lasted.
     */
    private fun accelSeekStepMs(pressStartAt: Long): Long {
        val heldMs = System.currentTimeMillis() - pressStartAt
        var step = BASE_SEEK_STEP_MS
        for ((thresholdMs, tierStepMs) in ACCEL_STEPS_MS) {
            if (heldMs >= thresholdMs) step = tierStepMs
        }
        return step
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_UP) leftPressStartAt = null
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_UP) rightPressStartAt = null

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && isDpadKey(event.keyCode)) {
            resetHideControlsTimer()
        }
        if (episodesPanel.visibility == View.VISIBLE || sourcesPanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && controlsOverlay.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showControls()
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.repeatCount == 0) leftPressStartAt = System.currentTimeMillis()
                    seekBy(-accelSeekStepMs(leftPressStartAt ?: System.currentTimeMillis()))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.repeatCount == 0) rightPressStartAt = System.currentTimeMillis()
                    seekBy(accelSeekStepMs(rightPressStartAt ?: System.currentTimeMillis()))
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDpadKey(keyCode: Int): Boolean = keyCode in DPAD_KEYS

    private fun handleBack() {
        when {
            sourcesPanel.visibility == View.VISIBLE -> hideSourcesPanel()
            episodesPanel.visibility == View.VISIBLE -> hideEpisodesPanel()
            controlsOverlay.visibility == View.VISIBLE -> hideControls()
            else -> {
                loadJob?.cancel()
                sourceSwitchJob?.cancel()
                finish()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressRunnable)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        saveProgress(force = true)
        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        sourceSwitchJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SEARCH_TITLE = "search_title"
        const val EXTRA_SOURCE_NAME = "source_name"
        const val EXTRA_YEAR = "year"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_EPISODE_URLS = "episode_urls"
        const val EXTRA_EPISODE_INDEX = "episode_index"
        const val EXTRA_ALT_SOURCES = "alt_sources"

        // Tap = 10s. Held past 1s -> 20s/step, past 3s -> 40s/step, past 6s -> 60s/step (cap).
        private const val BASE_SEEK_STEP_MS = 10_000L
        private val ACCEL_STEPS_MS = listOf(
            1_000L to 20_000L,
            3_000L to 40_000L,
            6_000L to 60_000L
        )
        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val LOAD_TIMEOUT_MS = 60_000L
        private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        )
    }
}
