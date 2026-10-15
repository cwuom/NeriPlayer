package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.queue.policy.QueueRepeatMode
import moe.ouom.neriplayer.core.player.queue.policy.failure.resolvePlaybackFailureAdvanceAction
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.queue.PlaybackFailureAdvanceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFailureRecoveryOwnerTest {
    @Test
    fun `login failure counts toward the bounded failure policy`() {
        val port = FakePort()

        PlaybackFailureRecoveryOwner(port).onResolutionFailure(
            10L, PlaybackResolutionFailure.LOGIN_REQUIRED, PlaybackCommandSource.LOCAL
        )

        assertEquals(1, port.failureCount)
        assertEquals(listOf(PlaybackResolutionFailure.LOGIN_REQUIRED.reason), port.advances)
        assertEquals(listOf(PlaybackResolutionFailure.LOGIN_REQUIRED), port.reportedFailures)
    }

    @Test
    fun `login failure at the end of a single track queue stops even in repeat all`() {
        val port = FakePort().apply { playlistSize = 1 }

        PlaybackFailureRecoveryOwner(port).onResolutionFailure(
            10L, PlaybackResolutionFailure.LOGIN_REQUIRED, PlaybackCommandSource.LOCAL
        )

        assertEquals(listOf(PlaybackFailureAdvanceAction.STOP), port.failureActions)
    }

    @Test
    fun `stale resolution failures do not change the new request or its failure budget`() {
        for (failure in PlaybackResolutionFailure.entries) {
            val port = FakePort().apply { requestToken = 11L }

            PlaybackFailureRecoveryOwner(port).onResolutionFailure(
                10L, failure, PlaybackCommandSource.LOCAL
            )

            assertEquals(0, port.failureCount)
            assertTrue(port.advances.isEmpty())
            assertTrue(port.reportedFailures.isEmpty())
            assertEquals(0, port.stopCount)
        }
    }

    @Test
    fun `paused resolution failure does not restart the queue`() {
        val port = FakePort().apply { resumePlaybackRequested = false }

        PlaybackFailureRecoveryOwner(port).onResolutionFailure(
            10L, PlaybackResolutionFailure.URL_UNAVAILABLE, PlaybackCommandSource.LOCAL
        )

        assertEquals(0, port.failureCount)
        assertTrue(port.advances.isEmpty())
        assertEquals(1, port.stopCount)
    }

    @Test
    fun `old player error is ignored before cache work starts`() = runTest {
        val port = FakePort().apply { requestToken = 11L }

        PlaybackFailureRecoveryOwner(port).onPlayerFailure(
            10L, "player_error", PlaybackCommandSource.LOCAL, "failed-track-cache"
        )

        assertTrue(port.invalidatedKeys.isEmpty())
        assertTrue(port.advances.isEmpty())
    }

    @Test
    fun `song switch during cache invalidation fences both mutation and queue advance`() = runTest {
        val port = FakePort()
        port.invalidationGate = CompletableDeferred()
        val recovery = launch {
            PlaybackFailureRecoveryOwner(port).onPlayerFailure(
                10L, "player_error", PlaybackCommandSource.REMOTE_SYNC, "failed-track-cache"
            )
        }
        runCurrent()
        assertEquals(listOf("failed-track-cache"), port.invalidationRequests)

        port.requestToken = 11L
        port.invalidationGate!!.complete(Unit)
        recovery.join()

        assertTrue(port.invalidatedKeys.isEmpty())
        assertTrue(port.advances.isEmpty())
    }

    @Test
    fun `pause during cache invalidation leaves playback paused`() = runTest {
        val port = FakePort()
        port.invalidationGate = CompletableDeferred()
        val recovery = launch {
            PlaybackFailureRecoveryOwner(port).onPlayerFailure(
                10L, "player_error", PlaybackCommandSource.LOCAL, "failed-track-cache"
            )
        }
        runCurrent()

        port.resumePlaybackRequested = false
        port.invalidationGate!!.complete(Unit)
        recovery.join()

        assertTrue(port.invalidatedKeys.isEmpty())
        assertTrue(port.advances.isEmpty())
    }

    @Test
    fun `cancelled cache invalidation never advances the queue`() = runTest {
        val port = FakePort().apply { invalidationGate = CompletableDeferred() }
        val recovery = launch {
            PlaybackFailureRecoveryOwner(port).onPlayerFailure(
                10L, "player_error", PlaybackCommandSource.LOCAL, "failed-track-cache"
            )
        }
        runCurrent()

        recovery.cancel()
        recovery.join()

        assertTrue(port.invalidatedKeys.isEmpty())
        assertTrue(port.advances.isEmpty())
    }

    @Test
    fun `cache cleanup swallowing cancellation still cannot advance the queue`() = runTest {
        val port = FakePort().apply {
            invalidationGate = CompletableDeferred()
            swallowInvalidationCancellation = true
        }
        val recovery = launch {
            PlaybackFailureRecoveryOwner(port).onPlayerFailure(
                10L, "player_error", PlaybackCommandSource.LOCAL, "failed-track-cache"
            )
        }
        runCurrent()

        recovery.cancel()
        recovery.join()

        assertTrue(port.invalidatedKeys.isEmpty())
        assertTrue(port.advances.isEmpty())
    }

    @Test
    fun `current player error invalidates the captured cache and preserves command source`() = runTest {
        val port = FakePort()

        PlaybackFailureRecoveryOwner(port).onPlayerFailure(
            10L, "player_error", PlaybackCommandSource.REMOTE_SYNC, "failed-track-cache"
        )

        assertEquals(listOf("failed-track-cache"), port.invalidatedKeys)
        assertEquals(listOf("player_error"), port.advances)
        assertEquals(PlaybackCommandSource.REMOTE_SYNC, port.lastCommandSource)
        assertFalse(port.failureActions.isEmpty())
    }

    @Test
    fun `offline resolution failure holds the current track instead of skipping the queue`() {
        val port = FakePort().apply { networkHold = true }

        PlaybackFailureRecoveryOwner(port).onResolutionFailure(
            10L, PlaybackResolutionFailure.URL_UNAVAILABLE, PlaybackCommandSource.LOCAL
        )

        assertEquals(listOf(PlaybackResolutionFailure.URL_UNAVAILABLE.reason), port.holds)
        assertEquals(0, port.failureCount)
        assertTrue(port.advances.isEmpty())
        assertEquals(0, port.stopCount)
    }

    @Test
    fun `login failures still advance even while offline`() {
        val port = FakePort().apply { networkHold = true }

        PlaybackFailureRecoveryOwner(port).onResolutionFailure(
            10L, PlaybackResolutionFailure.LOGIN_REQUIRED, PlaybackCommandSource.LOCAL
        )

        assertTrue(port.holds.isEmpty())
        assertEquals(listOf(PlaybackResolutionFailure.LOGIN_REQUIRED.reason), port.advances)
    }

    @Test
    fun `offline player error holds after cache work instead of advancing`() = runTest {
        val port = FakePort().apply { networkHold = true }

        PlaybackFailureRecoveryOwner(port).onPlayerFailure(
            10L, "player_error", PlaybackCommandSource.LOCAL, "failed-track-cache"
        )

        assertEquals(listOf("failed-track-cache"), port.invalidatedKeys)
        assertEquals(listOf("player_error"), port.holds)
        assertTrue(port.advances.isEmpty())
    }

    private class FakePort : PlaybackFailureRecoveryPort {
        override var requestToken = 10L
        override var resumePlaybackRequested = true
        var networkHold = false
        val holds = mutableListOf<String>()

        override fun holdForNetwork(reason: String): Boolean {
            if (networkHold) holds += reason
            return networkHold
        }
        var failureCount = 0
        var stopCount = 0
        var playlistSize = 2
        var lastCommandSource: PlaybackCommandSource? = null
        var invalidationGate: CompletableDeferred<Unit>? = null
        var swallowInvalidationCancellation = false
        val advances = mutableListOf<String>()
        val reportedFailures = mutableListOf<PlaybackResolutionFailure>()
        val failureActions = mutableListOf<PlaybackFailureAdvanceAction>()
        val invalidationRequests = mutableListOf<String>()
        val invalidatedKeys = mutableListOf<String>()

        override fun reportResolutionFailure(failure: PlaybackResolutionFailure) {
            reportedFailures += failure
        }

        override fun recordFailure() {
            failureCount++
        }

        override fun stopPreservingQueue() {
            stopCount++
        }

        override fun advanceAfterFailure(reason: String, commandSource: PlaybackCommandSource) {
            advances += reason
            lastCommandSource = commandSource
            failureActions += resolvePlaybackFailureAdvanceAction(0, playlistSize, QueueRepeatMode.ALL)
        }

        override suspend fun invalidateCache(
            key: String,
            reason: String,
            shouldApplyMutation: () -> Boolean
        ) {
            invalidationRequests += key
            try {
                invalidationGate?.await()
            } catch (error: CancellationException) {
                if (swallowInvalidationCancellation) return
                throw error
            }
            if (shouldApplyMutation()) invalidatedKeys += key
        }
    }
}
