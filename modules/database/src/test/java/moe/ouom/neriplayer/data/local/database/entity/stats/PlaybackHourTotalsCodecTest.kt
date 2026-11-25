package moe.ouom.neriplayer.data.local.database.entity.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackHourTotalsCodecTest {
    private val total = SyncPlaybackHourTotal(epochStartedAt = 1, hourStartAt = 100, localHour = 9, totalListenMs = 5)

    @Test
    fun acceptsTheExactUtf8LimitAndRejectsOneAdditionalByte() {
        val overhead = PlaybackHourTotalsCodec.encode(listOf(total)).length
        val budget = PlaybackHourTotalsCodec.MAX_UTF8_BYTES - overhead
        val device = "界".repeat(budget / 3) + "a".repeat(budget % 3)
        val atLimit = listOf(total.copy(deviceId = device))
        val encoded = PlaybackHourTotalsCodec.encode(atLimit)
        assertEquals(PlaybackHourTotalsCodec.MAX_UTF8_BYTES, encoded.toByteArray(Charsets.UTF_8).size)
        assertEquals(atLimit, PlaybackHourTotalsCodec.decode(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackHourTotalsCodec.encode(listOf(total.copy(deviceId = device + "a")))
        }
    }

    @Test
    fun accountsForAllRowsAndEscapedIdentifiers() {
        val device = "\"".repeat(PlaybackHourTotalsCodec.MAX_UTF8_BYTES / 6)
        val row = total.copy(deviceId = device)
        val pair = listOf(row, row.copy(localHour = 10))
        assertEquals(pair, PlaybackHourTotalsCodec.decode(PlaybackHourTotalsCodec.encode(pair)))
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackHourTotalsCodec.encode(pair + row.copy(localHour = 11))
        }
    }
}
