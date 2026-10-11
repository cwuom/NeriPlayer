package moe.ouom.neriplayer.platform.netease.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.netease.playlist/NeteasePlaylistBatchAdd
 * Created: 2026/8/10
 */

import moe.ouom.neriplayer.common.logging.NPLogger

/** 整个请求被拒绝时拆小批次也会得到同样结果, #445 中逐首重试仍然返回 524 */
private val NETEASE_WHOLE_BATCH_REJECTION_CODES = setOf(301, 401, 524)

/** 限流或网络异常时整次同步最多退避重试的次数 */
private const val NETEASE_PLAYLIST_ADD_MAX_TRANSIENT_RETRIES = 3
private const val NETEASE_PLAYLIST_ADD_BACKOFF_MS = 1_000L

/** 拆分和重试最多额外发出的请求数, 避免大量失败时请求数成倍增长 */
private const val NETEASE_PLAYLIST_ADD_EXTRA_REQUESTS = 30

/** 单次添加请求的结果 */
sealed interface NeteasePlaylistAddOutcome {
    data object Ok : NeteasePlaylistAddOutcome

    /** 网易云明确拒绝了这批歌曲, [message] 是接口返回的原因 */
    data class Rejected(val code: Int, val message: String?) : NeteasePlaylistAddOutcome {
        val reason: String
            get() = message ?: "code $code"
    }

    /** 网络异常、响应无法解析或被限流, 稍后重试可能成功 */
    data class Transient(val code: Int, val message: String?) : NeteasePlaylistAddOutcome
}

data class NeteasePlaylistBatchAddResult(
    val addedIds: Set<Long>,
    val failedIds: Set<Long>,
    /** [failedIds] 中被网易云明确拒绝的歌曲及拒绝原因 */
    val rejections: Map<Long, NeteasePlaylistAddOutcome.Rejected> = emptyMap()
)

data class NeteasePlaylistFailedSongResolution(
    val unresolvedFailedIds: Set<Long>,
    val skippedUnsupported: Int
)

/** [pause] 用于限流退避, 参数是需要等待的毫秒数 */
fun addNeteasePlaylistSongIdsInBatches(
    songIds: List<Long>,
    batchSize: Int,
    pause: (Long) -> Unit,
    addBatch: (List<Long>) -> NeteasePlaylistAddOutcome
): NeteasePlaylistBatchAddResult {
    require(batchSize > 0) { "batchSize must be positive" }
    val batches = songIds.asSequence()
        .filter { it > 0L }
        .distinct()
        .chunked(batchSize)
        .toList()
    val adder = NeteasePlaylistBatchAdder(
        addBatch = addBatch,
        pause = pause,
        remainingRequests = batches.size + NETEASE_PLAYLIST_ADD_EXTRA_REQUESTS
    )
    batches.forEach(adder::submit)
    return adder.result()
}

private class NeteasePlaylistBatchAdder(
    private val addBatch: (List<Long>) -> NeteasePlaylistAddOutcome,
    private val pause: (Long) -> Unit,
    private var remainingRequests: Int
) {
    private val addedIds = LinkedHashSet<Long>()
    private val failedIds = LinkedHashSet<Long>()
    private val rejections = LinkedHashMap<Long, NeteasePlaylistAddOutcome.Rejected>()
    private var transientRetries = 0
    private var stopped = false

    fun submit(ids: List<Long>) {
        if (!stopped && remainingRequests <= 0) {
            NPLogger.w("LocalPlaylistRepo", "addSongsToPlaylist request budget exhausted, ${ids.size} songs not sent")
            stopped = true
        }
        if (stopped) {
            failedIds.addAll(ids)
            return
        }
        remainingRequests -= 1
        val outcome = runCatching { addBatch(ids) }
            .getOrElse { NeteasePlaylistAddOutcome.Transient(-1, it.message) }
        when (outcome) {
            is NeteasePlaylistAddOutcome.Rejected -> reject(ids, outcome)
            is NeteasePlaylistAddOutcome.Transient -> retryLater(ids, outcome)
            else -> addedIds.addAll(ids)
        }
    }

    fun result() = NeteasePlaylistBatchAddResult(addedIds, failedIds, rejections)

    private fun reject(ids: List<Long>, rejected: NeteasePlaylistAddOutcome.Rejected) {
        if (ids.size > 1 && rejected.code !in NETEASE_WHOLE_BATCH_REJECTION_CODES) {
            val midpoint = ids.size / 2
            submit(ids.subList(0, midpoint))
            submit(ids.subList(midpoint, ids.size))
            return
        }
        NPLogger.w("LocalPlaylistRepo", "NetEase rejected songs $ids: code=${rejected.code}, message=${rejected.message}")
        failedIds.addAll(ids)
        ids.forEach { rejections[it] = rejected }
    }

    private fun retryLater(ids: List<Long>, transient: NeteasePlaylistAddOutcome.Transient) {
        if (transientRetries >= NETEASE_PLAYLIST_ADD_MAX_TRANSIENT_RETRIES) {
            NPLogger.w("LocalPlaylistRepo", "addSongsToPlaylist still failing after $transientRetries retries, stop syncing: $transient")
            stopped = true
            failedIds.addAll(ids)
            return
        }
        pause(NETEASE_PLAYLIST_ADD_BACKOFF_MS shl transientRetries)
        transientRetries += 1
        submit(ids)
    }
}

fun classifyNeteasePlaylistAddFailures(
    failedIds: Collection<Long>,
    batchSize: Int,
    resolveBatch: (List<Long>) -> Set<Long>?
): NeteasePlaylistFailedSongResolution {
    require(batchSize > 0) { "batchSize must be positive" }
    val distinctIds = failedIds.asSequence()
        .filter { it > 0L }
        .distinct()
        .toList()
    if (distinctIds.isEmpty()) {
        return NeteasePlaylistFailedSongResolution(
            unresolvedFailedIds = emptySet(),
            skippedUnsupported = 0
        )
    }

    val unresolvedFailedIds = LinkedHashSet<Long>(distinctIds.size)
    var skippedUnsupported = 0
    distinctIds.chunked(batchSize).forEach { ids ->
        val resolvedIds = resolveBatch(ids)
        if (resolvedIds == null) {
            unresolvedFailedIds.addAll(ids)
            return@forEach
        }
        ids.forEach { id ->
            if (id in resolvedIds) {
                unresolvedFailedIds.add(id)
            } else {
                skippedUnsupported += 1
            }
        }
    }
    return NeteasePlaylistFailedSongResolution(
        unresolvedFailedIds = unresolvedFailedIds,
        skippedUnsupported = skippedUnsupported
    )
}
