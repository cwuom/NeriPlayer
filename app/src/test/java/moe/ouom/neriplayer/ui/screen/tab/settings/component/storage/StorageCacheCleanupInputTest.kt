package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageCacheCleanupInputTest {
    @Test
    fun `retention slider preserves each fifty song stop`() {
        listOf(
            0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500,
            550, 600, 650, 700, 750, 800, 850, 900, 950, 1000
        ).forEach { songs ->
            assertEquals("must preserve $songs songs", songs, snapRetainedCacheSongs(songs.toFloat()))
        }
    }

    @Test
    fun `retention slider rounds halfway values toward the higher fifty song stop`() {
        listOf(
            24.9f to 0,
            25f to 50,
            25.1f to 50,
            74.9f to 50,
            75f to 100,
            75.1f to 100,
            974.9f to 950,
            975f to 1000,
            975.1f to 1000
        ).forEach { (value, expected) ->
            assertEquals("must round $value songs", expected, snapRetainedCacheSongs(value))
        }
    }

    @Test
    fun `retention slider clamps values outside zero to one thousand songs`() {
        listOf(
            Int.MIN_VALUE.toFloat() to 0,
            -20f to 0,
            -0.1f to 0,
            1000.1f to 1000,
            1025f to 1000,
            Int.MAX_VALUE.toFloat() to 1000
        ).forEach { (value, expected) ->
            assertEquals("must clamp $value songs", expected, snapRetainedCacheSongs(value))
        }
    }

    @Test
    fun `previous retention counts normalize to the nearest fifty song stop`() {
        listOf(
            10 to 0,
            20 to 0,
            30 to 50,
            40 to 50,
            230 to 250,
            770 to 750,
            990 to 1000
        ).forEach { (previous, expected) ->
            assertEquals("must normalize $previous songs", expected, snapRetainedCacheSongs(previous.toFloat()))
        }
    }
}
