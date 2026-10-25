package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCacheRetentionPlanTest {
    @Test
    fun `zero retention protects current audio without retaining its cover`() {
        val current = song(12, "current")
        val plan = StorageCacheRetentionPlan(emptyList(), current, setOf("current-key"), 7L, false)

        assertEquals(listOf(current), plan.retainedSongs)
        assertEquals(current.stableKey(), plan.currentSongKey)
        assertEquals(setOf("current-key"), plan.protectedKeys)
        assertTrue(plan.retainedCoverUrls.isEmpty())
    }

    @Test
    fun `positive retention shares distinct recent and current songs across media caches`() {
        val recent = song(12, "recent").copy(customCoverUrl = "custom", originalCoverUrl = "original")
        val current = song(13, "current")
        val plan = StorageCacheRetentionPlan(listOf(recent, recent), current, emptySet(), 7L, true)

        assertEquals(listOf(recent, current), plan.retainedSongs)
        assertEquals(setOf("recent", "custom", "original", "current"), plan.retainedCoverUrls)
    }

    private fun song(id: Long, cover: String) = SongItem(id, "song", "artist", "album", 0, 1000, cover)
}
