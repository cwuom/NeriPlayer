package moe.ouom.neriplayer.core.api.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeNewPipeFallbackStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun putsTheNewestKeyFirst() {
        val retained = retainRecentNewPipeFallbackKeys(listOf("old"), "new")

        assertEquals(listOf("new", "old"), retained)
    }

    @Test
    fun neverLetsOneUrlTakeTwoSlots() {
        val retained = retainRecentNewPipeFallbackKeys(listOf("a", "b"), "b")

        // 同一版 player.js 反复标记时占两个位置, 几次刷新就能把有用的旧结论挤掉
        assertEquals(listOf("b", "a"), retained)
    }

    @Test
    fun dropsTheOldestOncePastTheCap() {
        val existing = (1..NEWPIPE_FALLBACK_MAX_ENTRIES).map { "url-$it" }

        val retained = retainRecentNewPipeFallbackKeys(existing, "fresh")

        assertEquals(NEWPIPE_FALLBACK_MAX_ENTRIES, retained.size)
        assertEquals("fresh", retained.first())
        assertTrue(retained.none { it == "url-$NEWPIPE_FALLBACK_MAX_ENTRIES" })
    }

    @Test
    fun ignoresABlankKey() {
        val retained = retainRecentNewPipeFallbackKeys(listOf("a"), "")

        assertEquals(listOf("a"), retained)
    }

    @Test
    fun roundTripsThroughTheSnapshot() {
        val snapshot = NewPipeFallbackSnapshot(
            signature = listOf("player-a"),
            throttling = listOf("player-b")
        )

        val restored = json.decodeFromString<NewPipeFallbackSnapshot>(json.encodeToString(snapshot))

        assertEquals(listOf("player-a"), restored.signature)
        assertEquals(listOf("player-b"), restored.throttling)
        assertEquals(NEWPIPE_FALLBACK_SNAPSHOT_VERSION, restored.version)
    }

    @Test
    fun toleratesFieldsAddedByALaterBuild() {
        val payload = """{"signature":["a"],"throttling":[],"version":1,"futureField":true}"""

        val restored = json.decodeFromString<NewPipeFallbackSnapshot>(payload)

        assertEquals(listOf("a"), restored.signature)
    }
}
