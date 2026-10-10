package moe.ouom.neriplayer.platform.youtube.api.challenge

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class YouTubePlayerScriptStoreFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val playerUrl = "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js"
    private val script = "var player = {};".padEnd(PLAYER_SCRIPT_MIN_LENGTH, ' ')

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `scripts round trip through the cache directory`() {
        val store = YouTubePlayerScriptStore(context)

        assertNull(store.read(playerUrl))
        store.write(playerUrl, script)

        assertEquals(script, YouTubePlayerScriptStore(context).read("  $playerUrl  "))
        assertTrue(cacheFile(playerUrl).exists())
        assertFalse(File(cacheFile(playerUrl).path + ".tmp").exists())
    }

    @Test
    fun `truncated scripts are neither written nor trusted`() {
        val store = YouTubePlayerScriptStore(context)

        store.write(playerUrl, "short")
        assertFalse(cacheFile(playerUrl).exists())

        cacheFile(playerUrl).parentFile!!.mkdirs()
        cacheFile(playerUrl).writeText("truncated")
        assertNull(store.read(playerUrl))
        assertFalse(cacheFile(playerUrl).exists())
    }

    @Test
    fun `writing prunes expired scripts but keeps other files`() {
        val store = YouTubePlayerScriptStore(context)
        val staleUrl = "https://www.youtube.com/s/player/stale/base.js"
        store.write(staleUrl, script)
        cacheFile(staleUrl).setLastModified(System.currentTimeMillis() - PLAYER_SCRIPT_CACHE_MAX_AGE_MS - 60_000L)
        val notes = File(cacheFile(staleUrl).parentFile, "notes.txt").apply { writeText("keep") }

        store.write(playerUrl, script)

        assertFalse(cacheFile(staleUrl).exists())
        assertTrue(cacheFile(playerUrl).exists())
        assertTrue(notes.exists())
    }

    @Test
    fun `unreadable or unwritable cache entries are skipped`() {
        val store = YouTubePlayerScriptStore(context)
        File(cacheFile(playerUrl), "blocker").apply {
            parentFile!!.mkdirs()
            writeText("occupies the cache path")
        }

        store.write(playerUrl, script)

        assertTrue(cacheFile(playerUrl).isDirectory)
        assertNull(store.read(playerUrl))
    }

    private fun cacheFile(url: String): File =
        File(temporaryFolder.root, "youtube/player_js/${youTubePlayerScriptCacheKey(url)}.js")
}
