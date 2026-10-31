package moe.ouom.neriplayer.core.player.service.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackNotificationDeliveryRegressionTest {
    @Test
    fun `a burst coalesces to its last title without losing it to silent system rate limits`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        repeat(20) { fixture.publish("Edited $it") }

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("Edited 19", fixture.port.delivered?.song?.displayName())
        assertTrue("the media channel must merge intermediate saves", fixture.port.attempts.size <= 4)
        assertTrue(fixture.port.isNotificationPublicationCurrent(checkNotNull(fixture.port.notificationPublicationToken())))
    }

    @Test
    fun `the latest save retries a silent drop caused by other package notifications`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        advanceTimeBy(1_500L)
        runCurrent()
        fixture.port.fillPackageQuota()
        fixture.publish("Restored")
        assertEquals("Original", fixture.port.delivered?.song?.displayName())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("Restored", fixture.port.delivered?.song?.displayName())
        assertTrue(fixture.port.attempts.count { it.second == "Restored" } in 2..3)
    }

    @Test
    fun `a newer save cancels retries for the older silently dropped metadata`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        advanceTimeBy(1_500L)
        runCurrent()
        fixture.port.fillPackageQuota()
        fixture.publish("Superseded")
        fixture.publish("Restored")

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals("Restored", fixture.port.delivered?.song?.displayName())
        assertEquals(1, fixture.port.attempts.count { it.second == "Superseded" })
    }

    @Test
    fun `destroy cancels a queued save and all pending publication verification`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        fixture.publish("Restored")
        fixture.owner.releaseSessionForDestroy()

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(listOf("Original"), fixture.port.attempts.map { it.second })
    }

    @Test
    fun `leaving foreground cancels queued notifications before the session is destroyed`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        fixture.publish("Restored")
        fixture.owner.updateNotification(false, false, StatusBarLyricNotificationState(false, null), false)

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(listOf("Original"), fixture.port.attempts.map { it.second })
    }

    @Test
    fun `same value forced refreshes remain deliverable and share the fixed publication window`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        repeat(20) { fixture.publish("Original", force = true) }

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(listOf("Original", "Original"), fixture.port.attempts.map { it.second })
    }

    @Test
    fun `continuous new values do not postpone a fixed publication window`() = runTest {
        val fixture = Fixture(this)
        fixture.publish("Original")
        repeat(15) {
            advanceTimeBy(100L)
            runCurrent()
            fixture.publish("Edited $it")
        }
        advanceTimeBy(500L)
        runCurrent()

        assertEquals("Edited 14", fixture.port.delivered?.song?.displayName())
        assertTrue(fixture.port.attempts.size in 3..5)
        assertTrue(fixture.port.attempts.zipWithNext().all { (previous, next) -> next.first - previous.first >= 500L })
    }

    @Test
    fun `silent rejection exhausts two retries without leaving a repeating timer`() = runTest {
        val fixture = Fixture(this)
        fixture.port.rejectAll = true
        fixture.publish("Restored")

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(3, fixture.port.attempts.size)
        assertEquals(null, fixture.port.delivered)
    }

    @Test
    fun `a transient publisher exception can recover without losing the latest request`() = runTest {
        val fixture = Fixture(this)
        fixture.port.publishFailure = IllegalStateException("synthetic notification failure")
        fixture.publish("Restored")

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals("Restored", fixture.port.delivered?.song?.displayName())
        assertEquals(2, fixture.port.attempts.size)
    }

    @Test
    fun `a failed confirmation remains bounded and destruction cancels its retry`() = runTest {
        val fixture = Fixture(this)
        fixture.port.confirmationFailure = IllegalStateException("synthetic confirmation failure")
        fixture.publish("Restored")
        advanceTimeBy(1_001L)
        runCurrent()
        fixture.owner.releaseSessionForDestroy()
        val attemptsAtDestroy = fixture.port.attempts.size

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(attemptsAtDestroy, fixture.port.attempts.size)
        assertTrue(attemptsAtDestroy in 1..2)
    }

    @Test
    fun `cancellation from the publisher is propagated without scheduling retries`() = runTest {
        val fixture = Fixture(this)
        fixture.port.publishFailure = CancellationException("synthetic cancellation")
        assertThrows(CancellationException::class.java) { fixture.publish("Restored") }

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, fixture.port.attempts.size)
    }

    @Test
    fun `cancellation while confirming does not turn into another publication`() = runTest {
        val fixture = Fixture(this)
        fixture.port.confirmationFailure = CancellationException("synthetic cancellation")
        fixture.publish("Restored")

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, fixture.port.attempts.size)
    }

    private class Fixture(scope: TestScope) {
        private val source = Source()
        val port = RateLimitedPort { scope.testScheduler.currentTime }
        private val artwork = mock(PlaybackArtworkOwner::class.java) { call ->
            when (call.method.name) {
                "observe", "snapshotFor" -> PlaybackArtworkSnapshot(null, null, null, false, false, false)
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val owner = PlaybackServicePresentationOwner(source, port, artwork, scope.backgroundScope)

        fun publish(title: String, force: Boolean = false) {
            source.song = source.song.copy(customName = title)
            owner.updateNotification(force, true, StatusBarLyricNotificationState(false, null), false)
        }
    }

    private class RateLimitedPort(private val now: () -> Long) : PlaybackServicePresentationPort by mock(
        PlaybackServicePresentationPort::class.java
    ) {
        val attempts = mutableListOf<Pair<Long, String?>>()
        private val packageEnqueues = ArrayDeque<Long>()
        var delivered: PlaybackServiceNotificationRenderInputs? = null
            private set
        private var attemptedToken: String? = null
        private var deliveredToken: String? = null
        var rejectAll = false
        var publishFailure: RuntimeException? = null
        var confirmationFailure: RuntimeException? = null

        override fun elapsedRealtime(): Long = now()

        override fun publishNotification(inputs: PlaybackServiceNotificationRenderInputs) {
            attempts += now() to inputs.song?.displayName()
            publishFailure?.let { publishFailure = null; throw it }
            attemptedToken = "publication-${attempts.size}"
            while (packageEnqueues.firstOrNull()?.let { it <= now() - 1_000L } == true) packageEnqueues.removeFirst()
            packageEnqueues.addLast(now())
            if (!rejectAll && packageEnqueues.size <= 5) {
                delivered = inputs
                deliveredToken = attemptedToken
            }
        }

        fun fillPackageQuota() = repeat(5) { packageEnqueues.addLast(now()) }

        override fun notificationPublicationToken(): String? = attemptedToken

        override fun isNotificationPublicationCurrent(token: String): Boolean {
            confirmationFailure?.let { throw it }
            return deliveredToken == token
        }
    }

    private class Source : PlaybackServicePresentationSource {
        var song = SongItem(id = 1, name = "Original", artist = "Synthetic artist", album = "Synthetic album",
            albumId = 0L, durationMs = 30_000L, coverUrl = null)
        override fun playback() = PlaybackServicePlaybackSnapshot(song, true, 1_000L, 0L,
            false, true, true, false, true, false, 1f)
        override fun metadata() = PlaybackServiceMetadataInputs(ExternalBluetoothLyricPayload(), null, false)
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = emptySet<String>()
        override fun localPlaylistsReady() = true
        override fun isLocalSong(song: SongItem) = false
        override fun shareUrl(song: SongItem): String? = null
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) = Unit
    }
}
