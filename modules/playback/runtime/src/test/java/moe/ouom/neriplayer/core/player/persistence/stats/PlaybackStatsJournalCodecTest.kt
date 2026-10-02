package moe.ouom.neriplayer.core.player.persistence.stats

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackStatsJournalCodecTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `explicit wire preserves signed timestamp extremes nullable identity and empty metadata`() {
        val original = snapshot().copy(playedAt = Long.MIN_VALUE, observedClearedAt = Long.MAX_VALUE,
            localPlaylistId = Long.MIN_VALUE, listenedMs = Long.MAX_VALUE, playCountIncrement = Int.MAX_VALUE,
            song = snapshot().song.copy(id = Long.MIN_VALUE, albumId = Long.MAX_VALUE,
                durationMs = Long.MIN_VALUE, coverUrl = "", mediaUri = "", customName = "", sourceStableKey = ""))
        val file = temporary.newFile().apply { writeBytes(PlaybackStatsJournalCodec.frame(PlaybackStatsJournalCodec.payload(original))) }
        assertEquals(original, PlaybackStatsJournalCodec.read(file))
    }

    @Test
    fun `encoding rejects invalid sampled counts and event identities`() {
        for (invalid in listOf(snapshot().copy(eventId = ""), snapshot().copy(eventId = " "),
            snapshot().copy(eventId = "x".repeat(513)), snapshot().copy(listenedMs = -1),
            snapshot().copy(playCountIncrement = -1))) {
            assertThrows(IOException::class.java) { PlaybackStatsJournalCodec.payload(invalid) }
        }
    }

    @Test
    fun `valid crc cannot make malformed fields acceptable`() {
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.remove("song") }, { it.remove("eventId") }, { it.add("eventId", JsonNull.INSTANCE) },
            { it.addProperty("eventId", 7) }, { it.addProperty("eventId", " ") },
            { it.addProperty("eventId", "x".repeat(513)) },
            { it.addProperty("scheduleSync", "true") }, { it.remove("scheduleSync") },
            { it.addProperty("playCountIncrement", -1) }, { it.addProperty("playCountIncrement", 2147483648L) },
            { it.addProperty("listenedMs", -1) }, { it.addProperty("listenedMs", "15") },
            { it.addProperty("listenedMs", 1.5) }, { it.add("listenedMs", JsonNull.INSTANCE) },
            { it.remove("playedAt") }, { it.addProperty("playedAt", "9223372036854775808") },
            { it.getAsJsonObject("song").remove("name") },
            { it.getAsJsonObject("song").addProperty("name", false) },
            { it.getAsJsonObject("song").remove("coverUrl") },
            { it.getAsJsonObject("song").addProperty("coverUrl", 3) }
        )
        for (mutation in mutations) {
            val value = JsonParser.parseString(String(PlaybackStatsJournalCodec.payload(snapshot()), Charsets.UTF_8)).asJsonObject
            mutation(value)
            val file = temporary.newFile().apply { writeBytes(PlaybackStatsJournalCodec.frame(value.toString().toByteArray(Charsets.UTF_8))) }
            assertThrows(IOException::class.java) { PlaybackStatsJournalCodec.read(file) }
        }
    }

    @Test
    fun `invalid utf8 malformed json and zero payload fail before returning any event`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), "{".toByteArray(), byteArrayOf())) {
            val file = temporary.newFile().apply { writeBytes(PlaybackStatsJournalCodec.frame(bytes)) }
            assertThrows(IOException::class.java) { PlaybackStatsJournalCodec.read(file) }
        }
    }

    private fun snapshot() = PlaybackStatsSnapshot(SongItem(1, "name", "artist", "album", 1, 60_000, null),
        15_000, 0, false, eventId = "synthetic-event", playedAt = 42)
}
