@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.*
import org.junit.Assert.*
import org.junit.Test

class SyncArchiveBiliCollectionBindingTest {
    private val track = SyncBiliCollectionBindingTrack(
        songId = 1L,
        songName = "骨中月",
        cid = 10L,
        page = 1,
        part = "骨中月",
        durationSec = 236
    )

    private val binding = SyncBiliCollectionBinding(
        targetKey = "netease:song:1",
        targetId = 1L,
        targetName = "骨中月",
        targetSubtitle = "镜予歌",
        bvid = "BV1xx411c7mD",
        avid = 42L,
        videoTitle = "骨中月合集",
        tracks = listOf(track),
        updatedAt = 10L,
        targetType = "song"
    )

    @Test
    fun `binding and its tombstone round-trip through archive kind 17`() {
        val tombstone = binding.copy(isDeleted = true, bvid = "", avid = 0L, tracks = emptyList(), updatedAt = 20L)
        val input = SyncData(lastModified = 1, biliCollectionBindings = listOf(binding, tombstone))
        assertEquals(input, ProtoBuf.decodeFromByteArray<SyncData>(ProtoBuf.encodeToByteArray(input)))

        val bytes = ByteArrayOutputStream()
        val count = SyncArchiveRecords.write(input, bytes)

        assertEquals(2L, count)
        assertTrue(SyncArchiveRecords.header(input).biliCollectionBindings.isEmpty())
        val result = SyncArchiveRecords.read(
            SyncArchiveRecords.header(input),
            bytes.toByteArray().inputStream(),
            count,
            bytes.size().toLong()
        )
        assertEquals(input.biliCollectionBindings, result.biliCollectionBindings)
        assertTrue(result.biliCollectionBindings.last().isDeleted)
    }
}
