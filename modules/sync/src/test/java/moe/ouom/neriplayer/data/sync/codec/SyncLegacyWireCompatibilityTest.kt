@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.codec

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.sync.SyncAction
import moe.ouom.neriplayer.data.model.sync.SyncData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SyncLegacyWireCompatibilityTest {
    @Test
    fun `legacy addedAt tag is migrated across playlist favorite history and log`() {
        val song = LegacySong(id = 42L, addedAt = 123L)
        val fixture = LegacyData(
            playlists = listOf(LegacyPlaylist(7L, "playlist", listOf(song))),
            favorites = listOf(LegacyFavorite(8L, "favorite", listOf(song), 456L)),
            recent = listOf(LegacyRecent(42L, song, 789L)),
            log = listOf(LegacyLog(1000L, SyncAction.ADD_SONG))
        )
        val decoded = SyncDataSerializer.deserialize(gzip(ProtoBuf.encodeToByteArray(fixture)))
        assertEquals(42L, decoded.playlists.single().songs.single().id)
        assertEquals(123L, decoded.playlists.single().songs.single().addedAt)
        assertEquals(456L, decoded.favoritePlaylists.single().modifiedAt)
        assertEquals(789L, decoded.recentPlays.single().playedAt)
        assertEquals(SyncAction.ADD_SONG, decoded.syncLog.single().action)
        assertEquals("", decoded.deviceId)
    }

    @Test
    fun `gzip expansion beyond the decoded limit fails before protobuf parsing`() {
        val compressed = gzip(ByteArray(16 * 1024 * 1024 + 1))
        val result = runCatching { SyncDataSerializer.deserialize(compressed) }
        assertTrue(result.isFailure)
        assertEquals("Decompressed sync data is too large", result.exceptionOrNull()?.message)
    }

    @Test
    fun `json whitespace and binary size limits are recognized`() {
        val data = SyncData(deviceId = "remote")
        val bytes = SyncDataSerializer.serialize(data, false)
        assertEquals("remote", SyncDataSerializer.deserialize(" \n\r\t".toByteArray() + bytes).deviceId)
        assertTrue(runCatching { SyncDataSerializer.ensureRemoteContentSize(ByteArray(12 * 1024 * 1024 + 1)) }.isFailure)
        val largeJson = ByteArray(8 * 1024 * 1024 + 1)
        largeJson[0] = '{'.code.toByte()
        assertTrue(runCatching { SyncDataSerializer.ensureRemoteContentSize(largeJson) }.isFailure)
    }

    @Test
    fun `utf8 bom json keeps its payload`() {
        val data = SyncData(deviceId = "bom-remote")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + " \n\r\t".toByteArray() + SyncDataSerializer.serialize(data, false)
        assertEquals("bom-remote", SyncDataSerializer.deserialize(bytes).deviceId)
    }

    @Test
    fun `invalid gzip protobuf rejects both current and legacy schemas`() {
        assertTrue(runCatching { SyncDataSerializer.deserialize(gzip(byteArrayOf(0))) }.isFailure)
        val oversized = ByteArray(12 * 1024 * 1024 + 1)
        oversized[0] = 0x1F.toByte()
        oversized[1] = 0x8B.toByte()
        assertEquals(
            "Compressed sync data is too large",
            runCatching { SyncDataSerializer.deserialize(oversized) }.exceptionOrNull()?.message
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    @Serializable
    private data class LegacyData(
        @ProtoNumber(5) val playlists: List<LegacyPlaylist>,
        @ProtoNumber(6) val favorites: List<LegacyFavorite>,
        @ProtoNumber(7) val recent: List<LegacyRecent>,
        @ProtoNumber(8) val log: List<LegacyLog>
    )

    @Serializable
    private data class LegacyPlaylist(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val songs: List<LegacySong>
    )

    @Serializable
    private data class LegacySong(@ProtoNumber(1) val id: Long, @ProtoNumber(8) val addedAt: Long)

    @Serializable
    private data class LegacyFavorite(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(6) val songs: List<LegacySong>,
        @ProtoNumber(7) val addedAt: Long
    )

    @Serializable
    private data class LegacyRecent(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val song: LegacySong,
        @ProtoNumber(3) val playedAt: Long
    )

    @Serializable
    private data class LegacyLog(@ProtoNumber(1) val timestamp: Long, @ProtoNumber(3) val action: SyncAction)
}
