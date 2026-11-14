package com.orion.tv.ui.search

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orion.tv.R
import com.orion.tv.ServiceLocator
import com.orion.tv.data.remote.dto.SearchResult
import com.orion.tv.ui.common.CatalogItem
import com.orion.tv.ui.common.GridSpacingItemDecoration
import com.orion.tv.ui.common.PosterAdapter
import com.orion.tv.ui.common.openDetail
import com.orion.tv.util.GridUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Search screen backed by GET /api/search (server-side multi-source aggregation). Plain
 * EditText + an explicit submit button — not Leanback's SearchSupportFragment — since some TV
 * remotes/phone IMEs don't surface a search/done key on their virtual keyboard, so a
 * button that doesn't depend on the keyboard is needed. Results reuse the same poster-grid
 * (RecyclerView + GridLayoutManager + PosterAdapter) as Home for a consistent look.
 */
class SearchActivity : FragmentActivity(R.layout.activity_search) {

    private lateinit var searchInput: EditText
    private lateinit var resultsGrid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var posterAdapter: PosterAdapter

    private var searchJob: Job? = null

    private val cancelSearchOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            searchJob?.cancel()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchInput = findViewById(R.id.search_input)
        resultsGrid = findViewById(R.id.search_results_grid)
        emptyText = findViewById(R.id.search_empty_text)
        progressBar = findViewById(R.id.search_progress)
        val submitButton = findViewById<TextView>(R.id.search_submit_button)

        posterAdapter = PosterAdapter { openDetail(it) }
        resultsGrid.apply {
            layoutManager = GridLayoutManager(this@SearchActivity, GridUtils.computeColumns(this@SearchActivity, itemWidthDp = 142, minColumns = 3, maxColumns = 10))
            adapter = posterAdapter
            setHasFixedSize(true)
            itemAnimator = null
            addItemDecoration(GridSpacingItemDecoration((10 * resources.displayMetrics.density).toInt()))
        }

        submitButton.setOnClickListener { doSearch(searchInput.text?.toString().orEmpty()) }
        searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch(searchInput.text?.toString().orEmpty())
            true
        }

        onBackPressedDispatcher.addCallback(this, cancelSearchOnBack)

        val prefill = intent.getStringExtra(EXTRA_QUERY)
        if (!prefill.isNullOrBlank()) {
            searchInput.setText(prefill)
            doSearch(prefill)
        }
    }

    private fun doSearch(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            try {
                val results = rankByRelevance(
                    runCatching { ServiceLocator.catalogRepository.search(query) }.getOrDefault(emptyList()),
                    query
                )
                // One card per movie, not per source: the detail page lists the sources anyway.
                val items = dedupeByMovie(results).map { r ->
                    CatalogItem(
                        title = r.title,
                        posterUrl = ServiceLocator.catalogRepository.imageProxyUrl(r.poster),
                        subtitle = r.year.takeIf { it.isNotBlank() },
                        searchTitle = r.title
                    )
                }
                posterAdapter.submit(items)
                emptyText.text = "没有找到相关内容"
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Simple relevance ranking: exact title match first, then title starts with the query, then
     * title contains it, then everything else; within a tier shorter titles (closer to the query)
     * come first. The sort is stable, so the server's order is kept among equals.
     */
    private fun rankByRelevance(results: List<SearchResult>, query: String): List<SearchResult> {
        val q = normalize(query)
        if (q.isEmpty()) return results
        fun tier(title: String): Int {
            val t = normalize(title)
            return when {
                t == q -> 0
                t.startsWith(q) -> 1
                t.contains(q) -> 2
                else -> 3
            }
        }
        return results.sortedWith(compareBy<SearchResult>({ tier(it.title) }, { normalize(it.title).length }))
    }

    /**
     * Collapses the same movie offered by several sources into one entry (input order, i.e. the
     * relevance ranking, is kept). Same Douban id, or else same normalised title + year. The entry
     * kept is the first one, preferring a copy that actually has a poster.
     */
    private fun dedupeByMovie(results: List<SearchResult>): List<SearchResult> {
        val byKey = LinkedHashMap<String, SearchResult>()
        for (r in results) {
            val key = if (r.douban_id != null && r.douban_id > 0) "d${r.douban_id}" else "t${normalize(r.title)}|${r.year}"
            val existing = byKey[key]
            if (existing == null || (existing.poster.isBlank() && r.poster.isNotBlank())) byKey[key] = r
        }
        return byKey.values.toList()
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
