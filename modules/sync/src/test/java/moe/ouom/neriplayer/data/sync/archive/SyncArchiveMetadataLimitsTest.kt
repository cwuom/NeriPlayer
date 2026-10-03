@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.IOException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataBudget
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncArchiveMetadataLimitsTest {
    @Test fun zeroAndNegativePayloadCapacityAreRejected() {
        for (bytes in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveMetadataLimits(maxPayloadBytes = bytes, maxObjects = 1)
            }
        }
    }

    @Test fun zeroAndNegativeObjectCapacityAreRejected() {
        for (objects in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveMetadataLimits(maxPayloadBytes = 1, maxObjects = objects)
            }
        }
    }

    @Test fun minimumPositiveCapacityCanEncodeAndDecodeOneDefaultSong() {
        val limits = SyncArchiveMetadataLimits(maxPayloadBytes = 1, maxObjects = 1)
        val song = SyncSong()
        val writer = SyncArchiveMetadataBudget(limits)
        val payload = writer.encode(SyncSong.serializer(), song) {}
        val reader = SyncArchiveMetadataBudget(limits)
        reader.beginRecord(payload.size)
        reader.inspect(2, payload) {}
        assertEquals(song, ProtoBuf.decodeFromByteArray<SyncSong>(payload))
        assertThrows(IOException::class.java) { writer.encode(SyncSong.serializer(), song) {} }
        assertThrows(IOException::class.java) { reader.beginRecord(payload.size) }
    }
}
