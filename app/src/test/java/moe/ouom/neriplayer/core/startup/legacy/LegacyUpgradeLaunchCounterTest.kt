package moe.ouom.neriplayer.core.startup.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUpgradeLaunchCounterTest {
    @Test
    fun `retries inside one launch count as a single failed launch`() {
        val firstLaunch = LegacyUpgradeLaunchCounter("launch-1")
        val recorded = firstLaunch.recordFailure(null)

        assertEquals("1|launch-1", recorded)
        assertTrue(firstLaunch.countedInThisProcess(recorded))
        assertNull(firstLaunch.recordFailure(recorded))
        assertEquals(0, firstLaunch.failedLaunchesBeforeThisProcess(recorded))
    }

    @Test
    fun `later launches see every earlier failed launch`() {
        val first = LegacyUpgradeLaunchCounter("launch-1").recordFailure(null)
        val secondLaunch = LegacyUpgradeLaunchCounter("launch-2")

        assertFalse(secondLaunch.countedInThisProcess(first))
        assertEquals(1, secondLaunch.failedLaunchesBeforeThisProcess(first))
        val second = secondLaunch.recordFailure(first)
        assertEquals("2|launch-2", second)
        assertEquals(1, secondLaunch.failedLaunchesBeforeThisProcess(second))
        assertEquals(2, LegacyUpgradeLaunchCounter("launch-3").failedLaunchesBeforeThisProcess(second))
    }

    @Test
    fun `unreadable persisted values start from zero`() {
        val counter = LegacyUpgradeLaunchCounter("launch")

        listOf(null, "", "garbage", "|launch-0", "-4|other").forEach { persisted ->
            assertEquals("persisted=$persisted", 0, counter.failedLaunchesBeforeThisProcess(persisted))
        }
        assertEquals("1|launch", counter.recordFailure("garbage"))
    }
}
