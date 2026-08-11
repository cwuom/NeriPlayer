@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.PlayerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlayerManagerCachePrefetchPreparationTest {

    @After
    fun clearCacheReference() {
        PlayerManager.cache = null
    }

    @Test
    fun `prefetch waits for damaged cache removal before it may write`() = runBlocking {
        val cacheKey = "damaged-cache"
        val damagedFile = temporaryFile(length = 3)
        val mediaCache = cacheWithDamagedSpan(cacheKey, damagedFile)
        PlayerManager.cache = mediaCache

        try {
            val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(cacheKey)

            assertEquals(CachePrefetchReadiness.READY_FOR_PREFETCH, readiness)
            verify(mediaCache).removeResource(cacheKey)
        } finally {
            damagedFile.delete()
        }
    }

    @Test
    fun `prefetch is skipped when damaged cache removal fails`() = runBlocking {
        val cacheKey = "damaged-cache"
        val damagedFile = temporaryFile(length = 3)
        val mediaCache = cacheWithDamagedSpan(cacheKey, damagedFile)
        doThrow(IllegalStateException("cache write failed"))
            .`when`(mediaCache)
            .removeResource(cacheKey)
        PlayerManager.cache = mediaCache

        try {
            val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(cacheKey)

            assertEquals(CachePrefetchReadiness.UNAVAILABLE, readiness)
            verify(mediaCache).removeResource(cacheKey)
        } finally {
            damagedFile.delete()
        }
    }

    @Test
    fun `prefetch does not discard a mismatched resource after playback takes ownership`() = runBlocking {
        val cacheKey = "active-playback-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpan(
            cacheKey = cacheKey,
            cachedFile = cachedFile,
            spanLength = 1L,
            contentLength = 1_000_000L
        )
        PlayerManager.cache = mediaCache

        try {
            PlayerManager.invalidateMismatchedCachedResource(
                cacheKey = cacheKey,
                expectedContentLength = 50_000_000L,
                shouldApplyMutation = { false }
            )

            verify(mediaCache, never()).removeResource(cacheKey)
        } finally {
            cachedFile.delete()
        }
    }

    private fun cacheWithDamagedSpan(cacheKey: String, damagedFile: File): Cache {
        return cacheWithSpan(
            cacheKey = cacheKey,
            cachedFile = damagedFile,
            spanLength = 4L,
            contentLength = 4L
        )
    }

    private fun cacheWithSpan(
        cacheKey: String,
        cachedFile: File,
        spanLength: Long,
        contentLength: Long
    ): Cache {
        val mediaCache = mock(Cache::class.java)
        val spans = TreeSet<CacheSpan>().apply {
            add(CacheSpan(cacheKey, 0L, spanLength, 0L, cachedFile))
        }
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(spans)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = contentLength)
        )
        return mediaCache
    }

    private fun contentMetadata(length: Long): DefaultContentMetadata {
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, length)
        return DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations)
    }

    private fun temporaryFile(length: Int): File {
        return File.createTempFile("neriplayer-prefetch-cache", ".exo").also { file ->
            file.outputStream().use { output ->
                output.write(ByteArray(length))
            }
            assertTrue(file.isFile)
        }
    }
}
