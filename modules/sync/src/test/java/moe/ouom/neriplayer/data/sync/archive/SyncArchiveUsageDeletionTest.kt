@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SyncArchiveUsageDeletionTest {
    @Test fun usageProofAndPermanentDeletionRoundTripThroughProtobufAndArchiveKind16() {
        val token = SyncCausalToken("usage-delete:a", 1)
        val stat = SyncPlaylistUsageStat(playlistKey = "local:7", id = 7, source = "local", observedDeletionTokens = listOf(token))
        val deletion = SyncPlaylistUsageDeletion("local:7", listOf(token), 9)
        val input = SyncData(lastModified = 1, playlistUsageStats = listOf(stat), playlistUsageDeletions = listOf(deletion))
        assertEquals(input, ProtoBuf.decodeFromByteArray<SyncData>(ProtoBuf.encodeToByteArray(input)))
        val bytes = ByteArrayOutputStream()
        val count = SyncArchiveRecords.write(input, bytes)
        assertEquals(2L, count)
        val header = SyncArchiveRecords.header(input)
        assertTrue(header.playlistUsageDeletions.isEmpty())
        val result = SyncArchiveRecords.read(header, bytes.toByteArray().inputStream(), count, bytes.size().toLong())
        assertEquals(input.playlistUsageStats, result.playlistUsageStats)
        assertEquals(input.playlistUsageDeletions, result.playlistUsageDeletions)
    }
}
