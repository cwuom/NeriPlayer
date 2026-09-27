package moe.ouom.neriplayer.ui

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppLyricOffsetSettingsOwnerTest {
    @Test
    fun unchangedOffsetSkipsRebaseAndPersistence() = runBlocking {
        val events = mutableListOf<String>()
        val owner = owner(events)

        owner.changeCloudOffset(120, 120)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun failedOffsetSaveReversesTheRebase() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("save failed")
        val owner = owner(events, onSaveQq = { throw failure })

        assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.changeQqOffset(90, 130) }
        }
        assertEquals(
            listOf("QQ_MUSIC:90:130", "saveQq:130", "QQ_MUSIC:130:90"),
            events
        )
    }

    @Test
    fun failedResetRollsBackSourcesInReverseOrder() {
        val events = mutableListOf<String>()
        val owner = owner(events, onReset = { throw IllegalStateException("reset failed") })

        assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.resetCloudAndQqOffsets(80, 100) }
        }
        assertEquals(
            listOf(
                "CLOUD_MUSIC:80:$DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS",
                "QQ_MUSIC:100:$DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS",
                "reset",
                "QQ_MUSIC:$DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS:100",
                "CLOUD_MUSIC:$DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS:80"
            ),
            events
        )
    }

    @Test
    fun successfulCloudChangePersistsAfterRebase() = runBlocking {
        val events = mutableListOf<String>()
        owner(events).changeCloudOffset(50, 60)
        assertEquals(listOf("CLOUD_MUSIC:50:60", "saveCloud:60"), events)
    }

    private fun owner(
        events: MutableList<String>,
        onSaveQq: suspend (Long) -> Unit = {},
        onReset: suspend () -> Unit = {}
    ) = AppLyricOffsetSettingsOwner(
        rebase = { source: MusicPlatform, previous: Long, next: Long ->
            events += "${source.name}:$previous:$next"
        },
        saveCloudOffset = { events += "saveCloud:$it" },
        saveQqOffset = { events += "saveQq:$it"; onSaveQq(it) },
        resetOffsets = { events += "reset"; onReset() }
    )
}
