package com.orion.tv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.data.remote.dto.Favorite
import com.orion.tv.data.remote.dto.SearchResult
import com.orion.tv.player.M3u8Prober
import com.orion.tv.player.ProbeResult
import com.orion.tv.ui.common.FlowLayout
import com.orion.tv.ui.player.PlaybackActivity
import com.orion.tv.util.GridUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Multi-source detail screen. Mirrors OrionTV's detailStore.init(): if the caller already knows
 * a source+id (from the record/favorites rows), that source is fetched first for instant
 * display, then the rest of the enabled sources are merged in behind it.
 */
class DetailActivity : FragmentActivity(R.layout.activity_detail) {

    private lateinit var posterImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var descText: TextView
    private lateinit var favoriteButton: TextView
    private lateinit var sourcesLabel: TextView
    private lateinit var sourcesLoadingText: TextView
    private lateinit var sourcesHdLabel: TextView
    private lateinit var sourcesSdLabel: TextView
    private lateinit var sourcesGridHd: FlowLayout
    private lateinit var sourcesGridSd: FlowLayout
    private lateinit var episodesLabel: TextView
    private lateinit var episodesGrid: GridLayout
    private lateinit var progressBar: ProgressBar

    private val sources = mutableListOf<SearchResult>()
    private val probeResults = mutableMapOf<String, ProbeResult>()
    private val badges = mutableMapOf<String, TextView>()
    private var selected: SearchResult? = null
    private var isFavorite = false
    private var searchTitle: String = ""
    private var loadJob: Job? = null
    private var probeJob: Job? = null

    private val cancelLoadOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            loadJob?.cancel()
            probeJob?.cancel()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        posterImage = findViewById(R.id.poster_image)
        titleText = findViewById(R.id.title_text)
        metaText = findViewById(R.id.meta_text)
        descText = findViewById(R.id.desc_text)
        favoriteButton = findViewById(R.id.favorite_button)
        sourcesLabel = findViewById(R.id.sources_label)
        sourcesLoadingText = findViewById(R.id.sources_loading_text)
        sourcesHdLabel = findViewById(R.id.sources_hd_label)
        sourcesSdLabel = findViewById(R.id.sources_sd_label)
        sourcesGridHd = findViewById(R.id.sources_grid_hd)
        sourcesGridSd = findViewById(R.id.sources_grid_sd)
        episodesLabel = findViewById(R.id.episodes_label)
        episodesGrid = findViewById(R.id.episodes_grid)
        progressBar = findViewById(R.id.detail_progress)

        val spacingPx = (12 * resources.displayMetrics.density).toInt()
        for (grid in listOf(sourcesGridHd, sourcesGridSd)) {
            grid.horizontalSpacing = spacingPx
            grid.verticalSpacing = spacingPx
        }
        episodesGrid.columnCount = GridUtils.computeColumns(this)
        favoriteButton.setOnClickListener { toggleFavorite() }

        onBackPressedDispatcher.addCallback(this, cancelLoadOnBack)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        searchTitle = intent.getStringExtra(EXTRA_SEARCH_TITLE) ?: title
        val initialSource = intent.getStringExtra(EXTRA_SOURCE)
        val initialSourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        titleText.text = title

        loadJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                loadSources(searchTitle, initialSource, initialSourceId)
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun loadSources(searchTitle: String, initialSource: String?, initialSourceId: String?) {
        val catalogRepository = ServiceLocator.catalogRepository

        if (initialSource != null && initialSourceId != null) {
            val preferredList = runCatching { catalogRepository.searchOne(searchTitle, initialSource) }.getOrDefault(emptyList())
            val preferred = preferredList.firstOrNull { it.id == initialSourceId } ?: preferredList.firstOrNull()
            if (preferred != null) {
                sources.add(preferred)
                refreshSources()
                selectSource(preferred)
            }
        }

        val all = runCatching { catalogRepository.search(searchTitle) }.getOrDefault(emptyList())
        for (result in all) {
            if (sources.none { it.source == result.source }) sources.add(result)
        }
        refreshSources()
        if (selected == null) sources.firstOrNull()?.let { selectSource(it) }
        probeSources()
    }

    /**
     * Real speed test (see M3u8Prober) for every source, run once the full source list is in.
     * Limited concurrency so parallel downloads don't skew each other's measured speed too much.
     */
    private fun probeSources() {
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            val gate = Semaphore(PROBE_CONCURRENCY)
            var done = 0
            val total = sources.size
            sourcesLoadingText.text = "正在测速… 0/$total"
            sources.map { result ->
                async {
                    gate.withPermit {
                        val firstEpisodeUrl = result.episodes.firstOrNull().orEmpty()
                        probeResults[result.source] = M3u8Prober.probe(firstEpisodeUrl)
                    }
                    done++
                    sourcesLoadingText.text = "正在测速… $done/$total"
                    // Update just this badge in place, so the list never jumps while it is being used.
                    badges[result.source]?.text = badgeText(result)
                }
            }.awaitAll()
            refreshSources() // all done: re-sort fastest-first
        }
    }

    /**
     * Shown as soon as the source list exists, each badge starting at 测速中…; results fill in per
     * badge as they arrive, and the list is re-sorted by speed once every test has finished.
     */
    private fun refreshSources() {
        val focusedSource = badges.entries.firstOrNull { it.value.hasFocus() }?.key
        sourcesGridHd.removeAllViews()
        sourcesGridSd.removeAllViews()
        badges.clear()
        sourcesGridSd.visibility = View.GONE
        sourcesSdLabel.visibility = View.GONE
        if (sources.isEmpty()) {
            sourcesLoadingText.text = "正在获取片源…"
            sourcesLoadingText.visibility = View.VISIBLE
            sourcesHdLabel.visibility = View.GONE
            sourcesGridHd.visibility = View.GONE
            return
        }
        val allProbed = sources.all { probeResults.containsKey(it.source) }
        sourcesLoadingText.visibility = if (allProbed) View.GONE else View.VISIBLE
        val ordered = if (allProbed) {
            sources.sortedWith(compareByDescending { probeResults[it.source]?.speedBytesPerSec ?: -1L })
        } else {
            sources.toList()
        }
        sourcesHdLabel.text = if (allProbed) "片源（按网速排序）" else "片源"
        sourcesHdLabel.visibility = View.VISIBLE
        sourcesGridHd.visibility = View.VISIBLE
        populateSourceBadges(sourcesGridHd, ordered)
        focusedSource?.let { badges[it]?.requestFocus() }
    }

    private fun badgeText(result: SearchResult): String {
        val info = if (probeResults.containsKey(result.source)) formatProbeInfo(probeResults[result.source]) else "测速中…"
        return "${result.source_name} (${result.episodes.size})\n$info"
    }

    private fun populateSourceBadges(grid: FlowLayout, list: List<SearchResult>) {
        list.forEach { result ->
            val badge = layoutInflater.inflate(R.layout.item_source_badge, grid, false) as TextView
            badge.text = badgeText(result)
            badge.isSelected = result.source == selected?.source
            badge.setOnClickListener { selectSource(result, showEpisodes = true) }
            badges[result.source] = badge
            grid.addView(badge)
        }
    }

    /** Second badge line: "2.3MB/s · 1080p · 1h52m"; unknown parts are omitted, a failed test shows 测速失败. */
    private fun formatProbeInfo(probe: ProbeResult?): String {
        if (probe == null || probe.failed) return "测速失败"
        return listOfNotNull(
            formatSpeed(probe.speedBytesPerSec!!),
            probe.resolutionHeight?.let { formatResolution(it) },
            probe.durationSec?.let { formatDuration(it) }
        ).joinToString(" · ")
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec >= 1_000_000) String.format("%.1fMB/s", bytesPerSec / 1_000_000.0)
        else "${bytesPerSec / 1000}KB/s"

    private fun formatResolution(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        else -> "${height}p"
    }

    private fun formatDuration(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}分钟"
            else -> "${sec}秒"
        }
    }

    private fun populateEpisodesGrid(count: Int) {
        episodesGrid.removeAllViews()
        val columns = episodesGrid.columnCount.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val marginPx = (6 * density).toInt()
        // Fixed pixel width per cell, not GridLayout's weight-based stretching: with weight, a
        // row that isn't "full" (e.g. only 1 episode total) has nothing to share the row's width
        // with, so GridLayout stretches that single cell across the entire row instead of sizing
        // it to its normal 1/columns share.
        val usableWidthPx = resources.displayMetrics.widthPixels - (64 * density).toInt()
        val cellWidthPx = (usableWidthPx / columns) - marginPx * 2
        for (i in 0 until count) {
            val cell = layoutInflater.inflate(R.layout.item_episode, episodesGrid, false) as TextView
            cell.text = "第${i + 1}集"
            cell.setOnClickListener { playEpisode(i) }
            cell.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(i / columns),
                GridLayout.spec(i % columns)
            ).apply {
                width = cellWidthPx
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            episodesGrid.addView(cell)
        }
    }

    /**
     * [showEpisodes] is only true for an explicit badge tap: the episode grid stays hidden through
     * every automatic pre-probe selection (used just to get poster/desc on screen instantly) and
     * only appears once the user has actually chosen a source from the speed-tested list.
     */
    private fun selectSource(result: SearchResult, showEpisodes: Boolean = false) {
        selected = result
        refreshSources()
        metaText.text = listOfNotNull(
            result.year.takeIf { it.isNotBlank() },
            result.type_name,
            result.source_name
        ).joinToString(" · ")
        descText.text = result.desc.orEmpty()
        if (showEpisodes) {
            populateEpisodesGrid(result.episodes.size)
            episodesLabel.visibility = View.VISIBLE
            episodesGrid.visibility = View.VISIBLE
            episodesGrid.getChildAt(0)?.requestFocus()
        }
        Glide.with(this)
            .load(ServiceLocator.catalogRepository.imageProxyUrl(result.poster))
            .placeholder(R.drawable.ic_poster_placeholder)
            .error(R.drawable.ic_poster_placeholder)
            .centerCrop()
            .into(posterImage)
        lifecycleScope.launch { refreshFavoriteState() }
    }

    private suspend fun refreshFavoriteState() {
        val src = selected ?: return
        isFavorite = runCatching { ServiceLocator.favoriteRepository.isFavorite(src.source, src.id) }.getOrDefault(false)
        favoriteButton.text = if (isFavorite) "✓ 已收藏" else "＋ 收藏"
    }

    private fun toggleFavorite() {
        val src = selected ?: return
        lifecycleScope.launch {
            if (isFavorite) {
                runCatching { ServiceLocator.favoriteRepository.delete(src.source, src.id) }
            } else {
                val favorite = Favorite(
                    source_name = src.source_name,
                    total_episodes = src.episodes.size,
                    title = src.title,
                    year = src.year,
                    cover = src.poster,
                    save_time = System.currentTimeMillis(),
                    search_title = src.title
                )
                runCatching { ServiceLocator.favoriteRepository.save(src.source, src.id, favorite) }
            }
            refreshFavoriteState()
        }
    }

    private fun playEpisode(index: Int) {
        val src = selected ?: return
        val altSources = sources.map { "${it.source}|${it.source_name}|${it.episodes.size}" }.toTypedArray()
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_SOURCE, src.source)
            putExtra(PlaybackActivity.EXTRA_SOURCE_ID, src.id)
            putExtra(PlaybackActivity.EXTRA_TITLE, src.title)
            putExtra(PlaybackActivity.EXTRA_SEARCH_TITLE, searchTitle)
            putExtra(PlaybackActivity.EXTRA_SOURCE_NAME, src.source_name)
            putExtra(PlaybackActivity.EXTRA_YEAR, src.year)
            putExtra(PlaybackActivity.EXTRA_POSTER, src.poster)
            putExtra(PlaybackActivity.EXTRA_EPISODE_URLS, src.episodes.toTypedArray())
            putExtra(PlaybackActivity.EXTRA_EPISODE_INDEX, index)
            putExtra(PlaybackActivity.EXTRA_ALT_SOURCES, altSources)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        probeJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PROBE_CONCURRENCY = 4

        const val EXTRA_TITLE = "title"
        const val EXTRA_SEARCH_TITLE = "search_title"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ID = "source_id"
    }
}
