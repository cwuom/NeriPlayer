package moe.ouom.neriplayer.data.sync.runtime.dataset

import moe.ouom.neriplayer.data.model.sync.SyncData

suspend fun SyncDataset.readForTest(): SyncData {
    val tracks = playback.openTracks().use { cursor ->
        buildList { while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; addAll(page) } }
    }
    val buckets = playback.openBuckets().use { cursor ->
        buildList { while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; addAll(page) } }
    }
    return data.copy(playbackStats = tracks, playbackStatBuckets = buckets)
}
