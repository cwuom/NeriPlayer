package moe.ouom.neriplayer.platform.youtube.api.fallback

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.data.model.youtube.cache.NewPipeFallbackSnapshot
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

class YouTubeNewPipeFallbackStoreFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val storeFile: File
        get() = File(temporaryFolder.root, "youtube/newpipe_fallback.json")

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `saved snapshots reload until the schema version changes`() {
        val store = YouTubeNewPipeFallbackStore(context)
        val snapshot = NewPipeFallbackSnapshot(signature = listOf("player-a"), throttling = listOf("player-b"))

        assertNull(store.load())
        store.save(snapshot)

        assertEquals(snapshot, YouTubeNewPipeFallbackStore(context).load())

        storeFile.writeText("""{"signature":["old"],"throttling":[],"version":0}""")
        assertNull(store.load())
        assertTrue(storeFile.exists())
    }

    @Test
    fun `corrupt snapshots are dropped from disk`() {
        storeFile.parentFile!!.mkdirs()
        storeFile.writeText("{broken")

        assertNull(YouTubeNewPipeFallbackStore(context).load())
        assertFalse(storeFile.exists())
    }

    @Test
    fun `save failures are swallowed`() {
        File(temporaryFolder.root, "youtube").writeText("not a directory")
        val store = YouTubeNewPipeFallbackStore(context)

        store.save(NewPipeFallbackSnapshot(signature = listOf("player-a")))

        assertNull(store.load())
    }

    @Test
    fun `retention drops blank keys and honours non-positive caps`() {
        assertEquals(emptyList<String>(), retainRecentNewPipeFallbackKeys(listOf("a", "b"), "c", maxEntries = 0))
        assertEquals(emptyList<String>(), retainRecentNewPipeFallbackKeys(listOf("a"), " ", maxEntries = -1))
        assertEquals(listOf("c", "a", "b"), retainRecentNewPipeFallbackKeys(listOf("a", " ", "c", "b"), "c"))
        assertEquals(listOf("c", "a"), retainRecentNewPipeFallbackKeys(listOf("a", "b"), "c", maxEntries = 2))
    }
}
