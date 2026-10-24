package moe.ouom.neriplayer.ui

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearOutcome
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
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
        assertEquals("extra cleared", message)
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
    fun audioAndExtraBytesAreFormattedAsOneResult() = runBlocking {
        var formatted: ExtraCacheClearResult? = null
        val owner = AppSettingsCacheClearOwner(
            clearPlayerCache = { PlayerCacheClearOutcome(ExtraCacheClearResult(true, 70, 0, 2)) },
            clearLyricsCache = {},
            clearExtraCaches = { ExtraCacheClearResult(false, 30, 40, 1) },
            formatExtraResult = { formatted = it; "combined" }
        )
        assertEquals("combined", owner.clear(StorageCacheClearOptions(sharedMedia = true)))
        assertEquals(ExtraCacheClearResult(false, 100, 40, 3), formatted)
    }

    @Test
    fun audioOnlyResultIncludesActualFreedBytes() = runBlocking {
        var formatted: ExtraCacheClearResult? = null
        val owner = AppSettingsCacheClearOwner(
            clearPlayerCache = { PlayerCacheClearOutcome(ExtraCacheClearResult(true, 70, 0, 2)) },
            clearLyricsCache = { error("unexpected lyrics clear") },
            clearExtraCaches = { error("unexpected extra clear") },
            formatExtraResult = { formatted = it; "combined" }
        )
        owner.clear(StorageCacheClearOptions(imageCache = false))
        assertEquals(70L, formatted?.freedBytes)
    }

    @Test
    fun partialPlayerFailureStillClearsOtherSelectedCachesWithRetention() = runBlocking {
        val events = mutableListOf<String>()
        var formatted: ExtraCacheClearResult? = null
        val owner = AppSettingsCacheClearOwner(
            clearPlayerCache = { events += "player"; PlayerCacheClearOutcome(ExtraCacheClearResult(false, 70, 0, 2)) },
            clearLyricsCache = { events += "lyrics" },
            clearExtraCaches = { events += "extra"; ExtraCacheClearResult(true, 30, 40, 1) },
            formatExtraResult = { events += "format"; formatted = it; "combined" }
        )

        owner.clear(StorageCacheClearOptions(lyricsCache = true, otherCache = true, retainRecentSongs = 5))

        assertEquals(listOf("player", "lyrics", "extra", "format"), events)
        assertEquals(ExtraCacheClearResult(false, 100, 40, 3), formatted)
    }

    @Test
    fun unavailableRetentionStopsBeforeClearingOtherSelectedCaches() = runBlocking {
        val events = mutableListOf<String>()
        val failure = ExtraCacheClearResult(false, 0, 0, 0)
        var formatted: ExtraCacheClearResult? = null
        val owner = AppSettingsCacheClearOwner(
            clearPlayerCache = { events += "player"; PlayerCacheClearOutcome(failure, retentionUnavailable = true) },
            clearLyricsCache = { events += "lyrics" },
            clearExtraCaches = { events += "extra"; ExtraCacheClearResult(true, 30, 40, 1) },
            formatExtraResult = { events += "format"; formatted = it; "partial" }
        )

        assertEquals("partial", owner.clear(StorageCacheClearOptions(lyricsCache = true, retainRecentSongs = 5)))
        assertEquals(listOf("player", "format"), events)
        assertEquals(failure, formatted)
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

        assertEquals("partial · room:20:10", message(partial))
        assertEquals("room:20:10", message(reusable))
        assertEquals("complete:20", message(complete))
    }

    private fun owner(events: MutableList<String>) = AppSettingsCacheClearOwner(
        clearPlayerCache = { events += "player"; PlayerCacheClearOutcome(result(success = true, reusable = 0)) },
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
