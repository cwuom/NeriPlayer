package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.Cache.CacheException
import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.File
import java.nio.file.Files

class PlayerManagerMediaCacheLifecycleTest {

    @After
    fun clearCacheReference() {
        PlayerManager.cache = null
    }

    @Test
    fun `releasing media cache clears the reference before releasing it`() {
        val mediaCache = mock(Cache::class.java)
        doAnswer {
            assertFalse(PlayerManager.isCacheInitialized())
            null
        }.`when`(mediaCache).release()
        PlayerManager.cache = mediaCache

        PlayerManager.releaseMediaCache()

        assertFalse(PlayerManager.isCacheInitialized())
        verify(mediaCache).release()
    }

    @Test
    fun `only explicit cache initialization failures can rebuild the media cache`() {
        assertTrue(
            shouldRebuildMediaCacheAfterInitializationFailure(
                CacheException("broken cache index")
            )
        )
        assertTrue(
            shouldRebuildMediaCacheAfterInitializationFailure(
                IllegalStateException("wrapped", CacheException("broken cache index"))
            )
        )
        assertFalse(
            shouldRebuildMediaCacheAfterInitializationFailure(
                IllegalStateException("unrelated initialization failure")
            )
        )
        assertFalse(
            shouldRebuildMediaCacheAfterInitializationFailure(
                IllegalStateException(
                    "Another SimpleCache instance uses the folder",
                    CacheException("broken cache index")
                )
            )
        )
    }

    @Test
    fun `cache directory is reopened only after it is empty or gone`() {
        val parent = Files.createTempDirectory("neriplayer-media-cache-test").toFile()
        val cacheDir = File(parent, "media_cache")
        try {
            assertTrue(isMediaCacheDirectorySafeToOpen(cacheDir))

            assertTrue(cacheDir.mkdirs())
            assertTrue(isMediaCacheDirectorySafeToOpen(cacheDir))

            Files.createTempFile(cacheDir.toPath(), "orphan", ".exo")
            assertFalse(isMediaCacheDirectorySafeToOpen(cacheDir))
        } finally {
            parent.deleteRecursively()
        }
    }
}
