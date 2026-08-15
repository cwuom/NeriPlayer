package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadNamingTest {

    @Test
    fun `renderManagedDownloadBaseName uses readable default template`() {
        val result = renderManagedDownloadBaseName(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            source = "netease"
        )

        assertEquals("晴天 - 周杰伦 - 叶惠美 - netease", result)
    }

    @Test
    fun `YouTube source wins over stale channel id while old filename stays discoverable`() {
        val song = SongItem(
            id = 1L,
            name = "爱我别走",
            artist = "张震岳",
            album = "某张专辑",
            albumId = 2L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = buildYouTubeMusicMediaUri("dQw4w9WgXcQ"),
            channelId = "netease"
        )

        assertEquals(
            "爱我别走 - 张震岳 - 某张专辑 - youtubeMusic",
            renderManagedDownloadBaseName(song)
        )
        val candidates = candidateManagedDownloadBaseNames(song)
        assertTrue(candidates.contains("爱我别走 - 张震岳 - 某张专辑 - youtubeMusic"))
        assertTrue(candidates.contains("爱我别走 - 张震岳 [${managedDownloadIdentityHash(song)}]"))
        assertTrue(candidates.contains("youtubeMusic - 张震岳 - 爱我别走"))
        assertTrue(candidates.contains("netease - 张震岳 - 爱我别走"))
    }

    @Test
    fun `renderManagedDownloadBaseName applies custom template`() {
        val result = renderManagedDownloadBaseName(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            template = "%album% - %title%"
        )

        assertEquals("叶惠美 - 晴天", result)
    }

    @Test
    fun `download filename cleans source album prefix without changing remote identity`() {
        val song = SongItem(
            id = 123L,
            name = "茫",
            artist = "李润祺",
            album = "Netease茫",
            albumId = 456L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "123"
        )
        val stableKey = song.stableKey()
        val identity = song.identity()

        assertEquals("茫 - 李润祺 - 茫 - netease", renderManagedDownloadBaseName(song))
        assertEquals("茫 - 茫", renderManagedDownloadBaseName(song, "%album% - %title%"))
        assertEquals("Netease茫", song.album)
        assertEquals(stableKey, song.stableKey())
        assertEquals(identity, song.identity())

        val candidates = candidateManagedDownloadBaseNames(song)
        assertTrue(candidates.contains("茫 - 李润祺 - 茫 - netease"))
        assertTrue(candidates.contains("茫 - 李润祺 - Netease茫 - netease"))
    }

    @Test
    fun `parseManagedDownloadBaseName respects active custom template`() {
        val parsed = parseManagedDownloadBaseName(
            baseName = "叶惠美 - 晴天",
            template = "%album% - %title%"
        )

        assertEquals("晴天", parsed?.title)
        assertEquals("叶惠美", parsed?.album)
    }

    @Test
    fun `parseManagedDownloadBaseName keeps source artist title compatibility`() {
        val parsed = parseManagedDownloadBaseName(
            baseName = "netease - 周杰伦 - 晴天",
            template = "%source% - %artist% - %title%"
        )

        assertEquals("netease", parsed?.source)
        assertEquals("周杰伦", parsed?.artist)
        assertEquals("晴天", parsed?.title)
    }

    @Test
    fun `parseManagedDownloadBaseName cleans historical source album prefix`() {
        val parsed = parseManagedDownloadBaseName("茫 - 李润祺 - Netease茫 - netease")

        assertEquals("茫", parsed?.title)
        assertEquals("李润祺", parsed?.artist)
        assertEquals("茫", parsed?.album)
        assertEquals("netease", parsed?.source)
    }

    @Test
    fun `source only album marker is omitted while historical filename stays discoverable`() {
        val song = SongItem(
            id = 123L,
            name = "歌曲",
            artist = "歌手",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "123"
        )

        assertEquals("歌曲 - 歌手 -  - netease", renderManagedDownloadBaseName(song))
        assertTrue(candidateManagedDownloadBaseNames(song).contains("歌曲 - 歌手 - netease"))
        assertTrue(candidateManagedDownloadBaseNames(song).contains("歌曲 - 歌手 -  - netease"))
        assertTrue(candidateManagedDownloadBaseNames(song).contains("歌曲 - 歌手 - netease"))
        assertTrue(
            candidateManagedDownloadBaseNames(song).contains(
                "歌曲 - 歌手 - Netease - netease"
            )
        )

        val parsed = parseManagedDownloadBaseName("歌曲 - 歌手 -  - netease")
        assertEquals("歌曲", parsed?.title)
        assertEquals("歌手", parsed?.artist)
        assertNull(parsed?.album)
        assertEquals("netease", parsed?.source)
    }

    @Test
    fun `default template preserves a blank artist slot before album metadata`() {
        val baseName = renderManagedDownloadBaseName(
            title = "歌曲",
            artist = "",
            album = "专辑",
            source = "netease"
        )

        assertEquals("歌曲 -  - 专辑 - netease", baseName)
        val parsed = parseManagedDownloadBaseName(baseName)
        assertEquals("歌曲", parsed?.title)
        assertNull(parsed?.artist)
        assertEquals("专辑", parsed?.album)
        assertEquals("netease", parsed?.source)
    }

    @Test
    fun `candidateManagedDownloadBaseNames keeps legacy artist title name after template changes`() {
        val song = SongItem(
            id = 1L,
            name = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            albumId = 2L,
            durationMs = 1000L,
            coverUrl = null
        )

        val candidates = candidateManagedDownloadBaseNames(song)

        assertTrue(candidates.contains("周杰伦 - 晴天"))
        assertTrue(candidates.contains("netease - 周杰伦 - 晴天"))
    }

    @Test
    fun `candidateManagedDownloadBaseNames includes active custom template result`() {
        val song = SongItem(
            id = 1L,
            name = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            albumId = 2L,
            durationMs = 1000L,
            coverUrl = null
        )

        val candidates = candidateManagedDownloadBaseNames(song, activeTemplate = "%album% - %title%")

        assertTrue(candidates.contains("叶惠美 - 晴天"))
    }

    @Test
    fun `renderManagedDownloadBaseName falls back when custom template only yields one character`() {
        val song = SongItem(
            id = 1L,
            name = "A",
            artist = "Artist",
            album = "Album",
            albumId = 2L,
            durationMs = 1_000L,
            coverUrl = null
        )

        val result = renderManagedDownloadBaseName(song, template = "%title%")

        assertEquals("A - Artist - Album - netease", result)
    }

    @Test
    fun `candidateManagedDownloadBaseNames keeps legacy short custom template result for lookup`() {
        val song = SongItem(
            id = 1L,
            name = "A",
            artist = "Artist",
            album = "Album",
            albumId = 2L,
            durationMs = 1_000L,
            coverUrl = null
        )

        val candidates = candidateManagedDownloadBaseNames(song, activeTemplate = "%title%")

        assertTrue(candidates.contains("A"))
        assertTrue(candidates.contains("netease - Artist - A"))
    }

    @Test
    fun `candidateManagedDownloadBaseNames keeps suffixed and raw audio base names`() {
        val candidates = candidateManagedDownloadBaseNames("Artist - Title (1)")

        assertEquals(listOf("Artist - Title (1)", "Artist - Title"), candidates)
    }

    @Test
    fun `renderManagedDownloadBaseName supports source and identity placeholders`() {
        val result = renderManagedDownloadBaseName(
            title = "Song",
            artist = "Artist",
            album = "Album",
            source = "netease",
            songId = "123",
            audioId = "456",
            subAudioId = "789",
            template = "%source% - %artist% - %title% - %id% - %audioId% - %subAudioId%"
        )

        assertEquals("netease - Artist - Song - 123 - 456 - 789", result)
    }

    @Test
    fun `candidateManagedDownloadBaseNames keeps local file base name for scanned download entries`() {
        val song = SongItem(
            id = 1L,
            name = "已经改过的标题",
            artist = "已经改过的歌手",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 1000L,
            coverUrl = null,
            localFileName = "netease - 原歌手 - 原标题 (1).flac",
            localFilePath = "/storage/emulated/0/Music/NeriPlayer/netease - 原歌手 - 原标题 (1).flac",
            mediaUri = "/storage/emulated/0/Music/NeriPlayer/netease - 原歌手 - 原标题 (1).flac"
        )

        val candidates = candidateManagedDownloadBaseNames(song)

        assertTrue(candidates.contains("netease - 原歌手 - 原标题 (1)"))
        assertTrue(candidates.contains("netease - 原歌手 - 原标题"))
    }

    @Test
    fun `default name uses album and source to distinguish readable names`() {
        val first = SongItem(
            id = 1L,
            name = "同名歌曲",
            artist = "同一歌手",
            album = "专辑",
            albumId = 10L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "remote-a"
        )
        val second = first.copy(id = 2L, audioId = "remote-b", album = "另一张专辑")

        assertTrue(renderManagedDownloadBaseName(first) != renderManagedDownloadBaseName(second))
    }

    @Test
    fun `candidate names keep the previous hash default`() {
        val song = SongItem(
            id = 42L,
            name = "歌曲",
            artist = "歌手",
            album = "专辑",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )

        assertTrue(
            candidateManagedDownloadBaseNames(song).contains(
                "歌曲 - 歌手 [${managedDownloadIdentityHash(song)}]"
            )
        )
    }

    @Test
    fun `renderManagedDownloadBaseName bounds UTF8 bytes without splitting code points`() {
        val title = "a".repeat(196) + "\uD83D\uDE00" + "超过长度的尾部"

        val result = renderManagedDownloadBaseName(
            title = title,
            artist = "",
            album = "",
            source = "",
            template = "%title%"
        )

        assertEquals(
            MAX_MANAGED_DOWNLOAD_BASE_NAME_UTF8_BYTES,
            result.toByteArray(Charsets.UTF_8).size
        )
        assertTrue(result.endsWith("\uD83D\uDE00"))
    }

    @Test
    fun `candidate names preserve untruncated historical hash names`() {
        val title = "a".repeat(MAX_MANAGED_DOWNLOAD_BASE_NAME_UTF8_BYTES + 32)
        val song = SongItem(
            id = 42L,
            name = title,
            artist = "歌手",
            album = "专辑",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val historicalName = "$title - 歌手 [${managedDownloadIdentityHash(song)}]"

        assertTrue(candidateManagedDownloadBaseNames(song).contains(historicalName))
    }

    @Test
    fun `hash placeholder renders the stable identity hash`() {
        val song = SongItem(
            id = 42L,
            name = "歌曲",
            artist = "歌手",
            album = "专辑",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )

        assertEquals(
            managedDownloadIdentityHash(song),
            renderManagedDownloadBaseName(song, template = "%hash%")
        )
    }
}
