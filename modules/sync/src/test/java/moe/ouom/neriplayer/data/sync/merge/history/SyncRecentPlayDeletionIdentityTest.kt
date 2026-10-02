package moe.ouom.neriplayer.data.sync.merge.history

import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.identity
import org.junit.Assert.*
import org.junit.Test

class SyncRecentPlayDeletionIdentityTest {
    @Test
    fun `raw source labels delete normalized history and retain the newest deletion evidence`() {
        val song = SyncSong(id = 7, album = "Netease", channelId = "netease", audioId = "7")
        val play = SyncRecentPlay(7, song, 20)
        val deletions = listOf(
            SyncRecentPlayDeletion(7, "Netease", deletedAt = 20, deviceId = "a"),
            SyncRecentPlayDeletion(7, "album", deletedAt = 30, deviceId = "z"),
            SyncRecentPlayDeletion(7, "netease", deletedAt = 25, deviceId = "newer")
        )
        val merged = SyncRecentPlayMerger.mergeRecentPlayDeletions(deletions, emptyList())

        assertEquals(listOf(SyncRecentPlayDeletion(7, "netease", deletedAt = 30, deviceId = "z")), merged)
        assertTrue(SyncRecentPlayMerger.mergeRecentPlays(emptyList(), listOf(play), deletions).isEmpty())
        assertEquals(merged, SyncRecentPlayMerger.pruneRecentPlayDeletions(deletions, listOf(play)))
        assertTrue(SyncRecentPlayMerger.pruneRecentPlayDeletions(merged, listOf(play.copy(playedAt = 31))).isEmpty())
        assertEquals(merged, SyncRecentPlayMerger.mergeRecentPlayDeletions(merged, deletions))
    }

    @Test
    fun `bilibili deletion keeps canonical numeric identity and supports the legacy raw identity`() {
        val song = SyncSong(id = 7, album = "Bilibili|8")
        val identity = song.identity()
        val raw = SyncRecentPlayDeletion(7, song.album, deletedAt = 20)
        val canonical = SyncRecentPlayDeletion(identity.id, identity.album, identity.mediaUri, 30)
        val play = SyncRecentPlay(7, song, 20)

        assertEquals(canonical, SyncRecentPlayMerger.normalizeDeletion(canonical))
        assertEquals(raw, SyncRecentPlayMerger.normalizeDeletion(raw))
        assertTrue(SyncRecentPlayMerger.mergeRecentPlays(emptyList(), listOf(play), listOf(raw)).isEmpty())
        assertTrue(SyncRecentPlayMerger.mergeRecentPlays(emptyList(), listOf(play), listOf(canonical)).isEmpty())
        assertTrue(SyncRecentPlayMerger.mergeRecentPlays(emptyList(), listOf(play.copy(playedAt = 25)), listOf(raw, canonical)).isEmpty())
        assertTrue(SyncRecentPlayMerger.pruneRecentPlayDeletions(listOf(raw, canonical), listOf(play.copy(playedAt = 31))).isEmpty())
    }

    @Test
    fun `youtube URI normalization is idempotent and preserves removal time and device`() {
        val song = SyncSong(id = 7, album = "YouTube", mediaUri = "https://music.youtube.com/watch?v=abcdefghijk")
        val raw = SyncRecentPlayDeletion(7, song.album, song.mediaUri, 20, "device")
        val normalized = SyncRecentPlayMerger.normalizeDeletion(raw)

        assertEquals(song.identity(), normalized.identity())
        assertEquals(20L, normalized.deletedAt)
        assertEquals("device", normalized.deviceId)
        assertEquals(normalized, SyncRecentPlayMerger.normalizeDeletion(normalized))
        assertTrue(SyncRecentPlayMerger.mergeRecentPlays(emptyList(), listOf(SyncRecentPlay(7, song, 10)), listOf(raw)).isEmpty())
    }
}
