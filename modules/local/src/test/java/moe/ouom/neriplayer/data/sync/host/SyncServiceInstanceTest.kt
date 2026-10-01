package moe.ouom.neriplayer.data.sync.host

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncServiceInstanceTest {
    @Test
    fun `concurrent readers share one instance and cached reads never rebuild`() {
        val holder = SyncServiceInstance<Any>()
        val builds = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val reads = (1..32).map { executor.submit<Any> { holder.get { builds.incrementAndGet(); Any() } } }
            val first = reads.first().get()
            for (read in reads) assertSame(first, read.get())
            assertSame(first, holder.get { error("cached instance must be reused") })
            assertEquals(1, builds.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed construction can be retried`() {
        val holder = SyncServiceInstance<Any>()
        assertThrows(IllegalStateException::class.java) { holder.get { error("failed") } }
        val expected = Any()
        assertSame(expected, holder.get { expected })
    }
}
