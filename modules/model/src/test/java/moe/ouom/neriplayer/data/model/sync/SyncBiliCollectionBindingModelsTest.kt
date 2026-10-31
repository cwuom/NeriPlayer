package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_SONG
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBiliCollectionBindingModelsTest {
    private val track = BiliCollectionBindingTrack(
        songId = 1L,
        songName = "骨中月",
        cid = 10L,
        page = 1,
        part = "骨中月",
        durationSec = 236
    )

    private val active = BiliCollectionBinding(
        targetKey = "netease:song:1",
        targetType = BILI_BINDING_TARGET_SONG,
        targetId = 1L,
        targetName = "骨中月",
        targetSubtitle = "镜予歌",
        bvid = "BV1xx411c7mD",
        avid = 42L,
        videoTitle = "骨中月合集",
        tracks = listOf(track),
        updatedAt = 10L
    )

    @Test
    fun `active binding round-trips through the sync model`() {
        val restored = active.toSyncBiliCollectionBinding().toBiliCollectionBindingOrNull()

        assertEquals(active, restored)
        assertEquals(BILI_BINDING_TARGET_SONG, restored?.targetType)
    }

    @Test
    fun `tombstone survives the trip so the other device can delete too`() {
        val tombstone = active.copy(
            isDeleted = true,
            bvid = "",
            avid = 0L,
            videoTitle = "",
            tracks = emptyList(),
            updatedAt = 20L
        )

        val sync = tombstone.toSyncBiliCollectionBinding()

        assertTrue(sync.isDeleted)
        assertEquals("", sync.bvid)
        assertTrue(sync.tracks.isEmpty())
        val restored = sync.toBiliCollectionBindingOrNull()
        assertTrue(restored?.isDeleted == true)
        assertEquals("netease:song:1", restored?.targetKey)
        assertEquals(20L, restored?.updatedAt)
    }

    @Test
    fun `unusable payloads stay unusable`() {
        assertNull(SyncBiliCollectionBinding(targetKey = "  ", isDeleted = true).toBiliCollectionBindingOrNull())
        assertNull(SyncBiliCollectionBinding(targetKey = "netease:song:1", bvid = "").toBiliCollectionBindingOrNull())
    }
}
