package moe.ouom.neriplayer.core.player.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshMediaLoadPolicyTest {

    @Test
    fun `stale refresh result is rejected when current generation changed even for same song key`() {
        val owner = refreshSemantics(requestGeneration = 10L)

        assertFalse(
            shouldApplyRefreshResult(
                owner = owner,
                current = owner.copy(requestGeneration = 11L),
                currentRequestGeneration = 11L,
                ownerActive = true
            )
        )
    }

    @Test
    fun `stale refresh apply action rejects every success side effect`() {
        val action = resolveRefreshApplyAction(
            accepted = false,
            resultKind = RefreshResultKind.SUCCESS
        )

        assertRejectsAllSideEffects(action)
    }

    @Test
    fun `cancelled stale refresh cannot apply fallback side effects`() {
        val action = resolveRefreshApplyAction(
            accepted = false,
            resultKind = RefreshResultKind.FALLBACK
        )

        assertFalse(action.fallbackSeek)
        assertFalse(action.fallbackPlayPause)
        assertRejectsAllSideEffects(action)
    }

    @Test
    fun `cancelled stale refresh cannot apply failure side effects`() {
        val action = resolveRefreshApplyAction(
            accepted = false,
            resultKind = RefreshResultKind.FAILURE
        )

        assertFalse(action.clearPendingSeek)
        assertFalse(action.emitFailureError)
        assertFalse(action.pauseAfterFailure)
        assertRejectsAllSideEffects(action)
    }

    @Test
    fun `new pending load cancels in flight refresh when generation changes`() {
        val decision = resolveRefreshInFlightDecision(
            owner = RefreshInFlightOwner(
                semantics = refreshSemantics(requestGeneration = 10L),
                isActive = true
            ),
            incoming = refreshSemantics(requestGeneration = 11L)
        )

        assertEquals(RefreshInFlightDecision.CancelExistingAndStartNew, decision)
    }

    @Test
    fun `same semantics refresh request reuses in flight owner`() {
        val semantics = refreshSemantics()
        val decision = resolveRefreshInFlightDecision(
            owner = RefreshInFlightOwner(semantics = semantics, isActive = true),
            incoming = semantics
        )

        assertEquals(RefreshInFlightDecision.ReuseExisting, decision)
    }

    @Test
    fun `non reusable refresh semantics cancel in flight owner`() {
        val owner = refreshSemantics()
        val differentSemantics = listOf(
            owner.copy(songKey = "other-song"),
            owner.copy(requestGeneration = owner.requestGeneration + 1),
            owner.copy(resumePositionMs = owner.resumePositionMs + 1),
            owner.copy(fallbackSeekPositionMs = owner.fallbackSeekPositionMs?.plus(1)),
            owner.copy(resumePlaybackAfterRefresh = !owner.resumePlaybackAfterRefresh),
            owner.copy(allowFallback = !owner.allowFallback),
            owner.copy(reason = "other_reason"),
            owner.copy(resumedPlaybackCommandSource = PlaybackCommandSource.REMOTE_SYNC),
            owner.copy(
                youtubeRecoveryStrategy = YouTubePlaybackRecoveryStrategy(
                    preferredQualityOverride = "high",
                    requireDirect = true,
                    preferM4a = true
                )
            ),
            owner.copy(cacheKeyToInvalidateBeforeResolve = "stale-youtube-cache")
        )

        differentSemantics.forEach { incoming ->
            assertEquals(
                RefreshInFlightDecision.CancelExistingAndStartNew,
                resolveRefreshInFlightDecision(
                    owner = RefreshInFlightOwner(owner, isActive = true),
                    incoming = incoming
                )
            )
        }
    }

    @Test
    fun `production refresh gate reuses existing operation without duplicate start or fallback`() {
        val controller = RefreshInFlightController<String>()
        val semantics = refreshSemantics()
        var starts = 0
        var fallbackCalls = 0
        val started = controller.startOrReuse(
            semantics = semantics,
            start = {
                starts++
                "operation"
            },
            cancel = {},
            fallback = { fallbackCalls++ }
        )

        val reused = controller.startOrReuse(
            semantics = semantics,
            start = {
                starts++
                "duplicate"
            },
            cancel = {},
            fallback = { fallbackCalls++ }
        )

        assertEquals(1, starts)
        assertEquals(0, fallbackCalls)
        assertTrue(started.startedNew)
        assertFalse(reused.startedNew)
        assertSame(started.operation, reused.operation)
    }

    @Test
    fun `production refresh gate cancels existing operation before starting non reusable request`() {
        val controller = RefreshInFlightController<String>()
        var cancels = 0
        controller.startOrReuse(
            semantics = refreshSemantics(requestGeneration = 10L),
            start = { "old" },
            cancel = { cancels++ },
            fallback = {}
        )

        val replacement = controller.startOrReuse(
            semantics = refreshSemantics(requestGeneration = 11L),
            start = { "new" },
            cancel = { cancels++ },
            fallback = {}
        )

        assertEquals(1, cancels)
        assertTrue(replacement.startedNew)
        assertEquals("new", replacement.operation)
    }

    @Test
    fun `cancelling not yet started refresh cancels deferred and clears active owner`() {
        val controller = RefreshInFlightController<CompletableDeferred<String>>()
        val deferred = CompletableDeferred<String>()
        val oldSemantics = refreshSemantics(requestGeneration = 10L)

        controller.startOrReuse(
            semantics = oldSemantics,
            start = { deferred },
            cancel = { RefreshDeferredCompletion(deferred).cancel() },
            fallback = {}
        )

        val cancelled = controller.cancelIfNotReusable(refreshSemantics(requestGeneration = 11L))

        assertTrue(cancelled)
        assertTrue(deferred.isCancelled)
        assertEquals(null, controller.currentSemantics())
    }

    @Test
    fun `unexpected refresh exception completes deferred exceptionally for reuse waiters`() {
        val deferred = CompletableDeferred<String>()
        val error = IllegalStateException("boom")

        val completed = RefreshDeferredCompletion(deferred).completeExceptionally(error)
        val failure = runCatching { runBlocking { deferred.await() } }

        assertTrue(completed)
        assertTrue(deferred.isCompleted)
        assertTrue(failure.isFailure)
    }

    @Test
    fun `resume intent mismatch is not reusable so stale intent cannot be applied`() {
        val oldIntent = refreshSemantics(
            resumePositionMs = 45_000L,
            resumePlaybackAfterRefresh = true
        )
        val currentIntent = refreshSemantics(
            resumePositionMs = 0L,
            resumePlaybackAfterRefresh = false
        )

        assertEquals(
            RefreshInFlightDecision.CancelExistingAndStartNew,
            resolveRefreshInFlightDecision(
                owner = RefreshInFlightOwner(oldIntent, isActive = true),
                incoming = currentIntent
            )
        )
    }

    @Test
    fun `stale refresh cache invalidation is denied when ownership changes before mutation`() {
        var current = true
        var invalidated = false
        val gate = RefreshSideEffectGate { current }

        val applied = gate.runPreparedMutation(
            prepare = { current = false },
            mutate = { invalidated = true }
        )

        assertFalse(applied)
        assertFalse(invalidated)
    }

    @Test
    fun `stale refresh main thread sequence stops before media install and pending clear`() {
        var current = true
        var prepared = false
        var mediaInstalled = false
        var pendingCleared = false
        var played = false
        val gate = RefreshSideEffectGate { current }

        val applied = gate.runMutationSequence(
            { prepared = true; current = false },
            { mediaInstalled = true },
            { pendingCleared = true },
            { played = true }
        )

        assertFalse(applied)
        assertTrue(prepared)
        assertFalse(mediaInstalled)
        assertFalse(pendingCleared)
        assertFalse(played)
    }

    @Test
    fun `stale refresh final mutations are denied after media apply returns`() {
        var current = true
        var failureCounterReset = false
        var playbackCommandEmitted = false
        val gate = RefreshSideEffectGate { current }

        val mediaApplied = gate.runMutation { current = false }
        val resetApplied = gate.runMutation { failureCounterReset = true }
        val commandApplied = gate.runMutation { playbackCommandEmitted = true }

        assertTrue(mediaApplied)
        assertFalse(resetApplied)
        assertFalse(commandApplied)
        assertFalse(failureCounterReset)
        assertFalse(playbackCommandEmitted)
    }

    @Test
    fun `stale refresh resolver cannot update duration before apply`() {
        var current = false
        var durationUpdated = false
        val sink = RefreshResolverSideEffects(RefreshSideEffectGate { current })

        val applied = sink.updateDuration { durationUpdated = true }

        assertFalse(applied)
        assertFalse(durationUpdated)
    }

    @Test
    fun `stale refresh resolver cannot emit error before apply`() {
        var current = false
        var errorEmitted = false
        val sink = RefreshResolverSideEffects(RefreshSideEffectGate { current })

        val applied = sink.emitError { errorEmitted = true }

        assertFalse(applied)
        assertFalse(errorEmitted)
    }

    @Test
    fun `stale refresh resolver cannot trigger local cache scan before apply`() {
        var current = false
        var scanned = false
        val sink = RefreshResolverSideEffects(RefreshSideEffectGate { current })

        val applied = sink.scanLocalFiles { scanned = true }

        assertFalse(applied)
        assertFalse(scanned)
    }

    @Test
    fun `stale refresh result cannot update duration when ownership changes before mutation`() {
        var current = true
        var durationUpdated = false
        val sink = RefreshResultSideEffects(RefreshSideEffectGate { current })

        val applied = sink.updateDuration(
            beforeMutation = { current = false },
            update = { durationUpdated = true }
        )

        assertFalse(applied)
        assertFalse(durationUpdated)
    }

    @Test
    fun `stale refresh cannot persist when ownership changes immediately before persist`() {
        var current = true
        var persisted = false
        val gate = RefreshSideEffectGate { current }

        val applied = gate.runPreparedMutation(
            prepare = { current = false },
            mutate = { persisted = true }
        )

        assertFalse(applied)
        assertFalse(persisted)
    }

    @Test
    fun `refresh persist mutation executes inside ownership gate`() = runBlocking {
        var current = false
        var persisted = false
        val gate = RefreshSideEffectGate { current }

        val applied = gate.runSuspendingMutation { persisted = true }

        assertFalse(applied)
        assertFalse(persisted)
    }

    private fun refreshSemantics(
        songKey: String = "song",
        requestGeneration: Long = 10L,
        resumePositionMs: Long = 1_000L,
        fallbackSeekPositionMs: Long? = 1_000L,
        resumePlaybackAfterRefresh: Boolean = true,
        allowFallback: Boolean = false,
        reason: String = "stale_resume",
        resumedPlaybackCommandSource: PlaybackCommandSource? = PlaybackCommandSource.LOCAL
    ) = RefreshRequestSemantics(
        songKey = songKey,
        requestGeneration = requestGeneration,
        resumePositionMs = resumePositionMs,
        fallbackSeekPositionMs = fallbackSeekPositionMs,
        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
        allowFallback = allowFallback,
        reason = reason,
        resumedPlaybackCommandSource = resumedPlaybackCommandSource
    )

    private fun assertRejectsAllSideEffects(action: RefreshApplyAction) {
        assertFalse(action.updateDuration)
        assertFalse(action.updateUrl)
        assertFalse(action.updateAudioInfo)
        assertFalse(action.persist)
        assertFalse(action.installMediaItem)
        assertFalse(action.clearPendingMediaLoad)
        assertFalse(action.resetFailureCounter)
        assertFalse(action.emitPlaybackCommand)
        assertFalse(action.clearPendingSeek)
        assertFalse(action.updateLoadedGeneration)
        assertFalse(action.fallbackSeek)
        assertFalse(action.fallbackPlayPause)
        assertFalse(action.emitFailureError)
        assertFalse(action.pauseAfterFailure)
    }
}
