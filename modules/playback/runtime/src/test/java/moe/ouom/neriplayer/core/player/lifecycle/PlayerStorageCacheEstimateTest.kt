@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(coil.annotation.ExperimentalCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import android.app.Application
import androidx.media3.datasource.cache.Cache
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.storage.scan.statsOf
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayerStorageCacheEstimateTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `readable empty player caches have a known zero estimate`() = withPlayerCaches { _, disk, _, _ ->
        `when`(disk.size).thenReturn(0L)

        assertEquals(PlayerCacheClearEstimate(0L, 0L), PlayerManager.estimateStorageCacheClear(0))
    }

    @Test
    fun `missing audio cache instance is unavailable while existing files remain counted by storage scan`() =
        withPlayerCaches { _, _, _, _ ->
            val directory = File(PlayerManager.application.cacheDir, "media_cache").apply { mkdirs() }
            val remainingFile = File(directory, "1.0.0.v3.exo").apply { writeBytes(ByteArray(4096)) }
            PlayerManager.cache = null

            assertEquals(4096L, statsOf(directory).sizeBytes)
            assertEquals(PlayerCacheClearEstimate(null, 27L), PlayerManager.estimateStorageCacheClear(0))
            assertEquals(4096L, remainingFile.length())
        }

    @Test
    fun `missing image cache instance is unavailable while existing files remain counted by storage scan`() =
        withPlayerCaches { _, _, loader, _ ->
            val directory = File(PlayerManager.application.cacheDir, "image_cache").apply { mkdirs() }
            val remainingFile = File(directory, "legacy.1").apply { writeBytes(ByteArray(2048)) }
            `when`(loader.diskCache).thenReturn(null)

            assertEquals(2048L, statsOf(directory).sizeBytes)
            assertEquals(PlayerCacheClearEstimate(0L, null), PlayerManager.estimateStorageCacheClear(0))
            assertEquals(2048L, remainingFile.length())
        }

    @Test
    fun `missing cache instances do not claim known zero estimates`() = withPlayerCaches { _, _, loader, _ ->
        PlayerManager.cache = null
        `when`(loader.diskCache).thenReturn(null)

        assertEquals(PlayerCacheClearEstimate(null, null), PlayerManager.estimateStorageCacheClear(0))
    }

    @Test
    fun `an audio inspection failure stays unavailable while image estimate is preserved`() =
        withPlayerCaches { cache, _, _, _ ->
            doAnswer { throw IOException("audio cache unreadable") }.`when`(cache).keys

            assertEquals(PlayerCacheClearEstimate(null, 27L), PlayerManager.estimateStorageCacheClear(0))
        }

    @Test
    fun `an image inspection failure preserves the known audio estimate`() = withPlayerCaches { _, _, loader, _ ->
        doAnswer { throw IOException("image cache unreadable") }.`when`(loader).diskCache

        assertEquals(PlayerCacheClearEstimate(0L, null), PlayerManager.estimateStorageCacheClear(0))
    }

    @Test
    fun `unavailable retention history does not fall back to clearing every cache`() = withPlayerCaches { _, _, _, history ->
        `when`(history.awaitInitialized()).thenReturn(false)

        assertEquals(PlayerCacheClearEstimate(null, null), PlayerManager.estimateStorageCacheClear(10))
    }

    @Test
    fun `positive retention excludes an untracked legacy image cache after history loads`() =
        withPlayerCaches { _, _, _, history ->
            `when`(history.awaitInitialized()).thenReturn(true)
            `when`(history.historyFlow).thenReturn(MutableStateFlow(emptyList()))

            assertEquals(PlayerCacheClearEstimate(0L, 0L), PlayerManager.estimateStorageCacheClear(10))
        }

    @Test
    fun `a failed retention preflight leaves both estimates unavailable`() = withPlayerCaches { _, _, _, history ->
        doAnswer { throw IOException("history unreadable") }.`when`(history).awaitInitialized()

        assertEquals(PlayerCacheClearEstimate(null, null), PlayerManager.estimateStorageCacheClear(10))
    }

    @Test
    fun `cancellation from image inspection is propagated`() = withPlayerCaches { _, _, loader, _ ->
        doAnswer { throw CancellationException("cancel estimate") }.`when`(loader).diskCache

        val failure = runCatching { PlayerManager.estimateStorageCacheClear(0) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun withPlayerCaches(
        check: suspend (Cache, DiskCache, ImageLoader, PlayHistoryRepository) -> Unit
    ) = runTest {
        val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
        val playerField = PlayerManager::class.java.getDeclaredField("player").apply { isAccessible = true }
        val imageLoaderField = Coil::class.java.getDeclaredField("imageLoader").apply { isAccessible = true }
        val imageFactoryField = Coil::class.java.getDeclaredField("imageLoaderFactory").apply { isAccessible = true }
        val previousApplication = applicationField.get(null)
        val previousPlayer = playerField.get(null)
        val previousImageLoader = imageLoaderField.get(null)
        val previousImageFactory = imageFactoryField.get(null)
        val previousCache = PlayerManager.cache
        val previousSong = PlayerManager._currentSongFlow.value
        val previousUrl = PlayerManager._currentMediaUrl.value
        val cache = mock(Cache::class.java)
        val disk = mock(DiskCache::class.java)
        val loader = mock(ImageLoader::class.java)
        val history = mock(PlayHistoryRepository::class.java)
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        val application = mock(Application::class.java)
        `when`(application.cacheDir).thenReturn(temporary.newFolder("cache"))
        `when`(cache.keys).thenReturn(emptySet())
        `when`(disk.size).thenReturn(27L)
        `when`(loader.diskCache).thenReturn(disk)
        `when`(repositories.playHistoryRepo).thenReturn(history)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            PlayerTestEnvironment.install(repositories = repositories)
            PlayerManager.application = application
            playerField.set(null, null)
            PlayerManager.cache = cache
            PlayerManager._currentSongFlow.value = null
            PlayerManager._currentMediaUrl.value = null
            Coil.setImageLoader(loader)
            check(cache, disk, loader, history)
        } finally {
            PlayerManager.cache = previousCache
            PlayerManager._currentSongFlow.value = previousSong
            PlayerManager._currentMediaUrl.value = previousUrl
            applicationField.set(null, previousApplication)
            playerField.set(null, previousPlayer)
            imageLoaderField.set(null, previousImageLoader)
            imageFactoryField.set(null, previousImageFactory)
            PlayerTestEnvironment.reset()
            Dispatchers.resetMain()
        }
    }
}
