package moe.ouom.neriplayer.data.platform.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliVideoSkipRepositoryPolicyTest {
    @Test
    fun `normalization merges touching intervals and clamps to the video duration`() {
        val normalized = normalizeBiliVideoSkipIntervals(
            intervals = listOf(
                BiliVideoSkipInterval(startMs = -1_000L, endMs = 3_000L),
                BiliVideoSkipInterval(startMs = 3_000L, endMs = 9_000L),
                BiliVideoSkipInterval(startMs = 20_000L, endMs = 30_000L),
                BiliVideoSkipInterval(startMs = 25_000L, endMs = 26_000L),
                BiliVideoSkipInterval(startMs = 14_000L, endMs = 14_000L)
            ),
            durationMs = 25_000L
        )

        assertEquals(
            listOf(
                BiliVideoSkipInterval(startMs = 0L, endMs = 9_000L),
                BiliVideoSkipInterval(startMs = 20_000L, endMs = 25_000L)
            ),
            normalized
        )
    }

    @Test
    fun `equal timestamp edits merge while only a newer tombstone deletes a rule`() {
        val target = BiliVideoSkipTarget(bvid = "BV1test", cid = 42L)
        val original = BiliVideoSkipRule(
            target = target,
            intervals = listOf(BiliVideoSkipInterval(10_000L, 20_000L)),
            modifiedAt = 100L
        )
        val concurrentEdit = original.copy(
            intervals = listOf(BiliVideoSkipInterval(30_000L, 40_000L))
        )
        val equalTimestampDelete = original.copy(isDeleted = true, intervals = emptyList())

        val merged = normalizeBiliVideoSkipRules(
            listOf(original, concurrentEdit, equalTimestampDelete)
        ).single()

        assertFalse(merged.isDeleted)
        assertEquals(
            listOf(
                BiliVideoSkipInterval(10_000L, 20_000L),
                BiliVideoSkipInterval(30_000L, 40_000L)
            ),
            merged.intervals
        )

        val deleted = normalizeBiliVideoSkipRules(
            listOf(original, equalTimestampDelete.copy(modifiedAt = 101L))
        ).single()

        assertTrue(deleted.isDeleted)
        assertEquals(emptyList<BiliVideoSkipInterval>(), deleted.intervals)
    }

    @Test
    fun `input drafts keep the newest text for each Bili video part`() {
        val target = BiliVideoSkipTarget(bvid = "BV1draft", cid = 7L)
        val drafts = normalizeBiliVideoSkipDrafts(
            listOf(
                BiliVideoSkipDraft(
                    target = target,
                    startText = "10",
                    endText = "",
                    modifiedAt = 100L
                ),
                BiliVideoSkipDraft(
                    target = target,
                    startText = " 15 ",
                    endText = "00:20",
                    modifiedAt = 101L
                ),
                BiliVideoSkipDraft(
                    target = BiliVideoSkipTarget(bvid = "BV1empty", cid = 8L),
                    startText = " ",
                    endText = ""
                )
            )
        )

        assertEquals(
            listOf(
                BiliVideoSkipDraft(
                    target = target,
                    startText = "15",
                    endText = "00:20",
                    modifiedAt = 101L
                )
            ),
            drafts
        )
    }
}
