package moe.ouom.neriplayer.data.playlist.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistUsageRepositoryTest {

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

    private fun usageEntry(
        id: Long,
        subtype: String?,
        lastOpened: Long = 0L,
        openCount: Int = 1,
        name: String = "Bili"
    ): UsageEntry {
        return UsageEntry(
            id = id,
            name = name,
            picUrl = null,
            trackCount = 0,
            source = "bili",
            lastOpened = lastOpened,
            openCount = openCount,
            subtype = subtype
        )
    }
}
