package moe.ouom.neriplayer.data.local.playlist.sync

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
 * File: moe.ouom.neriplayer.data.local.playlist.sync/NeteasePlaylistBatchAdd
 * Created: 2026/8/10
 */

internal data class NeteasePlaylistBatchAddResult(
    val addedIds: Set<Long>,
    val failedIds: Set<Long>
)

internal data class NeteasePlaylistFailedSongResolution(
    val unresolvedFailedIds: Set<Long>,
    val skippedUnsupported: Int
)

internal fun addNeteasePlaylistSongIdsInBatches(
    songIds: List<Long>,
    batchSize: Int,
    addBatch: (List<Long>) -> Boolean
): NeteasePlaylistBatchAddResult {
    require(batchSize > 0) { "batchSize must be positive" }
    val distinctIds = songIds.asSequence()
        .filter { it > 0L }
        .distinct()
        .toList()
    if (distinctIds.isEmpty()) {
        return NeteasePlaylistBatchAddResult(
            addedIds = emptySet(),
            failedIds = emptySet()
        )
    }

    val addedIds = LinkedHashSet<Long>(distinctIds.size)
    val failedIds = LinkedHashSet<Long>()

    fun submit(ids: List<Long>) {
        if (ids.isEmpty()) return
        val added = runCatching { addBatch(ids) }.getOrDefault(false)
        if (added) {
            addedIds.addAll(ids)
            return
        }
        if (ids.size == 1) {
            failedIds.add(ids.single())
            return
        }
        val midpoint = ids.size / 2
        submit(ids.subList(0, midpoint))
        submit(ids.subList(midpoint, ids.size))
    }

    distinctIds.chunked(batchSize).forEach(::submit)
    return NeteasePlaylistBatchAddResult(
        addedIds = addedIds,
        failedIds = failedIds
    )
}

internal fun classifyNeteasePlaylistAddFailures(
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
