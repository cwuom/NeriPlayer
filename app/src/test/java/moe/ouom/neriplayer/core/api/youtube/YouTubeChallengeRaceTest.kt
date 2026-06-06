package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeChallengeRaceTest {

    @Test
    fun awaitFirstChallengeSuccess_returnsFirstSuccessfulCandidateWithoutWaitingForPendingCandidate() = runTest {
        val winner = CompletableDeferred(
            ChallengeCandidateResult(
                source = "EJS_FALLBACK",
                value = "resolved-signature",
                elapsedMs = 5L
            )
        )
        val pendingLoser = CompletableDeferred<ChallengeCandidateResult<String>>()

        val result = awaitFirstChallengeSuccess(listOf(winner, pendingLoser))

        assertEquals("EJS_FALLBACK", result?.source)
        assertEquals("resolved-signature", result?.value)
        assertEquals(5L, result?.elapsedMs)
        assertEquals(0L, currentTime)
    }

    @Test
    fun awaitFirstChallengeSuccess_cancelsPendingCandidatesAfterWinner() = runTest {
        val winner = CompletableDeferred(
            ChallengeCandidateResult(
                source = "NEWPIPE",
                value = "resolved-n",
                elapsedMs = 12L
            )
        )
        val pendingLoser = CompletableDeferred<ChallengeCandidateResult<String>>()

        val result = awaitFirstChallengeSuccess(listOf(winner, pendingLoser))

        assertEquals("resolved-n", result?.value)
        assertTrue(pendingLoser.isCancelled)
    }

    @Test
    fun awaitFirstChallengeSuccess_returnsCompletedSuccessWhenCompletedNullCandidateIsSelectedFirst() = runTest {
        val completedWithoutValue = CompletableDeferred(
            ChallengeCandidateResult<String>(
                source = "NEWPIPE",
                value = null,
                elapsedMs = 7L
            )
        )
        val completedWinner = CompletableDeferred(
            ChallengeCandidateResult(
                source = "EJS_FALLBACK",
                value = "resolved-signature",
                elapsedMs = 8L
            )
        )

        val result = awaitFirstChallengeSuccess(listOf(completedWithoutValue, completedWinner))

        assertEquals("EJS_FALLBACK", result?.source)
        assertEquals("resolved-signature", result?.value)
        assertEquals(8L, result?.elapsedMs)
    }

    @Test
    fun awaitFirstChallengeSuccess_returnsNullAfterAllCandidatesCompleteWithoutValue() = runTest {
        val newPipe = async {
            delay(10L)
            ChallengeCandidateResult<String>(
                source = "NEWPIPE",
                value = null,
                elapsedMs = 10L
            )
        }
        val ejs = async {
            delay(20L)
            ChallengeCandidateResult<String>(
                source = "EJS_FALLBACK",
                value = null,
                elapsedMs = 20L
            )
        }

        val result = awaitFirstChallengeSuccess(listOf(newPipe, ejs))

        assertNull(result)
        assertTrue(newPipe.isCompleted)
        assertTrue(ejs.isCompleted)
        assertFalse(newPipe.isCancelled)
        assertFalse(ejs.isCancelled)
    }
}
