package moe.ouom.neriplayer.core.player.download.cover

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDownloadSingleFlightHandoffTest {
    @Test
    fun `waiting callers rethrow the owner failure without producing again`() = runTest {
        val singleFlight = CoverDownloadSingleFlight<String, String>()
        val releaseOwner = CompletableDeferred<Unit>()
        val producerCalls = AtomicInteger(0)

        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                singleFlight.run("song-key|cover.jpg") {
                    producerCalls.incrementAndGet()
                    releaseOwner.await()
                    throw IllegalStateException("network failure")
                }
            }
        }
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                singleFlight.run("song-key|cover.jpg") {
                    producerCalls.incrementAndGet()
                    "content://covers/duplicate.jpg"
                }
            }
        }
        releaseOwner.complete(Unit)

        val ownerError = owner.await().exceptionOrNull()
        val followerError = follower.await().exceptionOrNull()
        assertTrue(ownerError is IllegalStateException)
        assertTrue(followerError is IllegalStateException)
        assertEquals("network failure", followerError?.message)
        assertEquals(1, producerCalls.get())
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `a waiting caller takes over production after the owner is cancelled`() = runTest {
        val singleFlight = CoverDownloadSingleFlight<String, String>()

        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("song-key|cover.jpg") { awaitCancellation() }
        }
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("song-key|cover.jpg") { "content://covers/retried.jpg" }
        }
        assertEquals(1, singleFlight.inFlightCount)

        owner.cancelAndJoin()

        assertEquals("content://covers/retried.jpg", follower.await())
        assertEquals(0, singleFlight.inFlightCount)
    }
}
