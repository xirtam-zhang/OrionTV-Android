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

    /** Latency-only speed test (see M3u8Prober) for every source, run once the full source list is in. */
    private fun probeSources() {
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            sources.map { result ->
                async {
                    val firstEpisodeUrl = result.episodes.firstOrNull().orEmpty()
                    probeResults[result.source] = M3u8Prober.probe(firstEpisodeUrl)
                }
            }.awaitAll()
            refreshSources()
        }
    }

    /** Nothing is shown until every source has a probe result, then the list splits into 高清/一般, each sorted fastest-first. */
    private fun refreshSources() {
        sourcesGridHd.removeAllViews()
        sourcesGridSd.removeAllViews()
        val probed = sources.isNotEmpty() && sources.all { probeResults.containsKey(it.source) }
        if (!probed) {
            sourcesLoadingText.visibility = View.VISIBLE
            sourcesHdLabel.visibility = View.GONE
            sourcesSdLabel.visibility = View.GONE
            sourcesGridHd.visibility = View.GONE
            sourcesGridSd.visibility = View.GONE
            return
        }
        sourcesLoadingText.visibility = View.GONE
        val byLatency = compareBy<SearchResult> { probeResults[it.source]?.latencyMs ?: Long.MAX_VALUE }
        val hdSources = sources.filter { (probeResults[it.source]?.resolutionHeight ?: 0) >= HD_MIN_HEIGHT }.sortedWith(byLatency)
        val sdSources = sources.filter { (probeResults[it.source]?.resolutionHeight ?: 0) < HD_MIN_HEIGHT }.sortedWith(byLatency)
        sourcesHdLabel.visibility = if (hdSources.isNotEmpty()) View.VISIBLE else View.GONE
        sourcesSdLabel.visibility = if (sdSources.isNotEmpty()) View.VISIBLE else View.GONE
        sourcesGridHd.visibility = if (hdSources.isNotEmpty()) View.VISIBLE else View.GONE
        sourcesGridSd.visibility = if (sdSources.isNotEmpty()) View.VISIBLE else View.GONE
        sourcesSdLabel.text = if (hdSources.isEmpty()) "全部片源" else "一般"
        populateSourceBadges(sourcesGridHd, hdSources)
        populateSourceBadges(sourcesGridSd, sdSources)
    }

    private fun populateSourceBadges(grid: FlowLayout, list: List<SearchResult>) {
        list.forEach { result ->
            val badge = layoutInflater.inflate(R.layout.item_source_badge, grid, false) as TextView
            val probe = probeResults[result.source]
            val pingLabel = if (probe?.latencyMs != null) " · ${probe.latencyMs}ms" else " · 超时"
            badge.text = "${result.source_name} (${result.episodes.size})$pingLabel"
            badge.isSelected = result.source == selected?.source
            badge.setOnClickListener { selectSource(result, showEpisodes = true) }
            grid.addView(badge)
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
        private const val HD_MIN_HEIGHT = 720

        const val EXTRA_TITLE = "title"
        const val EXTRA_SEARCH_TITLE = "search_title"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ID = "source_id"
    }
}
