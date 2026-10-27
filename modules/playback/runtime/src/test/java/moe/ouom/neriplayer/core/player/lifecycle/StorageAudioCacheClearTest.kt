package moe.ouom.neriplayer.core.player.lifecycle

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageAudioCacheClearTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `audio estimate follows recent song retention without removing cache spans`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        store(cache, "netease-12-lossless", 11)
        store(cache, "netease-13-exhigh", 13)
        store(cache, "netease-14-exhigh", 17)
        val recent = listOf(song(12), song(12), song(13))
        val keys = cache.keys.toSet()

        assertEquals(48L, estimateAudioStorageCache(cache, emptyList(), emptySet(), false))
        assertEquals(30L, estimateAudioStorageCache(cache, selectRetainedCacheSongs(recent, 1), emptySet(), true))
        assertEquals(17L, estimateAudioStorageCache(cache, selectRetainedCacheSongs(recent, 2), emptySet(), true))
        assertEquals(keys, cache.keys)
        assertEquals(48L, cache.cacheSpace)
    }

    @Test
    fun `audio estimate protects current resources and unmapped legacy data`() = withCache { cache ->
        store(cache, "playing", 7)
        store(cache, "legacy-unknown", 11)
        store(cache, "bili-auto-legacy-9-high", 13)
        store(cache, "bili-auto-known-9-high", 17)
        cache.recordStorageCacheOwner("bili-auto-known-9-high", song(14))

        assertEquals(41L, estimateAudioStorageCache(cache, emptyList(), setOf("playing"), false))
        assertEquals(17L, estimateAudioStorageCache(cache, listOf(song(12)), setOf("playing"), true))
        assertEquals(4, cache.keys.size)
    }

    @Test
    fun `audio estimate is unavailable when span inspection fails`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        val observed = spy(cache)
        doAnswer { throw java.io.IOException("cannot inspect cache") }
            .`when`(observed).getCachedSpans("netease-12-exhigh")

        assertNull(estimateAudioStorageCache(observed, emptyList(), emptySet(), false))
        assertEquals(7L, cache.cacheSpace)
    }

    @Test
    fun `audio estimate propagates cancellation without removing resources`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        val observed = spy(cache)
        doAnswer { throw CancellationException("cancel estimate") }
            .`when`(observed).getCachedSpans("netease-12-exhigh")

        val failure = runCatching {
            estimateAudioStorageCache(observed, emptyList(), emptySet(), false)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(7L, cache.cacheSpace)
    }

    @Test
    fun `all qualities of retained song survive while other songs are removed`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        store(cache, "netease-12-lossless-fallback-v1", 11)
        store(cache, "netease-preview-v1-12-standard", 13)
        store(cache, "netease-123-exhigh", 17)

        val result = clearAudioStorageCache(cache, listOf(song(12)), emptySet(), true)

        assertTrue(result.success)
        assertEquals(17L, result.freedBytes)
        assertEquals(31L, cache.cacheSpace)
        assertEquals(3, cache.keys.size)
    }

    @Test
    fun `zero retention clears unknown keys but protects current resource`() = withCache { cache ->
        store(cache, "old-unknown", 7)
        store(cache, "playing", 11)

        val result = clearAudioStorageCache(cache, emptyList(), setOf("playing"), false)

        assertEquals(7L, result.freedBytes)
        assertEquals(setOf("playing"), cache.keys)
    }

    @Test
    fun `unmapped legacy resources are protected when retaining recent songs`() = withCache { cache ->
        store(cache, "bili-auto-BVold-9-high", 19)
        store(cache, "netease-123-exhigh", 17)

        val result = clearAudioStorageCache(cache, listOf(song(12)), emptySet(), true)

        assertEquals(17L, result.freedBytes)
        assertEquals(setOf("bili-auto-BVold-9-high"), cache.keys)
    }

    @Test
    fun `recent songs use newest distinct identities before applying limit`() {
        val selected = selectRetainedCacheSongs(listOf(song(12), song(12), song(13)), 2)
        assertEquals(listOf(12L, 13L), selected.map { it.id })
        assertTrue(selectRetainedCacheSongs(listOf(song(12)), 0).isEmpty())
    }

    @Test
    fun `source fallback caches honor persisted song owners`() = withCache { cache ->
        store(cache, "bili-auto-BVone-9-high", 19)
        store(cache, "bili-auto-BVtwo-9-high", 17)
        cache.recordStorageCacheOwner("bili-auto-BVone-9-high", song(12))
        cache.recordStorageCacheOwner("bili-auto-BVtwo-9-high", song(13))

        val result = clearAudioStorageCache(cache, listOf(song(12)), emptySet(), true)

        assertEquals(17L, result.freedBytes)
        assertEquals(setOf("bili-auto-BVone-9-high"), cache.keys)
    }

    @Test
    fun `local song does not protect an unrelated cloud song with the same number`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        val result = clearAudioStorageCache(cache, listOf(song(12).copy(mediaUri = "content://media/external/audio/12")), emptySet(), true)
        assertEquals(7L, result.freedBytes)
        assertTrue(cache.keys.isEmpty())
    }

    @Test
    fun `cancellation after first removal preserves remaining resources`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        store(cache, "netease-13-exhigh", 11)
        val clearingJob = Job()
        val observed = spy(cache)
        doAnswer { invocation ->
            invocation.callRealMethod().also { clearingJob.cancel() }
        }.`when`(observed).removeResource(org.mockito.ArgumentMatchers.anyString())
        val failure = runCatching {
            withContext(clearingJob) { clearAudioStorageCache(observed, emptyList(), emptySet(), false) }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, cache.keys.size)
    }

    @Test
    fun `playback change stops the remaining removal batch`() = withCache { cache ->
        store(cache, "netease-12-exhigh", 7)
        store(cache, "netease-13-exhigh", 11)
        var checks = 0
        val result = clearAudioStorageCache(cache, emptyList(), emptySet(), false, removalGate = { remove ->
            if (++checks == 1) { remove(); true } else false
        })
        assertEquals(1, cache.keys.size)
        assertTrue(!result.success)
        assertTrue(result.freedBytes in setOf(7L, 11L))
    }

    private fun withCache(block: suspend (SimpleCache) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), provider)
        try {
            block(cache)
        } finally {
            cache.release()
            provider.close()
        }
    }

    private fun store(cache: SimpleCache, key: String, bytes: Int) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.toLong()))
        try {
            val file: File = cache.startFile(key, 0, bytes.toLong())
            file.writeBytes(ByteArray(bytes))
            cache.commitFile(file, bytes.toLong())
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    private fun song(id: Long) = SongItem(id, "song", "artist", "album", 0, 1000, null)
}
