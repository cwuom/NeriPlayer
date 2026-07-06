package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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
}
