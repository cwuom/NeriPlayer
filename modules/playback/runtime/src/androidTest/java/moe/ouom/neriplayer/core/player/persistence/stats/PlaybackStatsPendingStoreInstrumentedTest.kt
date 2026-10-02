package moe.ouom.neriplayer.core.player.persistence.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.UUID
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsPendingStoreInstrumentedTest {
    @Test
    fun fullFrameAndByteBudgetsSurviveReopenAndAcknowledgementMakesRoom() {
        for (limitByBytes in listOf(false, true)) {
            withOwnedJournalDirectory { directory ->
                val events = (1..3).map { snapshot("event-$it", 1_700_000_000_000L + it * 15_000L, 42L) }
                val byteBudget = if (limitByBytes) events.take(2).sumOf {
                    PlaybackStatsJournalCodec.frame(PlaybackStatsJournalCodec.payload(it)).size.toLong()
                } else Long.MAX_VALUE
                val frameBudget = if (limitByBytes) 10 else 2
                FilePlaybackStatsPendingStore(directory, maxJournalBytes = byteBudget, maxJournalFrames = frameBudget).use { store ->
                    store.append(events[0])
                    store.append(events[1])
                    assertThrows(IOException::class.java) { store.append(events[2]) }
                    store.append(events[1])
                    assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "delta" })
                }
                FilePlaybackStatsPendingStore(directory, maxJournalBytes = byteBudget, maxJournalFrames = frameBudget).use { store ->
                    assertThrows(IOException::class.java) { store.append(events[2]) }
                    assertEquals(events[0], store.first())
                    store.acknowledge(events[0].eventId)
                    store.append(events[2])
                    for (event in events.drop(1)) {
                        assertEquals(event, store.first())
                        store.acknowledge(event.eventId)
                    }
                }
                FilePlaybackStatsPendingStore(directory).use { assertNull(it.first()) }
                assertFalse(directory.listFiles().orEmpty().any { it.extension == "delta" })
            }
        }
    }

    @Test
    fun appendReopenAndDurableAcknowledgementPreserveFifoMetadata() = withOwnedJournalDirectory { directory ->
        val first = snapshot("first", 1_700_000_000_000L, 42L)
        val second = snapshot("second", 1_700_086_400_000L, null).copy(
            listenedMs = 30_000L,
            playCountIncrement = 1,
            scheduleSync = true,
            observedClearedAt = 1_600_000_000_000L,
            song = first.song.copy(id = 8L, coverUrl = null, mediaUri = "", customName = null,
                customArtist = "", sourceStableKey = "fixture|second")
        )
        FilePlaybackStatsPendingStore(directory).use { store ->
            store.append(first)
            store.append(second)
        }
        FilePlaybackStatsPendingStore(directory).use { store ->
            assertEquals(first, store.first())
            store.acknowledge(first.eventId)
        }
        FilePlaybackStatsPendingStore(directory).use { store ->
            assertEquals(second, store.first())
            store.acknowledge(second.eventId)
        }
        FilePlaybackStatsPendingStore(directory).use { store ->
            assertNull(store.first())
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "delta" })
        }
    }

    @Test
    fun rejectedSecondOwnerLeavesTheFirstWritableUntilItCloses() = withOwnedJournalDirectory { directory ->
        val firstEvent = snapshot("owner-first", 1_700_000_000_000L, 42L)
        val laterEvent = snapshot("owner-later", 1_700_000_015_000L, 42L)
        FilePlaybackStatsPendingStore(directory).use { first ->
            first.append(firstEvent)
            FilePlaybackStatsPendingStore(directory).use { second ->
                assertThrows(IOException::class.java) { second.first() }
                second.close()
                first.append(laterEvent)
                assertEquals(firstEvent, first.first())
                first.acknowledge(firstEvent.eventId)
                assertEquals(laterEvent, first.first())
                assertThrows(IOException::class.java) { second.first() }
                first.close()
                assertEquals(laterEvent, second.first())
                second.acknowledge(laterEvent.eventId)
            }
        }
        FilePlaybackStatsPendingStore(directory).use { assertNull(it.first()) }
    }

    @Test
    fun partialUnpublishedFrameDoesNotBlockPrefixOrRemoveFutureVersionTemporaryFiles() = withOwnedJournalDirectory { directory ->
        val prefix = snapshot("prefix", 1_700_000_000_000L, 42L)
        val next = snapshot("after-reopen", 1_700_000_015_000L, 42L)
        FilePlaybackStatsPendingStore(directory).use { it.append(prefix) }
        val partial = File(directory, ".npst-v1-frame-${UUID.randomUUID()}.tmp")
        val future = File(directory, ".npst-v2-frame-${UUID.randomUUID()}.tmp")
        val futureBytes = byteArrayOf(1, 2, 3, 4)
        // 人工残留验证 Android 恢复路径，不在设备上终止测试进程
        partial.writeBytes(PlaybackStatsJournalCodec.frame(PlaybackStatsJournalCodec.payload(next)).copyOf(8))
        future.writeBytes(futureBytes)
        FilePlaybackStatsPendingStore(directory).use { store ->
            assertEquals(prefix, store.first())
            assertFalse(partial.exists())
            assertArrayEquals(futureBytes, future.readBytes())
            store.acknowledge(prefix.eventId)
            assertNull(store.first())
            store.append(next)
            assertEquals(next, store.first())
            store.acknowledge(next.eventId)
        }
        FilePlaybackStatsPendingStore(directory).use { assertNull(it.first()) }
        assertArrayEquals(futureBytes, future.readBytes())
    }

    private fun withOwnedJournalDirectory(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "playback-stats-journal-test-${UUID.randomUUID()}")
        assertTrue("Cannot create the owned playback journal directory", directory.mkdir())
        try {
            block(directory)
        } finally {
            assertTrue("Cannot remove the owned playback journal directory", directory.deleteRecursively())
        }
    }

    private fun snapshot(eventId: String, playedAt: Long, playlistId: Long?) = PlaybackStatsSnapshot(
        song = SongItem(7L, "合成曲目 🎵\u0000", "测试歌手", "测试专辑", 9L, 180_000L, "",
            mediaUri = null, customCoverUrl = "https://fixture.invalid/封面.jpg", customName = "",
            customArtist = null, localFileName = "合成.flac", localFilePath = null,
            channelId = "fixture", audioId = "fixture-audio", subAudioId = "2", sourceStableKey = "fixture|first"),
        listenedMs = 15_000L,
        playCountIncrement = 0,
        scheduleSync = false,
        localPlaylistId = playlistId,
        eventId = eventId,
        playedAt = playedAt,
        observedClearedAt = 1_500_000_000_000L
    )
}
