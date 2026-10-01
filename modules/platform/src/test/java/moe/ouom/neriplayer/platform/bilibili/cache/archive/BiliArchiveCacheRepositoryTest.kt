package moe.ouom.neriplayer.platform.bilibili.cache.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BiliArchiveCacheRepositoryTest {
    @Test
    fun `uses a stable collection cache filename`() {
        assertEquals(
            "collection_42.json",
            biliArchiveCacheFileName(mediaId = 42L, kind = "COLLECTION")
        )
    }

    @Test
    fun `separates collection and series cache entries with the same id`() {
        val collection = biliArchiveCacheFileName(mediaId = 42L, kind = "COLLECTION")
        val series = biliArchiveCacheFileName(mediaId = 42L, kind = "SERIES")

        assertNotEquals(collection, series)
    }
}
