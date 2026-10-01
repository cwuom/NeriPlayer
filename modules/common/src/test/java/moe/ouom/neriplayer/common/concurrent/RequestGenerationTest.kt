package moe.ouom.neriplayer.common.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGenerationTest {
    @Test
    fun `capturing sibling requests keeps them valid until the generation advances`() {
        val requests = RequestGeneration()
        val first = requests.capture()
        val sibling = requests.capture()

        assertSame(first, sibling)
        assertTrue(first.isCurrent)
        assertTrue(sibling.isCurrent)

        val next = requests.advance()

        assertFalse(first.isCurrent)
        assertFalse(sibling.isCurrent)
        assertTrue(next.isCurrent)
        assertSame(next, requests.capture())

        requests.advance()

        assertFalse(first.isCurrent)
        assertFalse(next.isCurrent)
    }

    @Test
    fun `advancing one owner does not invalidate another owner's requests`() {
        val firstOwner = RequestGeneration()
        val secondOwner = RequestGeneration()
        val first = firstOwner.capture()
        val second = secondOwner.capture()

        firstOwner.advance()

        assertFalse(first.isCurrent)
        assertTrue(second.isCurrent)
    }

    @Test
    fun `concurrent advances issue distinct tickets with exactly one current ticket`() {
        val requests = RequestGeneration()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = List(16) {
                executor.submit<RequestGeneration.Ticket> {
                    check(start.await(5, TimeUnit.SECONDS))
                    requests.advance()
                }
            }
            start.countDown()
            val tickets = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(tickets.size, tickets.toSet().size)
            assertEquals(1, tickets.count { it.isCurrent })
            assertSame(requests.capture(), tickets.single { it.isCurrent })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
