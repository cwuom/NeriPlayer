package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 二进制流传输改造的往返与迁移测试
 * 覆盖：raw 写-读往返、旧 base64 文本 read-both、GZIP 魔数识别边界、
 * GitHub base64-once 编码正确性、WebDAV octet-stream 读写、损坏远端安全失败
 */
class SyncDataSerializerBinaryStreamTest {

    private val GZIP_MAGIC_0 = 0x1F.toByte()
    private val GZIP_MAGIC_1 = 0x8B.toByte()

    // 省流模式写出的即为原始 GZIP(ProtoBuf) 字节，往返可读回
    @Test
    fun `raw compressed bytes round trip`() {
        val data = sampleData()
        val raw = SyncDataSerializer.serialize(data, useDataSaver = true)

        assertTrue("省流产物应为原始 GZIP 字节", isGzip(raw))

        val decoded = SyncDataSerializer.deserialize(raw)
        assertMatchesSample(decoded)

        // 解码后再次编码应字节一致，证明序列化层无额外归一化/丢字段
        assertArrayEquals(raw, SyncDataSerializer.serialize(decoded, useDataSaver = true))
    }

    // 非省流写出 UTF-8 JSON 字节，往返可读回（含 WebDAV 旧 JSON 文件场景）
    @Test
    fun `json bytes round trip`() {
        val data = sampleData()
        val jsonBytes = SyncDataSerializer.serialize(data, useDataSaver = false)

        assertEquals('{'.code.toByte(), jsonBytes[0])
        assertFalse("JSON 不应被识别为 GZIP", isGzip(jsonBytes))

        assertMatchesSample(SyncDataSerializer.deserialize(jsonBytes))
    }

    // read-both：旧 backup.bin 是 Base64(GZIP(ProtoBuf)) 文本，必须仍可读
    @Test
    fun `legacy base64 backup is still readable`() {
        val data = sampleData()
        val raw = SyncDataSerializer.serialize(data, useDataSaver = true)
        // 旧格式：对原始 GZIP 字节再做一层 Base64 得到文本，落盘为其 UTF-8 字节
        val legacyBytes = Base64.getEncoder().encodeToString(raw).toByteArray(Charsets.UTF_8)

        assertFalse("旧 base64 文本首字节不应命中 GZIP 魔数", isGzip(legacyBytes))
        assertMatchesSample(SyncDataSerializer.deserialize(legacyBytes))
    }

    // GZIP 魔数识别边界：raw 命中魔数走解压，JSON/base64 文本不命中
    @Test
    fun `gzip magic detection boundary`() {
        val data = sampleData()

        val raw = SyncDataSerializer.serialize(data, useDataSaver = true)
        assertTrue(isGzip(raw))

        val jsonBytes = SyncDataSerializer.serialize(data, useDataSaver = false)
        assertFalse(isGzip(jsonBytes))

        // 仅有单个 0x1F、不足两字节魔数，不应被当作 GZIP（会落入文本分支并最终失败）
        assertThrowsAny { SyncDataSerializer.deserialize(byteArrayOf(GZIP_MAGIC_0)) }
    }

    // GitHub 上传对原始字节做「一次」Base64；下载解码一次即得回原始 GZIP 字节
    @Test
    fun `github contents api base64 once round trip`() {
        val data = sampleData()
        val raw = SyncDataSerializer.serialize(data, useDataSaver = true)

        // 模拟 GitHubApiClient.updateFileContent：对原始字节 base64 一次
        val uploaded = Base64.getEncoder().encodeToString(raw)
        // 模拟 GitHubApiClient.getFileContent：content 字段 base64 解码一次
        val downloaded = Base64.getMimeDecoder().decode(uploaded)

        // 解码一次即得回原始 GZIP 字节，证明未叠加第二层 base64
        assertArrayEquals(raw, downloaded)
        assertTrue("单次 base64 解码后应直接是 GZIP 原始字节", isGzip(downloaded))

        assertMatchesSample(SyncDataSerializer.deserialize(downloaded))
    }

    // WebDAV octet-stream：省流传原始 GZIP 字节，非省流传 JSON 字节，两者均可读回
    @Test
    fun `webdav octet stream read write`() {
        val data = sampleData()

        val binaryBody = SyncDataSerializer.serialize(data, useDataSaver = true)
        assertTrue(isGzip(binaryBody))
        assertMatchesSample(SyncDataSerializer.deserialize(binaryBody))

        val jsonBody = SyncDataSerializer.serialize(data, useDataSaver = false)
        assertMatchesSample(SyncDataSerializer.deserialize(jsonBody))
    }

    // 损坏/空远端必须抛错，交由上层走「远端损坏」路径（不覆盖本地）
    @Test
    fun `corrupt or empty remote fails safely`() {
        assertThrowsAny { SyncDataSerializer.deserialize(ByteArray(0)) }
        // 命中 GZIP 魔数但内容截断/非法，解压必然失败
        assertThrowsAny {
            SyncDataSerializer.deserialize(byteArrayOf(GZIP_MAGIC_0, GZIP_MAGIC_1, 0x08, 0x00, 0x01))
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1

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
    }
}
