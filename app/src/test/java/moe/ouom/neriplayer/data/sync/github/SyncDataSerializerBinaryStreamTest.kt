package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 省流二进制写路径与 read-both 的兼容测试
 *
 * 覆盖: 省流写出=原始 GZIP, read-both 仍兼容历史 base64/JSON, WebDAV 原始字节读写,
 * 损坏远端安全失败
 */
class SyncDataSerializerBinaryStreamTest {

    private val gzipMagic0 = 0x1F.toByte()
    private val gzipMagic1 = 0x8B.toByte()

    // 核心: 省流写路径必须直接输出 GZIP 字节, 不能再叠加 Base64 文本
    @Test
    fun `data saver upload is raw gzip bytes`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        assertTrue("省流产物必须是原始 GZIP 字节", isGzip(body))
        assertMatchesSample(SyncDataSerializer.deserialize(body))
    }

    // read-both: 历史 Base64(GZIP) 文本仍必须可读, 以便升级后迁移旧远端备份
    @Test
    fun `read both still reads legacy base64 gzip`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        val legacyBase64 = Base64.getEncoder().encodeToString(body)

        assertMatchesSample(SyncDataSerializer.deserialize(legacyBase64.toByteArray(Charsets.UTF_8)))
    }

    // 原始 GZIP 往返; 再次序列化字节一致, 证明序列化层无额外归一化/丢字段
    @Test
    fun `raw gzip round trip is stable`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        val decoded = SyncDataSerializer.deserialize(body)
        assertMatchesSample(decoded)
        assertArrayEquals(body, SyncDataSerializer.serialize(decoded, useDataSaver = true))
    }

    // 非省流写出 UTF-8 JSON 字节, 往返可读回 (含 WebDAV 旧 JSON 文件场景)
    @Test
    fun `json bytes round trip`() {
        val data = sampleData()
        val jsonBytes = SyncDataSerializer.serialize(data, useDataSaver = false)

        assertEquals('{'.code.toByte(), jsonBytes[0])
        assertFalse("JSON 不应被识别为 GZIP", isGzip(jsonBytes))

        assertMatchesSample(SyncDataSerializer.deserialize(jsonBytes))
    }

    @Test
    fun `data saver writes a new raw file without replacing legacy binary backup`() {
        assertEquals("backup-raw.bin", SyncDataSerializer.getFileName(useDataSaver = true))
        assertEquals("backup.json", SyncDataSerializer.getFileName(useDataSaver = false))
        assertEquals(
            listOf("backup.bin", "backup.json"),
            SyncDataSerializer.getReadFallbackFileNames(useDataSaver = true)
        )
        assertEquals(
            listOf("backup-raw.bin", "backup.bin"),
            SyncDataSerializer.getReadFallbackFileNames(useDataSaver = false)
        )
    }

    @Test
    fun `oversized json upload fails before transport`() {
        val data = SyncData(deviceName = "x".repeat(8 * 1024 * 1024))

        assertThrowsAny {
            SyncDataSerializer.serialize(data, useDataSaver = false)
        }
    }

    @Test
    fun `oversized uncompressed binary upload fails before transport`() {
        val data = SyncData(deviceName = "x".repeat(16 * 1024 * 1024 + 1))

        assertThrowsAny {
            SyncDataSerializer.serialize(data, useDataSaver = true)
        }
    }

    @Test
    fun `legacy safe serialization expands ranges for old clients`() {
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            playlists = listOf(
                SyncPlaylist(
                    id = 1L,
                    name = "playlist",
                    songs = listOf(
                        SyncSong(
                            id = 1L,
                            name = "song",
                            syncMembershipTokens = listOf(
                                SyncCausalToken("device", 1L),
                                SyncCausalToken("device", 2L),
                                SyncCausalToken("device", 3L)
                            )
                        )
                    )
                )
            )
        )

        listOf(false, true).forEach { useDataSaver ->
            val decoded = SyncDataSerializer.deserialize(
                SyncDataSerializer.serialize(data, useDataSaver)
            )
            assertEquals(
                listOf(
                    SyncCausalToken("device", 1L),
                    SyncCausalToken("device", 2L),
                    SyncCausalToken("device", 3L)
                ),
                decoded.playlists.single().songs.single().syncMembershipTokens
            )
        }
    }

    @Test
    fun `range capable serialization keeps compact range explicitly`() {
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            playlists = listOf(
                SyncPlaylist(
                    id = 1L,
                    songs = listOf(
                        SyncSong(
                            id = 1L,
                            syncMembershipTokens = listOf(
                                SyncCausalToken("device", 1L),
                                SyncCausalToken("device", 2L),
                                SyncCausalToken("device", 3L)
                            )
                        )
                    )
                )
            )
        )

        val decoded = SyncDataSerializer.deserialize(
            SyncDataSerializer.serialize(
                data,
                useDataSaver = true,
                compatibility = SyncSerializationCompatibility.RANGE_V1
            )
        )

        assertEquals(
            listOf(SyncCausalToken("device", 1L, counterEnd = 3L)),
            decoded.playlists.single().songs.single().syncMembershipTokens
        )
    }

    @Test
    fun `fragmented causal ranges fail before either upload format`() {
        val tokens = (1L..65L).map { counter ->
            SyncCausalToken("device", counter * 2L)
        }
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            playlists = listOf(
                SyncPlaylist(
                    id = 1L,
                    songs = listOf(SyncSong(id = 1L, syncMembershipTokens = tokens))
                )
            )
        )

        listOf(false, true).forEach { useDataSaver ->
            assertThrowsAny {
                SyncDataSerializer.serialize(data, useDataSaver)
            }
        }
    }

    @Test
    fun `fragmented recent play causal ranges fail before either upload format`() {
        val tokens = (1L..65L).map { counter ->
            SyncCausalToken("device", counter * 2L)
        }
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            recentPlays = listOf(
                SyncRecentPlay(
                    songId = 1L,
                    song = SyncSong(id = 1L, syncMembershipTokens = tokens),
                    playedAt = 1L,
                    deviceId = "device"
                )
            )
        )

        listOf(false, true).forEach { useDataSaver ->
            assertThrowsAny {
                SyncDataSerializer.serialize(data, useDataSaver)
            }
        }
    }

    @Test
    fun `legacy expansion rejects an unbounded range before either upload format`() {
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            playlists = listOf(
                SyncPlaylist(
                    id = 1L,
                    songs = listOf(
                        SyncSong(
                            id = 1L,
                            syncMembershipTokens = listOf(
                                SyncCausalToken(
                                    deviceId = "device",
                                    counter = 1L,
                                    counterEnd = 8_193L
                                )
                            )
                        )
                    )
                )
            )
        )

        listOf(false, true).forEach { useDataSaver ->
            assertThrowsAny {
                SyncDataSerializer.serialize(data, useDataSaver)
            }
        }
    }

    @Test
    fun `too many playlist deletion records fail before either upload format`() {
        val data = SyncData(
            deviceId = "device",
            deviceName = "test",
            playlistSongDeletions = (0L..5_000L).map { songId ->
                SyncPlaylistSongDeletion(
                    playlistId = 1L,
                    songId = songId,
                    album = "netease",
                    deletedAt = songId,
                    deviceId = "device"
                )
            }
        )

        listOf(false, true).forEach { useDataSaver ->
            assertThrowsAny {
                SyncDataSerializer.serialize(data, useDataSaver)
            }
        }
    }

    @Test
    fun `oversized remote body fails before parsing`() {
        val oversizedJson = ("{" + "x".repeat(8 * 1024 * 1024) + "}")
            .toByteArray(Charsets.UTF_8)

        assertThrowsAny {
            SyncDataSerializer.deserialize(oversizedJson)
        }
    }

    @Test
    fun `json with utf8 bom is decoded after format detection`() {
        val json = SyncDataSerializer.serialize(sampleData(), useDataSaver = false)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json

        assertMatchesSample(SyncDataSerializer.deserialize(withBom))
    }

    // 二进制通道必须保留原始 GZIP 字节, 不能将其转成 UTF-8 文本
    @Test
    fun `binary transport body stays raw gzip`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        assertTrue(isGzip(body))
        assertFalse(body.toString(Charsets.UTF_8).startsWith("H4sI"))
        assertMatchesSample(SyncDataSerializer.deserialize(body))
    }

    // WebDAV 二进制通道: 省流传原始 GZIP, 非省流传 JSON, 两者均可读回
    @Test
    fun `webdav text channel read write`() {
        val data = sampleData()

        val binaryBody = SyncDataSerializer.serialize(data, useDataSaver = true)
        assertTrue(isGzip(binaryBody))
        assertMatchesSample(SyncDataSerializer.deserialize(binaryBody))

        val jsonBody = SyncDataSerializer.serialize(data, useDataSaver = false)
        assertMatchesSample(SyncDataSerializer.deserialize(jsonBody))
    }

    // 损坏/空远端必须抛错, 交由上层走"远端损坏"路径 (不覆盖本地)
    @Test
    fun `corrupt or empty remote fails safely`() {
        assertThrowsAny { SyncDataSerializer.deserialize(ByteArray(0)) }
        // 命中 GZIP 魔数但内容截断/非法, 解压必然失败
        assertThrowsAny {
            SyncDataSerializer.deserialize(byteArrayOf(gzipMagic0, gzipMagic1, 0x08, 0x00, 0x01))
        }
    }

    @Test
    fun `legacy base64 rejects illegal characters before decompression`() {
        assertThrowsAny {
            SyncDataSerializer.deserialize("H4sI!invalid".toByteArray(Charsets.UTF_8))
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == gzipMagic0 && bytes[1] == gzipMagic1

    private fun assertThrowsAny(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception but none was thrown", threw)
    }

    private fun sampleData(): SyncData = SyncData(
        version = "2.0",
        deviceId = "device-android",
        deviceName = "Pixel Test",
        lastModified = 1_700_000_000_000L,
        playlists = listOf(
            SyncPlaylist(
                id = 42L,
                name = "我的歌单",
                songs = listOf(
                    SyncSong(
                        id = 1001L,
                        name = "Song A",
                        artist = "Artist A",
                        album = "Album A",
                        albumId = 5L,
                        durationMs = 210_000L,
                        coverUrl = "https://cdn.example/a.jpg",
                        addedAt = 1_699_000_000_000L
                    ),
                    SyncSong(
                        id = 1002L,
                        name = "歌曲乙",
                        artist = "演唱者",
                        album = "专辑",
                        albumId = 6L,
                        durationMs = 180_000L,
                        coverUrl = null,
                        addedAt = 1_699_500_000_000L
                    )
                ),
                createdAt = 42L,
                modifiedAt = 1_700_000_000_000L
            )
        ),
        recentPlays = listOf(
            SyncRecentPlay(
                songId = 1001L,
                song = SyncSong(
                    id = 1001L,
                    name = "Song A",
                    artist = "Artist A",
                    album = "Album A",
                    durationMs = 210_000L
                ),
                playedAt = 1_700_000_000_000L,
                deviceId = "device-android",
                resumePositionMs = 75_000L
            )
        )
    )

    private fun assertMatchesSample(decoded: SyncData) {
        assertEquals("2.0", decoded.version)
        assertEquals("device-android", decoded.deviceId)
        assertEquals("Pixel Test", decoded.deviceName)
        assertEquals(1_700_000_000_000L, decoded.lastModified)

        val playlist = decoded.playlists.single()
        assertEquals(42L, playlist.id)
        assertEquals("我的歌单", playlist.name)
        assertEquals(2, playlist.songs.size)

        val first = playlist.songs[0]
        assertEquals(1001L, first.id)
        assertEquals("Song A", first.name)
        assertEquals("Artist A", first.artist)
        assertEquals(210_000L, first.durationMs)
        assertEquals("https://cdn.example/a.jpg", first.coverUrl)

        val second = playlist.songs[1]
        assertEquals(1002L, second.id)
        assertEquals("歌曲乙", second.name)
        assertEquals("演唱者", second.artist)
        assertEquals(null, second.coverUrl)

        val recentPlay = decoded.recentPlays.single()
        assertEquals(1001L, recentPlay.songId)
        assertEquals(75_000L, recentPlay.resumePositionMs)
    }
}
