package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadNamingTest {

    @Test
    fun `renderManagedDownloadBaseName uses default template`() {
        val result = renderManagedDownloadBaseName(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            source = "netease"
        )

        assertEquals("netease - 周杰伦 - 晴天", result)
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
}
