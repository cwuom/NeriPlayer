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
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlayerManagerCachePrefetchPreparationTest {

    @After
    fun clearCacheReference() {
        PlayerManager.cache = null
        clearPlaybackCacheSafetyForTesting()
    }

    @Test
    fun `prefetch waits for damaged cache removal before it may write`() = runBlocking {
        val cacheKey = "damaged-cache"
        val damagedFile = temporaryFile(length = 3)
        val mediaCache = cacheWithDamagedSpanRemovedOnRequest(cacheKey, damagedFile)
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
        val mediaCache = cacheWithSpan(
            cacheKey = cacheKey,
            cachedFile = damagedFile,
            spanLength = 4L,
            contentLength = 4L
        )
        doThrow(IllegalStateException("cache write failed"))
            .`when`(mediaCache)
            .removeResource(cacheKey)
        PlayerManager.cache = mediaCache

        try {
            val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(cacheKey)

            assertEquals(CachePrefetchReadiness.UNAVAILABLE, readiness)
            assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
            verify(mediaCache).removeResource(cacheKey)
        } finally {
            damagedFile.delete()
        }
    }

    @Test
    fun `prefetch removes a complete resource marked unsafe before writing`() = runBlocking {
        val cacheKey = "unsafe-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpanRemovedOnRequest(
            cacheKey = cacheKey,
            cachedFile = cachedFile,
            spanLength = 1L,
            contentLength = 1L
        )
        PlayerManager.cache = mediaCache
        PlayerManager.markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)

        try {
            assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
            val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(cacheKey)

            assertEquals(CachePrefetchReadiness.READY_FOR_PREFETCH, readiness)
            verify(mediaCache).removeResource(cacheKey)
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `prefetch clears a persisted unsafe marker after an empty resource removal`() = runBlocking {
        val cacheKey = "unsafe-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(TreeSet())
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadataWithUnsafeMarker()
        )
        PlayerManager.cache = mediaCache

        val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(cacheKey)

        assertEquals(CachePrefetchReadiness.READY_FOR_PREFETCH, readiness)
        verify(mediaCache).removeResource(cacheKey)
        val mutations = org.mockito.ArgumentCaptor.forClass(ContentMetadataMutations::class.java)
        verify(mediaCache).applyContentMetadataMutations(
            org.mockito.ArgumentMatchers.eq(cacheKey),
            mutations.capture()
        )
        assertTrue(
            CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY in mutations.value.removedValues
        )
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

    @Test
    fun `prefetch does not repair a damaged cache after ownership is lost`() = runBlocking {
        val cacheKey = "active-playback-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpan(
            cacheKey = cacheKey,
            cachedFile = cachedFile,
            spanLength = 2L,
            contentLength = 2L
        )
        PlayerManager.cache = mediaCache

        try {
            val readiness = PlayerManager.prepareExoPlayerCacheForPrefetch(
                cacheKey = cacheKey,
                shouldApplyMutation = { false }
            )

            assertEquals(CachePrefetchReadiness.UNAVAILABLE, readiness)
            verify(mediaCache, never()).removeResource(cacheKey)
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `playback recovery bypasses a cache key when removal leaves spans behind`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpan(
            cacheKey = cacheKey,
            cachedFile = cachedFile,
            spanLength = 1L,
            contentLength = 1L
        )
        PlayerManager.cache = mediaCache

        try {
            val removed = PlayerManager.invalidateCachedResourceForPlaybackRecovery(
                cacheKey = cacheKey,
                reason = "test"
            )

            assertEquals(false, removed)
            assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
            verify(mediaCache).removeResource(cacheKey)
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `playback recovery does not clear metadata after ownership is lost`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(TreeSet())
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadataWithUnsafeMarker()
        )
        PlayerManager.cache = mediaCache
        var ownershipChecks = 0

        try {
            val removed = PlayerManager.invalidateCachedResourceForPlaybackRecovery(
                cacheKey = cacheKey,
                reason = "test",
                shouldApplyMutation = { ++ownershipChecks < 3 }
            )

            assertEquals(false, removed)
            verify(mediaCache).removeResource(cacheKey)
            verify(mediaCache, never()).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
            )
        } finally {
            cachedFile.delete()
        }
    }

    private fun cacheWithDamagedSpanRemovedOnRequest(
        cacheKey: String,
        damagedFile: File
    ): Cache {
        return cacheWithSpanRemovedOnRequest(
            cacheKey = cacheKey,
            cachedFile = damagedFile,
            spanLength = 4L,
            contentLength = 4L
        )
    }

    private fun cacheWithSpanRemovedOnRequest(
        cacheKey: String,
        cachedFile: File,
        spanLength: Long,
        contentLength: Long
    ): Cache {
        val mediaCache = mock(Cache::class.java)
        var spans = cachedSpans(cacheKey, cachedFile, spanLength)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenAnswer { spans }
        doAnswer {
            spans = TreeSet()
        }.`when`(mediaCache).removeResource(cacheKey)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = contentLength)
        )
        return mediaCache
    }

    private fun cacheWithSpan(
        cacheKey: String,
        cachedFile: File,
        spanLength: Long,
        contentLength: Long
    ): Cache {
        val mediaCache = mock(Cache::class.java)
        val spans = cachedSpans(cacheKey, cachedFile, spanLength)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(spans)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = contentLength)
        )
        return mediaCache
    }

    private fun cachedSpans(
        cacheKey: String,
        cachedFile: File,
        spanLength: Long
    ): TreeSet<CacheSpan> {
        return TreeSet<CacheSpan>().apply {
            add(CacheSpan(cacheKey, 0L, spanLength, 0L, cachedFile))
        }
    }

    private fun contentMetadata(length: Long): DefaultContentMetadata {
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, length)
        return DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations)
    }

    private fun contentMetadataWithUnsafeMarker(): DefaultContentMetadata {
        return DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
            ContentMetadataMutations().set(
                CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY,
                "1"
            )
        )
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
