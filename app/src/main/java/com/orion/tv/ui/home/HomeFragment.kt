package com.orion.tv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.log.FileLogger
import com.orion.tv.ui.common.CatalogItem
import com.orion.tv.ui.common.GridSpacingItemDecoration
import com.orion.tv.ui.common.PosterAdapter
import com.orion.tv.ui.common.openDetail
import com.orion.tv.ui.favorites.FavoritesActivity
import com.orion.tv.ui.search.SearchActivity
import com.orion.tv.ui.settings.SettingsActivity
import com.orion.tv.util.GridUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "HomeFragment"

/**
 * Home/Discover screen: a single flat, horizontally-scrollable tab bar — 最近播放 alongside
 * OrionTV's category+tag menu (热门电影/热门剧集/国产剧/美剧/.../豆瓣 Top250) — each tab loading
 * into one plain vertical poster grid below (RecyclerView + GridLayoutManager, not a Leanback
 * grid widget: simpler, and lighter-weight on old/low-memory devices). Defaults to 热门电影.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val catalogRepository get() = ServiceLocator.catalogRepository
    private val playRecordRepository get() = ServiceLocator.playRecordRepository

    private lateinit var tabsContainer: LinearLayout
    private lateinit var contentGrid: RecyclerView
    private lateinit var contentEmptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var posterAdapter: PosterAdapter

    private val tabViews = mutableListOf<TextView>()
    private var loadJob: Job? = null
    private var currentTab: HomeTab? = null
    private var nextPageStart = 0
    private var hasMorePages = true
    private var isLoadingMore = false

    /**
     * Back from a poster in the content grid returns focus to the tab bar instead of exiting the
     * app outright — only a second Back press, with focus already outside the grid, actually exits.
     */
    private val cancelLoadOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val focused = requireActivity().currentFocus
            if (focused != null && isDescendantOf(focused, contentGrid)) {
                focusSelectedTab()
                return
            }
            loadJob?.cancel()
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun focusSelectedTab() {
        (tabViews.firstOrNull { it.isSelected } ?: tabViews.firstOrNull())?.requestFocus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabsContainer = view.findViewById(R.id.tabs_container)
        contentGrid = view.findViewById(R.id.content_grid)
        contentEmptyText = view.findViewById(R.id.content_empty_text)
        progressBar = view.findViewById(R.id.home_progress)

        view.findViewById<TextView>(R.id.search_button).setOnClickListener { openSearch(null) }
        view.findViewById<TextView>(R.id.favorites_button).setOnClickListener {
            startActivity(Intent(requireContext(), FavoritesActivity::class.java))
        }
        view.findViewById<TextView>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        posterAdapter = PosterAdapter { requireContext().openDetail(it) }
        contentGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), GridUtils.computeColumns(requireContext(), itemWidthDp = 142, minColumns = 3, maxColumns = 10))
            adapter = posterAdapter
            setHasFixedSize(true)
            itemAnimator = null // fewer allocations/inval passes per scroll frame on low-end devices
            addItemDecoration(GridSpacingItemDecoration((10 * resources.displayMetrics.density).toInt()))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= layoutManager.itemCount - LOAD_MORE_THRESHOLD) {
                        loadMoreIfNeeded()
                    }
                }
            })
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cancelLoadOnBack)

        setupTabs()
        selectTab(DEFAULT_TAB_INDEX)
    }

    private fun openSearch(prefillQuery: String?) {
        val intent = Intent(requireContext(), SearchActivity::class.java)
        if (prefillQuery != null) intent.putExtra(SearchActivity.EXTRA_QUERY, prefillQuery)
        startActivity(intent)
    }

    private fun setupTabs() {
        tabsContainer.removeAllViews()
        tabViews.clear()
        HOME_TABS.forEachIndexed { index, tab ->
            val tabView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_source_badge, tabsContainer, false) as TextView
            tabView.text = tab.title
            tabView.setOnClickListener { selectTab(index) }
            tabsContainer.addView(tabView)
            tabViews.add(tabView)
        }
    }

    private fun selectTab(index: Int) {
        if (index !in HOME_TABS.indices) return
        tabViews.forEachIndexed { i, v -> v.isSelected = i == index }
        loadContentGrid(HOME_TABS[index])
    }

    private fun loadContentGrid(tab: HomeTab) {
        currentTab = tab
        nextPageStart = 0
        hasMorePages = !tab.isRecent
        isLoadingMore = false
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            contentEmptyText.visibility = View.GONE
            try {
                val items = if (tab.isRecent) loadRecentItems() else loadDoubanItems(tab, pageStart = 0)
                posterAdapter.submit(items)
                if (!tab.isRecent) {
                    nextPageStart = items.size
                    hasMorePages = items.size >= DOUBAN_PAGE_SIZE
                }
                contentEmptyText.text = if (tab.isRecent) "暂无播放记录" else "该分类暂无内容"
                contentEmptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    /** Infinite scroll: fetches the next Douban page once the user nears the bottom of the grid. */
    private fun loadMoreIfNeeded() {
        val tab = currentTab ?: return
        if (tab.isRecent || !hasMorePages || isLoadingMore) return
        isLoadingMore = true
        loadJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val more = loadDoubanItems(tab, pageStart = nextPageStart)
                posterAdapter.appendItems(more)
                nextPageStart += more.size
                hasMorePages = more.size >= DOUBAN_PAGE_SIZE
            } finally {
                isLoadingMore = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun loadRecentItems(): List<CatalogItem> {
        val result = runCatching { playRecordRepository.getAll() }
        result.exceptionOrNull()?.let { FileLogger.e(TAG, "loadRecentItems failed", it) }
        val records = result.getOrDefault(emptyMap())
        return records.entries
            .sortedByDescending { it.value.save_time }
            .mapNotNull { (key, record) ->
                val idx = key.indexOf('+')
                if (idx <= 0) return@mapNotNull null
                CatalogItem(
                    title = record.title,
                    posterUrl = catalogRepository.imageProxyUrl(record.cover),
                    subtitle = "第${record.index}集",
                    source = key.substring(0, idx),
                    sourceId = key.substring(idx + 1),
                    searchTitle = record.search_title ?: record.title,
                    progress = if (record.total_time > 0) record.play_time.toFloat() / record.total_time else null
                )
            }
    }

    private suspend fun loadDoubanItems(tab: HomeTab, pageStart: Int): List<CatalogItem> {
        val result = runCatching { catalogRepository.douban(tab.type!!, tab.tag!!, pageSize = DOUBAN_PAGE_SIZE, pageStart = pageStart) }
        result.exceptionOrNull()?.let { FileLogger.e(TAG, "loadDoubanItems(${tab.title}, pageStart=$pageStart) failed", it) }
        val list = result.getOrNull()
        FileLogger.d(TAG, "loadDoubanItems(${tab.title}, pageStart=$pageStart) -> ${list?.size ?: "null"} items")
        return list.orEmpty().map {
            CatalogItem(
                title = it.title,
                posterUrl = catalogRepository.imageProxyUrl(it.poster),
                subtitle = it.rate?.takeIf { rate -> rate.isNotBlank() },
                searchTitle = it.title
            )
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }

    private data class HomeTab(val title: String, val type: String?, val tag: String?) {
        val isRecent: Boolean get() = type == null
    }

    companion object {
        // Flat tab bar: 最近播放 alongside OrionTV's category+tag menu (热门剧集/电视剧's
        // per-region tags/电影's tags/综艺/豆瓣 Top250), matching the original's category order
        // with 最近播放 first — but landing on 热门电影 by default. The tab bar itself is the
        // only horizontally-scrolling element on this screen (it holds more tabs than fit on
        // screen at once); the poster grid below is a plain vertical multi-column list.
        private val HOME_TABS = listOf(
            HomeTab("最近播放", null, null),
            HomeTab("热门电影", "movie", "热门"),
            HomeTab("热门剧集", "tv", "热门"),
            HomeTab("国产剧", "tv", "国产剧"),
            HomeTab("美剧", "tv", "美剧"),
            HomeTab("英剧", "tv", "英剧"),
            HomeTab("韩剧", "tv", "韩剧"),
            HomeTab("日剧", "tv", "日剧"),
            HomeTab("港剧", "tv", "港剧"),
            HomeTab("日本动画", "tv", "日本动画"),
            HomeTab("动画", "tv", "动画"),
            HomeTab("综艺", "tv", "综艺"),
            HomeTab("最新电影", "movie", "最新"),
            HomeTab("经典电影", "movie", "经典"),
            HomeTab("豆瓣高分", "movie", "豆瓣高分"),
            HomeTab("豆瓣 Top250", "movie", "top250")
        )
        private val DEFAULT_TAB_INDEX = HOME_TABS.indexOfFirst { it.title == "热门电影" }
        private const val DOUBAN_PAGE_SIZE = 20
        private const val LOAD_MORE_THRESHOLD = 6
    }
}
