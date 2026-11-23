package moe.ouom.neriplayer.ui.screen.tab.home

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGreetingTest {

    @Test
    fun `greeting follows the local hour`() {
        assertEquals(HomeGreetingPeriod.Morning, homeGreetingPeriod(7))
        assertEquals(HomeGreetingPeriod.Afternoon, homeGreetingPeriod(14))
        assertEquals(HomeGreetingPeriod.Evening, homeGreetingPeriod(22))
        assertEquals(HomeGreetingPeriod.LateEvening, homeGreetingPeriod(23))
        assertEquals(HomeGreetingPeriod.Midnight, homeGreetingPeriod(0))
        assertEquals(HomeGreetingPeriod.Midnight, homeGreetingPeriod(4))
        assertEquals(HomeGreetingPeriod.Morning, homeGreetingPeriod(5))
    }

    @Test
    fun `around noon the greeting is noon instead of morning or afternoon`() {
        assertEquals(HomeGreetingPeriod.Morning, homeGreetingPeriod(10))
        assertEquals(HomeGreetingPeriod.Noon, homeGreetingPeriod(11))
        assertEquals(HomeGreetingPeriod.Noon, homeGreetingPeriod(12))
        assertEquals(HomeGreetingPeriod.Afternoon, homeGreetingPeriod(13))
        assertTrue(homeGreetingUsesTone(HomeGreetingPeriod.Noon))
    }

    @Test
    fun `late night greetings stay calm without a playful tone`() {
        assertTrue(homeGreetingUsesTone(HomeGreetingPeriod.Evening))
        assertFalse(homeGreetingUsesTone(HomeGreetingPeriod.LateEvening))
        assertFalse(homeGreetingUsesTone(HomeGreetingPeriod.Midnight))
    }

    @Test
    fun `chinese characters and digits are separated by a space`() {
        assertEquals("11 月 22 日 星期日", spaceCjkAndDigits("11月22日 星期日"))
        assertEquals("Nov 22", spaceCjkAndDigits("Nov 22"))
    }

    @Test
    fun `greeting refreshes on the next whole hour so it crosses noon and midnight`() {
        val beforeEleven = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 10, 59, 30)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(30_000L, homeGreetingNextRefreshDelayMs(beforeEleven))
        val beforeMidnight = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 23, 59, 59)
            set(Calendar.MILLISECOND, 500)
        }.timeInMillis
        assertEquals(500L, homeGreetingNextRefreshDelayMs(beforeMidnight))
        val onTheHour = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(3_600_000L, homeGreetingNextRefreshDelayMs(onTheHour))
    }
}
