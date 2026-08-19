package com.orion.tv.player

import com.orion.tv.data.remote.dto.PlayRecord
import com.orion.tv.data.repo.PlayRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Throttles /api/playrecords saves to once per 10s during normal playback (mirrors OrionTV's PlayRecordManager), with an immediate/forced path for pause, source switch, and episode change. */
class PlaybackProgressTracker(
    private val repository: PlayRecordRepository,
    private val scope: CoroutineScope
) {
    private var lastSaveAtMs = 0L

    fun onProgress(source: String, id: String, record: PlayRecord, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAtMs < THROTTLE_MS) return
        lastSaveAtMs = now
        scope.launch { runCatching { repository.save(source, id, record) } }
    }

    companion object {
        private const val THROTTLE_MS = 10_000L
    }
}
