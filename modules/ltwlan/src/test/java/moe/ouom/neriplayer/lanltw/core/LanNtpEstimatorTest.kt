package moe.ouom.neriplayer.lanltw.core
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.lan.LanMessage
import org.junit.Assert.*
import org.junit.Test
class LanNtpEstimatorTest {
    @Test fun originalJavaScriptConvergesAndResetDropsPendingPair() = runBlocking {
        val estimator = LanNtpEstimator()
        try {
            estimator.reset()
            repeat(20) { group ->
                val start = group * 50.0 + 1000
                fun packet(index: Int) = LanMessage("NTP_RESPONSE", t0 = start + index * 25, t1 = start + index * 25 + 55,
                    t2 = start + index * 25 + 56, probeGroupId = group, probeGroupIndex = index)
                assertNull(estimator.response(packet(0), start + 11))
                val result = estimator.response(packet(1), start + 36)!!
                assertEquals(50.0, result.offset, .00001)
                assertEquals(10.0, result.rtt, .00001)
                assertEquals(group >= 15, result.synced)
            }
            estimator.reset()
            assertNull(estimator.response(LanMessage("NTP_RESPONSE", t0 = 1000.0, t1 = 1055.0, t2 = 1065.0, probeGroupId = 30, probeGroupIndex = 0), 1008.0))
            assertNull(estimator.response(LanMessage("NTP_RESPONSE", t0 = 1025.0, t1 = 1080.0, t2 = 1090.0, probeGroupId = 30, probeGroupIndex = 1), 1033.0))
            assertNull(estimator.response(LanMessage("NTP_RESPONSE", t0 = 0.0, t1 = 1.0, t2 = 2.0, probeGroupId = 99, probeGroupIndex = 1), 3.0))
        } finally { estimator.close() }
    }
}
