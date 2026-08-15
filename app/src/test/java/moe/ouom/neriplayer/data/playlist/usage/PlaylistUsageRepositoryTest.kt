package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistUsageStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
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
    fun `normalization retains every distinct playlist statistic for sync`() {
        val entries = (1L..101L).map { id ->
            UsageEntry(
                id = id,
                name = "Playlist $id",
                picUrl = null,
                trackCount = 1,
                source = "netease",
                lastOpened = id,
                openCount = 1
            )
        }

        val normalized = normalizeUsageEntries(entries)

        assertEquals(101, normalized.size)
        assertEquals(
            entries.map(UsageEntry::usageKey).toSet(),
            normalized.map(UsageEntry::usageKey).toSet()
        )
    }

    @Test
    fun `normalization keeps local display cover when counter shards exist`() {
        val repo = PlaylistUsageRepository(mockContext())
        val localCoverUrl = "file:///covers/local.jpg"

        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = localCoverUrl,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )

        assertEquals(localCoverUrl, repo.frequentPlaylistsFlow.value.single().picUrl)
    }

    @Test
    fun `legacy usage JSON without counter shards loads without crashing`() {
        tempFolder.newFile("playlist_usage.json").writeText(
            """
            [{
              "id": 42,
              "name": "旧歌单",
              "picUrl": null,
              "trackCount": 3,
              "source": "netease",
              "lastOpened": 100,
              "openCount": 2
            }]
            """.trimIndent()
        )

        val repo = PlaylistUsageRepository(mockContext())

        val entry = repo.frequentPlaylistsFlow.value.single()
        assertEquals(2, entry.openCount)
        assertTrue(entry.counterShards.isEmpty())
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
    fun `manual removal stays hidden when stale usage stats are merged`() {
        val repo = PlaylistUsageRepository(mockContext())

        repo.recordOpen(
            id = 42L,
            name = "歌单",
            picUrl = null,
            trackCount = 3,
            source = "netease",
            now = 100L
        )
        repo.removeEntry(id = 42L, source = "netease")
        repo.applyMergedStats(
            listOf(
                SyncPlaylistUsageStat(
                    playlistKey = "netease:42",
                    source = "netease",
                    id = 42L,
                    name = "歌单",
                    trackCount = 3,
                    lastOpenedAt = 100L,
                    firstOpenedAt = 100L,
                    openCount = 1
                )
            )
        )

        assertTrue(repo.frequentPlaylistsFlow.value.isEmpty())
    }

    @Test
    fun `opening a manually removed playlist clears its hidden state`() {
        val repo = PlaylistUsageRepository(mockContext())

        repo.recordOpen(
            id = 42L,
            name = "歌单",
            picUrl = null,
            trackCount = 3,
            source = "netease",
            now = 100L
        )
        repo.removeEntry(id = 42L, source = "netease")
        repo.recordOpen(
            id = 42L,
            name = "歌单",
            picUrl = null,
            trackCount = 3,
            source = "netease",
            now = 300L
        )

        assertEquals(1, repo.frequentPlaylistsFlow.value.size)
        assertEquals(300L, repo.frequentPlaylistsFlow.value.single().lastOpened)
    }

    @Test
    fun `bili usage keeps uploader subtitle when reopening and refreshing`() {
        val repo = PlaylistUsageRepository(mockContext())

        repo.recordOpen(
            id = 8801L,
            name = "合集",
            picUrl = "cover",
            trackCount = 3,
            source = "bili",
            subtype = "COLLECTION",
            subtitle = "UP 主",
            now = 100L
        )
        repo.recordOpen(
            id = 8801L,
            name = "合集",
            picUrl = "cover",
            trackCount = 3,
            source = "bili",
            subtype = "COLLECTION",
            now = 200L
        )
        repo.updateInfo(
            id = 8801L,
            name = "合集",
            picUrl = "cover",
            trackCount = 4,
            source = "bili",
            subtype = "COLLECTION",
            now = 300L
        )

        val entry = repo.frequentPlaylistsFlow.value.single()
        assertEquals("UP 主", entry.subtitle)
        assertEquals(4, entry.trackCount)
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

    @Test
    fun `sync local entries keeps favorites cover when newest song is local`() {
        val context = mockLocalizedContext()
        val repo = PlaylistUsageRepository(context)
        val localCoverUrl = "file:///covers/favorite-local.jpg"
        val favorites = LocalPlaylist(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "我喜欢的音乐",
            songs = mutableListOf(localSong(coverUrl = localCoverUrl))
        )

        repo.recordOpen(
            id = FavoritesPlaylist.SYSTEM_ID,
            name = "我喜欢的音乐",
            picUrl = null,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )
        repo.syncLocalEntries(playlists = listOf(favorites))

        assertEquals(localCoverUrl, repo.frequentPlaylistsFlow.value.single().picUrl)
    }

    @Test
    fun `sync local entries uses downloaded song cover for local files card`() {
        val context = mockLocalizedContext()
        val repo = PlaylistUsageRepository(context)
        val downloadedCoverUrl = "file:///covers/downloaded.jpg"
        val localFiles = LocalPlaylist(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            songs = mutableListOf(localSong(coverUrl = null))
        )
        val downloadedCoverCandidates = listOf(localSong(coverUrl = downloadedCoverUrl))

        assertEquals(
            downloadedCoverUrl,
            localFiles.displayCoverUrl(
                context = context,
                additionalCoverCandidates = downloadedCoverCandidates
            )
        )

        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = null,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )
        repo.syncLocalEntries(
            playlists = listOf(localFiles),
            localFilesCoverCandidates = downloadedCoverCandidates
        )

        val entry = repo.frequentPlaylistsFlow.value.single()
        assertEquals(LocalFilesPlaylist.currentName(context), entry.name)
        assertEquals(downloadedCoverUrl, entry.picUrl)
        assertEquals(1, entry.trackCount)
    }

    @Test
    fun `sync local entries keeps last known cover when fallback is temporarily blank`() {
        val context = mockLocalizedContext()
        val repo = PlaylistUsageRepository(context)
        val knownCoverUrl = "file:///covers/known-local.jpg"
        val localFiles = LocalPlaylist(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            songs = mutableListOf(localSong(coverUrl = null))
        )

        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = knownCoverUrl,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )
        repo.syncLocalEntries(playlists = listOf(localFiles))

        assertEquals(knownCoverUrl, repo.frequentPlaylistsFlow.value.single().picUrl)
    }

    @Test
    fun `opening local playlist without cover does not clear known cover`() {
        val repo = PlaylistUsageRepository(mockContext())
        val knownCoverUrl = "file:///covers/known-local.jpg"

        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = knownCoverUrl,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )
        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = null,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 200L
        )

        assertEquals(knownCoverUrl, repo.frequentPlaylistsFlow.value.single().picUrl)
    }

    @Test
    fun `merged usage stats keep local cover when sync omits local file cover`() {
        val repo = PlaylistUsageRepository(mockContext())
        val knownCoverUrl = "file:///covers/known-local.jpg"

        repo.recordOpen(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "本地文件",
            picUrl = knownCoverUrl,
            trackCount = 1,
            source = PlaylistUsageRepository.SOURCE_LOCAL,
            now = 100L
        )
        repo.applyMergedStats(
            listOf(
                SyncPlaylistUsageStat(
                    playlistKey = "local:${LocalFilesPlaylist.SYSTEM_ID}",
                    source = PlaylistUsageRepository.SOURCE_LOCAL,
                    id = LocalFilesPlaylist.SYSTEM_ID,
                    name = "本地文件",
                    coverUrl = null,
                    trackCount = 1,
                    lastOpenedAt = 200L,
                    firstOpenedAt = 100L,
                    openCount = 2
                )
            )
        )

        assertEquals(knownCoverUrl, repo.frequentPlaylistsFlow.value.single().picUrl)
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
            `when`(filesDir).thenReturn(tempFolder.root)
            `when`(applicationContext).thenReturn(this)
            `when`(createConfigurationContext(any(Configuration::class.java))).thenReturn(this)
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
