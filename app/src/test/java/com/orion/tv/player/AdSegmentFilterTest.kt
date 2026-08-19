package com.orion.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSegmentFilterTest {

    @Test
    fun `no discontinuity tags returns playlist unchanged`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:9.0,
            seg1.ts
            #EXTINF:9.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertEquals(playlist, AdSegmentFilter.filter(playlist))
    }

    @Test
    fun `drops short ad block between two long content blocks`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:600.0,
            main_a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:30.0,
            ad_1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:500.0,
            main_b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val filtered = AdSegmentFilter.filter(playlist)

        assertTrue("main_a.ts should be kept", filtered.contains("main_a.ts"))
        assertTrue("main_b.ts (post-ad content) should be kept", filtered.contains("main_b.ts"))
        assertFalse("ad_1.ts should be dropped", filtered.contains("ad_1.ts"))
        assertFalse("no discontinuity tags should remain", filtered.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(filtered.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `drops multiple short ad breaks`() {
        val playlist = """
            #EXTM3U
            #EXTINF:45.0,
            pre_roll_ad.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:1200.0,
            main.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:60.0,
            mid_roll_ad.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:400.0,
            main_2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val filtered = AdSegmentFilter.filter(playlist)

        assertFalse(filtered.contains("pre_roll_ad.ts"))
        assertFalse(filtered.contains("mid_roll_ad.ts"))
        assertTrue(filtered.contains("main.ts"))
        assertTrue(filtered.contains("main_2.ts"))
    }

    @Test
    fun `safety net leaves playlist untouched when candidate ad blocks exceed half the duration`() {
        val playlist = """
            #EXTM3U
            #EXTINF:40.0,
            part_a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:40.0,
            part_b.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:40.0,
            part_c.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        // All three blocks are individually below the ad-duration threshold, so together they'd
        // account for 100% of the playlist — the safety net must refuse to touch it.
        assertEquals(playlist, AdSegmentFilter.filter(playlist))
    }

    @Test
    fun `single block with discontinuity tag but nothing to split is left unchanged`() {
        val playlist = """
            #EXTM3U
            #EXT-X-DISCONTINUITY
            #EXTINF:9.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertEquals(playlist, AdSegmentFilter.filter(playlist))
    }
}
