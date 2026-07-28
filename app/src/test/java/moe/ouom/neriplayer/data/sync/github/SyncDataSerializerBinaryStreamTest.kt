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
 * 省流写路径回退到 Base64 文本 + read-both 的兼容测试
 *
 * 覆盖: 省流写出=老端可读的 base64 文本 (非 raw GZIP) , read-both 仍读 raw GZIP
 * (为将来 write-raw 预留) , base64/JSON 往返, GitHub base64-once, WebDAV 文本读写
 * 损坏远端安全失败
 */
class SyncDataSerializerBinaryStreamTest {

    private val gzipMagic0 = 0x1F.toByte()
    private val gzipMagic1 = 0x8B.toByte()

    // 核心: 省流写路径必须回退为 Base64(GZIP) 文本, 老端才能读; 不得是原始 GZIP 字节
    @Test
    fun `data saver upload is legacy readable base64 text`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        assertFalse("省流产物不应是原始 GZIP 字节（老端会解码失败）", isGzip(body))

        // 模拟老端读取: 把字节当 UTF-8 文本 -> 标准 base64 解码一次 -> 得到 GZIP 字节
        val legacyText = body.toString(Charsets.UTF_8)
        val legacyDecoded = Base64.getDecoder().decode(legacyText)
        assertTrue("老端 base64 解码后应为 GZIP 原始字节", isGzip(legacyDecoded))

        // 新端 read-both 能读回自己写的 base64 文本
        assertMatchesSample(SyncDataSerializer.deserialize(body))
    }

    // read-both: 为将来 write-raw 预留, 原始 GZIP(ProtoBuf) 字节仍必须可读
    @Test
    fun `read both still reads raw gzip for future write raw`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        // 从 base64 文本解出 raw GZIP 字节 (即将来 write-raw 切换后的产物形态)
        val rawGzip = Base64.getDecoder().decode(body.toString(Charsets.UTF_8))
        assertTrue("解出的字节应命中 GZIP 魔数", isGzip(rawGzip))

        // read-both 的 GZIP 魔数分支必须能直接读原始 GZIP 字节
        assertMatchesSample(SyncDataSerializer.deserialize(rawGzip))
    }

    // 省流 base64 文本往返; 再次序列化字节一致, 证明序列化层无额外归一化/丢字段
    @Test
    fun `base64 text round trip is stable`() {
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

    // GitHub contents API 对上传字节做"一次"base64; 下载解码一次即得回上传字节
    @Test
    fun `github contents api base64 once round trip`() {
        val data = sampleData()
        val body = SyncDataSerializer.serialize(data, useDataSaver = true)

        // 模拟 GitHubApiClient.updateFileContent: 对上传字节 base64 一次
        val uploaded = Base64.getEncoder().encodeToString(body)
        // 模拟 GitHubApiClient.getFileContent: content 字段 base64 解码一次
        val downloaded = Base64.getMimeDecoder().decode(uploaded)

        assertArrayEquals(body, downloaded)
        assertMatchesSample(SyncDataSerializer.deserialize(downloaded))
    }

    // WebDAV 文本通道: 省流传 base64 文本, 非省流传 JSON, 两者均可读回
    @Test
    fun `webdav text channel read write`() {
        val data = sampleData()

        val binaryBody = SyncDataSerializer.serialize(data, useDataSaver = true)
        assertFalse(isGzip(binaryBody))
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
