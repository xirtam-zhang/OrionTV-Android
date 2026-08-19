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
                val results = runCatching { ServiceLocator.catalogRepository.search(query) }.getOrDefault(emptyList())
                val items = results.map { r ->
                    CatalogItem(
                        title = r.title,
                        posterUrl = ServiceLocator.catalogRepository.imageProxyUrl(r.poster),
                        subtitle = r.source_name,
                        source = r.source,
                        sourceId = r.id,
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

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
