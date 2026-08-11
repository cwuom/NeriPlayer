package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.datasource.cache.Cache
import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

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
}
