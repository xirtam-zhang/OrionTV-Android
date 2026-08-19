package com.orion.tv.ui.common

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Uniform spacing on every side of every grid cell — simplest possible row/column gap. */
class GridSpacingItemDecoration(private val spacingPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(spacingPx, spacingPx, spacingPx, spacingPx)
    }
}
