package moe.ouom.neriplayer.data.platform.bili

import moe.ouom.neriplayer.data.local.database.store.BiliVideoSkipRoomSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliVideoSkipRoomRecoveryTest {
    @Test
    fun unchangedLegacyRulesDoNotRemoveRoomOnlyEntries() {
        val target = BiliVideoSkipTarget("BVlegacy", 1L)
        val roomOnly = BiliVideoSkipTarget("BVroom", 2L)
        val baseline = BiliVideoSkipRoomSnapshot(
            rules = listOf(BiliVideoSkipRule(target, modifiedAt = 1L)),
            drafts = listOf(BiliVideoSkipDraft(target, startText = "1", modifiedAt = 1L))
        )
        val room = BiliVideoSkipRoomSnapshot(
            rules = listOf(
                BiliVideoSkipRule(target, modifiedAt = 2L),
                BiliVideoSkipRule(roomOnly, modifiedAt = 2L)
            ),
            drafts = listOf(
                BiliVideoSkipDraft(target, startText = "2", modifiedAt = 2L),
                BiliVideoSkipDraft(roomOnly, endText = "3", modifiedAt = 2L)
            )
        )

        assertEquals(
            room,
            mergeBiliVideoSkipRoomRecovery(
                roomSnapshot = room,
                recoveryBaseline = baseline,
                currentSnapshot = baseline
            )
        )
    }
}
