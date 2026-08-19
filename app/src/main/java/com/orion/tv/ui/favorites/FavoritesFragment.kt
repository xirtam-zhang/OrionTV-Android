package com.orion.tv.ui.favorites

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
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
 * GET /api/favorites 的收藏夹网格：普通 RecyclerView + GridLayoutManager（跟首页一致），不用
 * Leanback 的高层网格组件——更简单、老设备上更流畅。Unfavoriting happens on the Detail screen's
 * existing favorite toggle.
 */
class FavoritesFragment : Fragment(R.layout.fragment_favorites) {

    private lateinit var grid: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var posterAdapter: PosterAdapter

    private var loadJob: Job? = null

    private val cancelLoadOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            loadJob?.cancel()
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        grid = view.findViewById(R.id.favorites_grid)
        progressBar = view.findViewById(R.id.favorites_progress)
        emptyText = view.findViewById(R.id.favorites_empty_text)

        posterAdapter = PosterAdapter { requireContext().openDetail(it) }
        grid.apply {
            layoutManager = GridLayoutManager(requireContext(), GridUtils.computeColumns(requireContext(), itemWidthDp = 142, minColumns = 3, maxColumns = 10))
            adapter = posterAdapter
            setHasFixedSize(true)
            itemAnimator = null
            addItemDecoration(GridSpacingItemDecoration((10 * resources.displayMetrics.density).toInt()))
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cancelLoadOnBack)
        loadFavorites()
    }

    private fun loadFavorites() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val favorites = runCatching { ServiceLocator.favoriteRepository.getAll() }.getOrDefault(emptyMap())
                val items = favorites.entries
                    .sortedByDescending { it.value.save_time }
                    .mapNotNull { (key, favorite) ->
                        val idx = key.indexOf('+')
                        if (idx <= 0) return@mapNotNull null
                        CatalogItem(
                            title = favorite.title,
                            posterUrl = ServiceLocator.catalogRepository.imageProxyUrl(favorite.cover),
                            subtitle = favorite.source_name,
                            source = key.substring(0, idx),
                            sourceId = key.substring(idx + 1),
                            searchTitle = favorite.search_title ?: favorite.title
                        )
                    }
                posterAdapter.submit(items)
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }
}
