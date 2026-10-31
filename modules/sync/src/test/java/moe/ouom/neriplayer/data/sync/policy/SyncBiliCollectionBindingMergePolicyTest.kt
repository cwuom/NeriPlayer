package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_ALBUM
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_SONG
import moe.ouom.neriplayer.data.model.sync.SyncBiliCollectionBinding
import moe.ouom.neriplayer.data.model.sync.SyncBiliCollectionBindingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBiliCollectionBindingMergePolicyTest {
    private val firstTrack = SyncBiliCollectionBindingTrack(
        songId = 1L,
        songName = "骨中月",
        cid = 10L,
        page = 1,
        part = "骨中月",
        durationSec = 236
    )
    private val secondTrack = firstTrack.copy(songId = 2L, songName = "与亡友书", cid = 20L, page = 2)

    private val active = SyncBiliCollectionBinding(
        targetKey = "netease:song:1",
        targetId = 1L,
        targetName = "骨中月",
        targetSubtitle = "镜予歌",
        bvid = "BV1xx411c7mD",
        avid = 42L,
        videoTitle = "骨中月合集",
        tracks = listOf(firstTrack),
        updatedAt = 10L,
        targetType = BILI_BINDING_TARGET_SONG
    )

    @Test
    fun `normalization trims text, defaults target type and orders tracks`() {
        val untidy = active.copy(
            targetKey = "  netease:song:1  ",
            targetName = "  骨中月  ",
            targetSubtitle = " 镜予歌 ",
            bvid = "  BV1xx411c7mD ",
            videoTitle = " 骨中月合集 ",
            targetType = "   ",
            tracks = listOf(
                secondTrack.copy(page = 2, cid = 20L),
                firstTrack.copy(page = 1, cid = 10L),
                firstTrack.copy(songId = 3L, cid = 0L),
                firstTrack.copy(songId = 4L, cid = 20L, page = 9)
            ),
            updatedAt = -5L
        )

        val normalized = SyncBiliCollectionMergePolicyAccess.sanitizeOne(untidy)

        assertEquals("netease:song:1", normalized.targetKey)
        assertEquals(BILI_BINDING_TARGET_ALBUM, normalized.targetType)
        assertEquals("骨中月", normalized.targetName)
        assertEquals("镜予歌", normalized.targetSubtitle)
        assertEquals("BV1xx411c7mD", normalized.bvid)
        assertEquals("骨中月合集", normalized.videoTitle)
        assertEquals(0L, normalized.updatedAt)
        assertEquals(listOf(10L, 20L), normalized.tracks.map { it.cid })
    }

    @Test
    fun `sanitize drops unusable active bindings but keeps deletion tombstones`() {
        val tombstones = SyncBiliCollectionBindingMergePolicy.sanitize(
            listOf(
                active.copy(targetKey = "   "),
                active.copy(bvid = "  "),
                active.copy(tracks = emptyList()),
                active.copy(tracks = listOf(firstTrack.copy(cid = 0L))),
                active.copy(isDeleted = true, updatedAt = -1L)
            )
        )

        assertEquals(1, tombstones.size)
        val tombstone = tombstones.single()
        assertTrue(tombstone.isDeleted)
        assertEquals("netease:song:1", tombstone.targetKey)
        assertEquals("", tombstone.bvid)
        assertTrue(tombstone.tracks.isEmpty())
        assertEquals(0L, tombstone.updatedAt)
    }

    @Test
    fun `newer timestamps win and deletion wins timestamp ties`() {
        val deletion = active.copy(isDeleted = true, bvid = "", tracks = emptyList(), updatedAt = 10L)

        assertEquals(
            listOf(active.copy(updatedAt = 20L)),
            SyncBiliCollectionBindingMergePolicy.merge(listOf(active.copy(updatedAt = 20L)), listOf(deletion))
        )
        assertEquals(
            listOf(deletion.copy(updatedAt = 20L, bvid = "")),
            SyncBiliCollectionBindingMergePolicy.merge(listOf(active), listOf(deletion.copy(updatedAt = 20L)))
        )
        assertEquals(
            listOf(deletion),
            SyncBiliCollectionBindingMergePolicy.merge(listOf(active), listOf(deletion))
        )
        assertEquals(
            listOf(deletion),
            SyncBiliCollectionBindingMergePolicy.merge(listOf(deletion), listOf(active))
        )
        assertEquals(
            listOf(active.copy(tracks = listOf(firstTrack))),
            SyncBiliCollectionBindingMergePolicy.merge(listOf(active), listOf(active))
        )
    }

    @Test
    fun `merge unions both sides and sanitize keeps the newest per target key`() {
        val other = active.copy(targetKey = "netease:album:7", targetId = 7L, updatedAt = 30L)
        val merged = SyncBiliCollectionBindingMergePolicy.merge(listOf(active), listOf(other))

        assertEquals(listOf("netease:album:7", "netease:song:1"), merged.map { it.targetKey })
        assertTrue(
            SyncBiliCollectionBindingMergePolicy.same(
                listOf(active, other),
                listOf(other, active.copy(targetName = "  骨中月 "))
            )
        )
        assertFalse(SyncBiliCollectionBindingMergePolicy.same(listOf(active), listOf(other)))
    }

    @Test
    fun `equal timestamps break ties by content so both devices agree`() {
        val left = active.copy(bvid = "BV1aa411c7mA", updatedAt = 50L)
        val right = active.copy(
            bvid = "BV1bb411c7mB",
            videoTitle = "另一份合集",
            tracks = listOf(secondTrack),
            updatedAt = 50L
        )

        val mergedLeftFirst = SyncBiliCollectionBindingMergePolicy.merge(listOf(left), listOf(right)).single()
        val mergedRightFirst = SyncBiliCollectionBindingMergePolicy.merge(listOf(right), listOf(left)).single()

        assertEquals(mergedLeftFirst, mergedRightFirst)
        assertEquals("BV1bb411c7mB", mergedLeftFirst.bvid)
    }

    @Test
    fun `sanitize caps the number of kept bindings`() {
        val many = (1..600).map { index ->
            active.copy(targetKey = "netease:song:$index", targetId = index.toLong(), updatedAt = index.toLong())
        }

        val kept = SyncBiliCollectionBindingMergePolicy.sanitize(many)

        assertEquals(512, kept.size)
        assertEquals("netease:song:600", kept.first().targetKey)
        assertEquals("netease:song:89", kept.last().targetKey)
    }
}

/** 单条规范化在 object 内部是私有的，测试通过单元素 sanitize 达到同样的入口 */
private object SyncBiliCollectionMergePolicyAccess {
    fun sanitizeOne(binding: SyncBiliCollectionBinding): SyncBiliCollectionBinding =
        SyncBiliCollectionBindingMergePolicy.sanitize(listOf(binding)).single()
}
