package com.orion.tv.player

/**
 * Removes ad segments spliced into HLS media playlists by the upstream Apple-CMS-style source
 * sites. Unlike MoonTV's own web client (which only deletes the `#EXT-X-DISCONTINUITY` tag text
 * and leaves the ad segment URIs in the playlist — see MoonTV-main src/app/play/page.tsx), this
 * actually drops the ad segments themselves so ExoPlayer never fetches/plays them.
 *
 * Heuristic: a discontinuity-free run of segments is a "block". Real inserted ads in these CMS
 * streams are single short breaks (pre/mid/post-roll, typically well under two minutes); genuine
 * episode content can legitimately span *multiple* discontinuity-separated blocks (e.g. content
 * resuming after a mid-roll ad), so we can't just keep "the one largest block" — instead we drop
 * every block short enough to plausibly be an ad and keep everything else, as long as doing so
 * wouldn't drop more than half of the total playlist duration (safety net against misclassifying
 * on an unusual stream).
 */
object AdSegmentFilter {

    private const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val EXTINF_PREFIX = "#EXTINF:"
    private const val ENDLIST = "#EXT-X-ENDLIST"
    private const val MAX_DROP_RATIO = 0.5

    /** Blocks at or under this duration are treated as ad-break candidates. */
    private const val AD_BLOCK_MAX_SECONDS = 90.0

    private data class Segment(val extinfLine: String, val uriLine: String, val durationSeconds: Double)

    fun filter(playlist: String): String {
        if (!playlist.contains(DISCONTINUITY)) return playlist

        val lines = playlist.split("\n").map { it.trimEnd('\r') }
        val firstSegmentIdx = lines.indexOfFirst { it.startsWith(EXTINF_PREFIX) || it.startsWith(DISCONTINUITY) }
        if (firstSegmentIdx < 0) return playlist
        val header = lines.subList(0, firstSegmentIdx)

        val blocks = mutableListOf<MutableList<Segment>>()
        var current = mutableListOf<Segment>()
        var endList = false
        var i = firstSegmentIdx
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith(DISCONTINUITY) -> {
                    if (current.isNotEmpty()) {
                        blocks.add(current)
                        current = mutableListOf()
                    }
                    i++
                }
                line.startsWith(EXTINF_PREFIX) -> {
                    val duration = line.removePrefix(EXTINF_PREFIX).substringBefore(',').toDoubleOrNull() ?: 0.0
                    val uri = if (i + 1 < lines.size) lines[i + 1] else ""
                    current.add(Segment(line, uri, duration))
                    i += 2
                }
                line.trim() == ENDLIST -> {
                    endList = true
                    i++
                }
                else -> i++
            }
        }
        if (current.isNotEmpty()) blocks.add(current)
        if (blocks.size <= 1) return playlist

        val blockDurations = blocks.map { block -> block.sumOf { it.durationSeconds } }
        val totalDuration = blockDurations.sum()
        if (totalDuration <= 0) return playlist

        val adIndices = blockDurations.indices.filter { blockDurations[it] <= AD_BLOCK_MAX_SECONDS }.toSet()
        if (adIndices.isEmpty()) return playlist

        val droppedDuration = adIndices.sumOf { blockDurations[it] }
        if (droppedDuration / totalDuration > MAX_DROP_RATIO) return playlist

        val keptIndices = blockDurations.indices.filter { it !in adIndices }
        if (keptIndices.isEmpty()) return playlist

        return buildString {
            header.forEach { append(it).append('\n') }
            keptIndices.forEach { index ->
                blocks[index].forEach { segment ->
                    append(segment.extinfLine).append('\n')
                    append(segment.uriLine).append('\n')
                }
            }
            if (endList) append(ENDLIST).append('\n')
        }
    }
}
