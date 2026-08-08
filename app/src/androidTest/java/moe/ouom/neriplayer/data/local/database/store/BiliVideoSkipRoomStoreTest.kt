package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipRule
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliVideoSkipRoomStoreTest {
    @Test
    fun rulesAndDraftsRoundTripWithoutJsonPayloads() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val target = BiliVideoSkipTarget("BV1TEST", 12L)
            val rule = BiliVideoSkipRule(
                target = target,
                intervals = listOf(
                    BiliVideoSkipInterval(startMs = 1_000L, endMs = 2_000L),
                    BiliVideoSkipInterval(startMs = 3_000L, endMs = 4_000L)
                ),
                modifiedAt = 20L
            )
            val draft = BiliVideoSkipDraft(
                target = target,
                startText = "00:01",
                endText = "00:02",
                modifiedAt = 21L
            )
            val store = BiliVideoSkipRoomStore(database)

            store.replaceAll(listOf(rule), listOf(draft), now = 30L)
            store.importLegacyAndPromote(emptyList(), emptyList(), now = 40L)

            assertEquals(
                BiliVideoSkipRoomSnapshot(listOf(rule), listOf(draft)),
                store.readIfRoomPrimary()
            )
            assertEquals(1, database.biliVideoSkipDao().getRules().size)
            assertEquals(2, database.biliVideoSkipDao().getIntervals().size)
            assertEquals(1, database.biliVideoSkipDao().getDrafts().size)
        } finally {
            database.close()
        }
    }
}
