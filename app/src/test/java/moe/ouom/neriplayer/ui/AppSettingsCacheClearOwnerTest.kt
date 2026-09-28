package moe.ouom.neriplayer.ui

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.ui.settings.owner.AppSettingsCacheClearOwner
import moe.ouom.neriplayer.ui.settings.owner.formatExtraCacheClearResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsCacheClearOwnerTest {
    @Test
    fun selectedCachesClearInPlayerLyricsExtraOrder() = runBlocking {
        val events = mutableListOf<String>()
        val owner = owner(events)
        val message = owner.clear(
            StorageCacheClearOptions(audioCache = true, imageCache = false, lyricsCache = true)
        )

        assertEquals(listOf("player", "lyrics", "extra", "format"), events)
        assertEquals("player cleared · extra cleared", message)
    }

    @Test
    fun noSelectionHasNoSideEffects() = runBlocking {
        val events = mutableListOf<String>()
        val owner = owner(events)
        val message = owner.clear(StorageCacheClearOptions(audioCache = false, imageCache = false))
        assertEquals(emptyList<String>(), events)
        assertEquals("", message)
    }

    @Test
    fun extraResultKeepsPartialAndReusableRoomMessagesDistinct() {
        val partial = result(success = false, reusable = 10)
        val reusable = result(success = true, reusable = 10)
        val complete = result(success = true, reusable = 0)

        fun message(result: ExtraCacheClearResult) = formatExtraCacheClearResult(
            result,
            partialMessage = { "partial" },
            roomCompleteMessage = { freed, room -> "room:$freed:$room" },
            completeMessage = { freed -> "complete:$freed" }
        )

        assertEquals("partial", message(partial))
        assertEquals("room:20:10", message(reusable))
        assertEquals("complete:20", message(complete))
    }

    private fun owner(events: MutableList<String>) = AppSettingsCacheClearOwner(
        clearPlayerCache = { events += "player"; "player cleared" },
        clearLyricsCache = { events += "lyrics" },
        clearExtraCaches = { events += "extra"; result(success = true, reusable = 0) },
        formatExtraResult = { events += "format"; "extra cleared" }
    )

    private fun result(success: Boolean, reusable: Long) = ExtraCacheClearResult(
        success = success,
        freedBytes = 20,
        roomBytesMadeReusable = reusable,
        deletedFiles = 1
    )
}
