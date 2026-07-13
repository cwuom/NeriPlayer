package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Locale

class PlaylistUsageRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `usage key keeps bili subtype to avoid compose key collisions`() {
        val created = usageEntry(id = 6998514L, subtype = "CREATED_FAVORITE")
        val collection = usageEntry(id = 6998514L, subtype = "COLLECTION")

        assertEquals("bili:6998514:CREATED_FAVORITE", created.usageKey())
        assertEquals("bili:6998514:COLLECTION", collection.usageKey())
    }

    @Test
    fun `normalize usage entries keeps different subtypes and merges exact duplicates`() {
        val olderCreated = usageEntry(
            id = 6998514L,
            subtype = "CREATED_FAVORITE",
            lastOpened = 100L,
            openCount = 2,
            name = "旧收藏夹"
        )
        val newerCreated = olderCreated.copy(
            name = "新收藏夹",
            lastOpened = 300L,
            openCount = 1
        )
        val collection = usageEntry(
            id = 6998514L,
            subtype = "COLLECTION",
            lastOpened = 200L,
            openCount = 4,
            name = "合集"
        )

        val normalized = normalizeUsageEntries(listOf(olderCreated, collection, newerCreated))

        assertEquals(2, normalized.size)
        assertEquals("bili:6998514:CREATED_FAVORITE", normalized[0].usageKey())
        assertEquals("新收藏夹", normalized[0].name)
        assertEquals(3, normalized[0].openCount)
        assertEquals("bili:6998514:COLLECTION", normalized[1].usageKey())
    }

    @Test
    fun `blank subtype keeps legacy source id key`() {
        assertEquals("bili:6998514", usageEntry(id = 6998514L, subtype = null).usageKey())
        assertEquals("bili:6998514", usageEntry(id = 6998514L, subtype = " ").usageKey())
    }

    @Test
    fun `normalize usage entries removes empty playlists`() {
        val empty = usageEntry(id = 1L, subtype = "CREATED_FAVORITE", trackCount = 0)
        val playable = usageEntry(id = 2L, subtype = "CREATED_FAVORITE", trackCount = 3)

        val normalized = normalizeUsageEntries(listOf(empty, playable))

        assertEquals(1, normalized.size)
        assertEquals("bili:2:CREATED_FAVORITE", normalized.single().usageKey())
    }

    @Test
    fun `record open removes stale empty playlist instead of keeping it`() {
        val repo = PlaylistUsageRepository(mockContext())

        repo.recordOpen(
            id = 42L,
            name = "有效歌单",
            picUrl = null,
            trackCount = 3,
            source = "netease",
            now = 100L
        )
        repo.recordOpen(
            id = 42L,
            name = "空歌单",
            picUrl = null,
            trackCount = 0,
            source = "netease",
            now = 200L
        )

        assertTrue(repo.frequentPlaylistsFlow.value.isEmpty())
    }

    @Test
    fun `update info promotes playlist only after detail has tracks`() {
        val repo = PlaylistUsageRepository(mockContext())

        repo.updateInfo(
            id = 7L,
            name = "加载中的歌单",
            picUrl = null,
            trackCount = 0,
            source = "youtubeMusic",
            now = 100L
        )
        assertTrue(repo.frequentPlaylistsFlow.value.isEmpty())

        repo.updateInfo(
            id = 7L,
            name = "已加载歌单",
            picUrl = "cover",
            trackCount = 8,
            source = "youtubeMusic",
            browseId = "VL7",
            playlistId = "7",
            now = 200L
        )

        val entry = repo.frequentPlaylistsFlow.value.single()
        assertEquals("已加载歌单", entry.name)
        assertEquals(8, entry.trackCount)
        assertEquals(200L, entry.lastOpened)
    }

    @Test
    fun `local playlist usage lookup keeps legacy local files cover`() {
        val coverUrl = "file:///covers/local.jpg"
        val legacyLocalFiles = LocalPlaylist(
            id = -12L,
            name = "本地文件",
            songs = mutableListOf(localSong(coverUrl))
        )

        val lookup = buildLocalPlaylistUsageLookup(
            playlists = listOf(legacyLocalFiles),
            context = mockLocalizedContext()
        )

        val localFiles = lookup.getValue(LocalFilesPlaylist.SYSTEM_ID)
        assertEquals(1, localFiles.songs.size)
        assertEquals(coverUrl, localFiles.displayCoverUrl())
    }

    @Test
    fun `local files usage cover resolves with local metadata fallback`() {
        val context = mockLocalizedContext()
        val embeddedCoverUrl = "file://${tempFolder.root.resolve("local-cover.jpg").absolutePath}"
        val localFiles = LocalPlaylist(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            songs = mutableListOf(
                localSong(coverUrl = null).copy(customCoverUrl = embeddedCoverUrl)
            )
        )

        val refreshedPicUrl = localFiles.displayCoverUrl(
            context = context,
            resolveLocalMetadataFallback = true
        )

        assertEquals(embeddedCoverUrl, refreshedPicUrl)
    }

    private fun usageEntry(
        id: Long,
        subtype: String?,
        lastOpened: Long = 0L,
        openCount: Int = 1,
        name: String = "Bili",
        trackCount: Int = 1
    ): UsageEntry {
        return UsageEntry(
            id = id,
            name = name,
            picUrl = null,
            trackCount = trackCount,
            source = "bili",
            lastOpened = lastOpened,
            openCount = openCount,
            subtype = subtype
        )
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        return context
    }

    private fun mockLocalizedContext(): Context {
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        val prefs = mock(SharedPreferences::class.java)
        return mock(Context::class.java).apply {
            `when`(resources.configuration).thenReturn(configuration)
            `when`(configuration.locales).thenReturn(locales)
            `when`(locales[0]).thenReturn(Locale.CHINA)
            `when`(getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(prefs)
            `when`(prefs.getString("selected_language", "")).thenReturn("")
            `when`(this.resources).thenReturn(resources)
            `when`(getString(R.string.local_files)).thenReturn("本地文件")
            `when`(getString(R.string.favorite_my_music)).thenReturn("我喜欢的音乐")
        }
    }

    private fun localSong(coverUrl: String?): SongItem {
        return SongItem(
            id = 1L,
            name = "Local Song",
            artist = "Local Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = coverUrl,
            mediaUri = "/music/local.mp3",
            localFileName = "local.mp3",
            localFilePath = "/music/local.mp3",
            channelId = "local",
            audioId = "1"
        )
    }
}
