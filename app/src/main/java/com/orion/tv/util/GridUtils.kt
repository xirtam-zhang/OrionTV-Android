package com.orion.tv.util

import android.content.Context

/**
 * OrionTV 1.3.3 made every screen responsive across phone/tablet/TV via width breakpoints
 * (mobile <768dp, tablet 768-1023dp, TV >=1024dp) instead of a fixed TV-only column count. This
 * mirrors that for our episode grid so it's usable on a phone screen instead of cramming a
 * TV-sized 5-column grid into it.
 */
object GridUtils {

    fun computeColumns(context: Context, itemWidthDp: Int = 150, minColumns: Int = 2, maxColumns: Int = 8): Int {
        val widthDp = context.resources.configuration.screenWidthDp
        val usableWidthDp = (widthDp - 64).coerceAtLeast(itemWidthDp)
        return (usableWidthDp / itemWidthDp).coerceIn(minColumns, maxColumns)
    }
}
