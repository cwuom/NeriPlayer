@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.dataset

import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_BYTES
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS

import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

internal class SyncPlaybackPageWriter<T>(
    private val serializer: KSerializer<T>,
    private val consume: suspend (List<T>) -> Unit
) {
    private val page = ArrayList<T>()
    private var bytes = 0L

    suspend fun add(record: T) {
        val size = ProtoBuf.encodeToByteArray(serializer, record).size
        if (page.isNotEmpty() && bytes + size > SYNC_PLAYBACK_PAGE_BYTES) finish()
        page.add(record)
        bytes += size
        if (page.size == SYNC_PLAYBACK_PAGE_RECORDS || bytes >= SYNC_PLAYBACK_PAGE_BYTES) finish()
    }

    suspend fun finish() {
        if (page.isEmpty()) return
        consume(page)
        page.clear()
        bytes = 0L
    }
}
