package moe.ouom.neriplayer.core.player.persistence.stats

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackStatsJournalSyncFlagTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `both sync flag values survive a round trip`() {
        for (scheduleSync in listOf(true, false)) {
            val original = snapshot().copy(scheduleSync = scheduleSync)

            assertEquals(original, PlaybackStatsJournalCodec.read(journalWith(original)))
        }
    }

    @Test
    fun `non primitive sync flags are rejected`() {
        for (flag in listOf<JsonElement>(JsonNull.INSTANCE, JsonObject(), JsonArray())) {
            val payload = JsonParser.parseString(String(PlaybackStatsJournalCodec.payload(snapshot()), Charsets.UTF_8))
                .asJsonObject
                .apply { add("scheduleSync", flag) }
            val file = temporary.newFile().apply {
                writeBytes(PlaybackStatsJournalCodec.frame(payload.toString().toByteArray(Charsets.UTF_8)))
            }

            val error = assertThrows(IOException::class.java) { PlaybackStatsJournalCodec.read(file) }
            assertEquals("Invalid playback journal sync flag", error.message)
        }
    }

    private fun journalWith(snapshot: PlaybackStatsSnapshot): File = temporary.newFile().apply {
        writeBytes(PlaybackStatsJournalCodec.frame(PlaybackStatsJournalCodec.payload(snapshot)))
    }

    private fun snapshot() = PlaybackStatsSnapshot(SongItem(1, "name", "artist", "album", 1, 60_000, null),
        15_000, 0, false, eventId = "synthetic-event", playedAt = 42)
}
