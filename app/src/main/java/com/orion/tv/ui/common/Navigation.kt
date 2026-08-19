package com.orion.tv.ui.common

import android.content.Context
import android.content.Intent
import com.orion.tv.ui.detail.DetailActivity

/** Shared Home/Search -> Detail navigation, keeping the extras contract in one place. */
fun Context.openDetail(item: CatalogItem) {
    val intent = Intent(this, DetailActivity::class.java).apply {
        putExtra(DetailActivity.EXTRA_TITLE, item.title)
        putExtra(DetailActivity.EXTRA_SEARCH_TITLE, item.searchTitle ?: item.title)
        item.source?.let { putExtra(DetailActivity.EXTRA_SOURCE, it) }
        item.sourceId?.let { putExtra(DetailActivity.EXTRA_SOURCE_ID, it) }
    }
    startActivity(intent)
}
