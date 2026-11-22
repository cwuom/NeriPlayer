package moe.ouom.neriplayer.lanltw.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.lan.LanMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LanNtpEstimatorTest {
    @Test fun matchesEveryProbeInTheSeededBeatSyncCorpus() = runBlocking {
        val estimator = LanNtpEstimator()
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = fixture("ntp-corpus.tsv").associate { row ->
            val (group, hash) = row.split('\t')
            group.toInt() to hash
        }
        var seed = 0x4c414e34
        fun draw(bound: Int): Int {
            seed = seed * 1664525 + 1013904223
            return (seed ushr 8) % bound
        }
        suspend fun probe(group: Int, index: Int, transit: Double, rtt: Double) {
            val t0 = 1000.0 + group * 60 + index * 25
            val t1 = t0 + transit
            val packet = LanMessage("NTP_RESPONSE", t0 = t0, t1 = t1, t2 = t1 + 1,
                probeGroupId = group, probeGroupIndex = index)
            val result = estimator.response(packet, t0 + rtt + 1)
            if (result == null) {
                digest.update(0)
            } else {
                digest.update(if (result.synced) 2 else 1)
                digest.update(ByteBuffer.allocate(16).putDouble(result.offset).putDouble(result.rtt).array())
            }
        }
        try {
            repeat(2048) { group ->
                if (group % 257 == 0) estimator.reset()
                val transit = (draw(3201) - 1600) / 8.0
                val drift = (draw(97) - 48) / 8.0
                val firstRtt = draw(241) / 8.0
                val secondRtt = if (group % 13 == 0) firstRtt else draw(241) / 8.0
                probe(group, 0, transit, firstRtt)
                probe(group, 1, transit + drift, secondRtt)
                if ((group + 1) % 256 == 0) {
                    assertEquals("through group $group", expected.getValue(group), digest.digest().toHexString())
                }
            }
        } finally { estimator.close() }
    }

    private fun fixture(name: String): List<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/beatsync/$name"))
        return stream.bufferedReader().use { it.readLines() }.drop(1)
    }

    @Test fun matchesFrozenBeatSyncResponseTrace() = runBlocking {
        val estimator = LanNtpEstimator()
        try {
            for ((index, row) in fixture("ntp-trace.tsv").withIndex()) {
                if (row == "reset") {
                    estimator.reset()
                    continue
                }
                val fields = row.split('\t')
                val packet = LanMessage("NTP_RESPONSE", probeGroupId = fields[0].toInt(), probeGroupIndex = fields[1].toInt(),
                    t0 = fields[2].toDouble(), t1 = fields[3].toDouble(), t2 = fields[4].toDouble())
                val result = estimator.response(packet, fields[5].toDouble())
                val label = "trace line ${index + 2}"
                if (fields.size == 6) {
                    assertNull(label, result)
                } else {
                    assertNotNull(label, result)
                    assertEquals(label, fields[6].toDouble(), result!!.offset, 1e-6)
                    assertEquals(label, fields[7].toDouble(), result.rtt, 1e-6)
                    assertEquals(label, fields[8].toBooleanStrict(), result.synced)
                }
            }
        } finally { estimator.close() }
    }

    @Test fun onlyASecondProbeWithTheExpectedIndexCompletesThePair() = runBlocking {
        val estimator = LanNtpEstimator()
        try {
            val first = LanMessage("NTP_RESPONSE", t0 = 100.0, t1 = 123.0, t2 = 124.0, probeGroupId = 9, probeGroupIndex = 0)
            val second = first.copy(t0 = 125.0, t1 = 148.0, t2 = 149.0, probeGroupIndex = 1)
            assertNull(estimator.response(first, 111.0))
            assertNull(estimator.response(second.copy(probeGroupIndex = 2), 136.0))
            assertNull(estimator.response(second.copy(probeGroupId = null), 136.0))
            assertNull(estimator.response(second.copy(probeGroupIndex = null), 136.0))
            assertNull(estimator.response(second.copy(t0 = null), 136.0))
            assertNull(estimator.response(second.copy(t1 = null), 136.0))
            assertNull(estimator.response(second.copy(t1 = Double.NaN), 136.0))
            assertNull(estimator.response(second.copy(t2 = null), 136.0))
            assertNull(estimator.response(second.copy(t2 = 147.0), 136.0))
            assertNull(estimator.response(second, Double.POSITIVE_INFINITY))
            assertEquals(LanNtpEstimator.Estimate(18.0, 10.0, false), estimator.response(second, 136.0))
            estimator.close()
            assertNull(estimator.response(first, 111.0))
            assertNull(estimator.response(second, 136.0))
        } finally { estimator.close() }
    }

    @Test fun finiteRttsStillHaveAFiniteMeanWhenTheirSumOverflows() = runBlocking {
        val estimator = LanNtpEstimator()
        try {
            repeat(3) { group ->
                val first = LanMessage("NTP_RESPONSE", t0 = 0.0, t1 = 0.0, t2 = 0.0,
                    probeGroupId = group, probeGroupIndex = 0)
                val second = first.copy(t0 = 25.0, t1 = 25.0, t2 = 25.0, probeGroupIndex = 1)
                assertNull(estimator.response(first, 1e308))
                assertEquals(1e308, estimator.response(second, 1e308)!!.rtt, 0.0)
            }
        } finally { estimator.close() }
    }

    @Test fun convergesAfterSixteenValidPairsAndDropsNegativeRtt() = runBlocking {
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
