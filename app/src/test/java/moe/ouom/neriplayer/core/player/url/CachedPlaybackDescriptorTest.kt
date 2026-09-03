package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CachedPlaybackDescriptorTest {

    @After
    fun clearCacheReference() {
        PlayerManager.cache = null
        clearPlaybackCacheSafetyForTesting()
    }

    @Test
    fun `descriptor round trip restores actual quality and mime`() {
        val audioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "hires",
            qualityLabel = "hires",
            qualityOptions = listOf(
                PlaybackQualityOption("hires", "hires"),
                PlaybackQualityOption("high", "high")
            ),
            codecLabel = "FLAC",
            mimeType = "audio/flac",
            bitrateKbps = 1_024
        )
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = audioInfo,
            expectedContentLength = 4_268_241L,
            representationIdentity = "id-30251|hires|audio/flac|1024"
        )

        val decoded = decodeCachedPlaybackDescriptor(encodeCachedPlaybackDescriptor(descriptor))
        val restored = decoded?.toPlaybackAudioInfo { it.toString() }

        assertNotNull(decoded)
        assertEquals(PlaybackAudioSource.BILIBILI, restored?.source)
        assertEquals("hires", restored?.qualityKey)
        assertEquals("audio/flac", restored?.mimeType)
        assertEquals(2, restored?.qualityOptions?.size)
    }

    @Test
    fun `tampered descriptor is rejected`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.NETEASE,
                qualityKey = "hires",
                mimeType = "audio/flac"
            ),
            expectedContentLength = 100L,
            representationIdentity = "netease-hires"
        )
        val encoded = encodeCachedPlaybackDescriptor(descriptor)
            .replace("hires", "standard")

        assertNull(
            decodeCachedPlaybackDescriptor(encoded)?.toPlaybackAudioInfo { it.toString() }
        )
    }

    @Test
    fun `descriptor match distinguishes actual representation identity`() {
        val audioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "high",
            mimeType = "audio/mp4",
            bitrateKbps = 192
        )
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = audioInfo,
            expectedContentLength = null,
            representationIdentity = "id-30280|high|audio/mp4|192"
        )

        assertTrue(
            descriptor.matches(
                audioInfo = audioInfo,
                expectedContentLength = null,
                representationIdentity = "id-30280|high|audio/mp4|192"
            )
        )
        assertTrue(
            !descriptor.matches(
                audioInfo = audioInfo,
                expectedContentLength = null,
                representationIdentity = "id-30232|medium|audio/mp4|128"
            )
        )
    }

    @Test
    fun `descriptor with nullable fields survives round trip`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.NETEASE,
                qualityKey = "exhigh",
                mimeType = "audio/mp4"
            ),
            expectedContentLength = null,
            representationIdentity = null
        )

        val decoded = decodeCachedPlaybackDescriptor(encodeCachedPlaybackDescriptor(descriptor))

        assertNotNull(decoded?.toPlaybackAudioInfo { it.toString() })
        assertNull(decoded?.representationIdentity)
        assertNull(decoded?.codecLabel)
    }

    @Test
    fun `offline cache result preserves descriptor identity for replay`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.BILIBILI,
                qualityKey = "high",
                mimeType = "audio/mp4",
                bitrateKbps = 196
            ),
            expectedContentLength = 4_268_241L,
            representationIdentity = "30280|high|audio/mp4|196"
        )
        val decoded = decodeCachedPlaybackDescriptor(encodeCachedPlaybackDescriptor(descriptor))
        val decodedDescriptor = decoded ?: error("descriptor did not decode")
        val restored = decodedDescriptor.toPlaybackAudioInfo { it.toString() }

        assertEquals(4_268_241L, decodedDescriptor.expectedContentLength)
        assertEquals("30280|high|audio/mp4|196", decodedDescriptor.representationIdentity)
        assertTrue(
            decodedDescriptor.matches(
                audioInfo = restored ?: error("descriptor did not restore audio info"),
                expectedContentLength = decodedDescriptor.expectedContentLength,
                representationIdentity = decodedDescriptor.representationIdentity
            )
        )
    }

    @Test
    fun `descriptor rejects a different cached content length`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.BILIBILI,
                qualityKey = "high",
                mimeType = "audio/mp4"
            ),
            expectedContentLength = 4_268_241L
        )

        assertTrue(descriptor.matchesCachedContentLength(4_268_241L))
        assertTrue(!descriptor.matchesCachedContentLength(2_506_865L))
    }

    @Test
    fun `descriptor rejects a current representation with missing identity`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.YOUTUBE_MUSIC,
                qualityKey = "high",
                mimeType = "audio/mp4",
                bitrateKbps = 128,
                sampleRateHz = 44_100
            ),
            expectedContentLength = 3_606_154L,
            representationIdentity = "140|audio/mp4|128|44100|DIRECT"
        )

        assertFalse(
            descriptor.matches(
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.YOUTUBE_MUSIC,
                    qualityKey = "high",
                    mimeType = "audio/mp4",
                    bitrateKbps = 128,
                    sampleRateHz = 44_100
                ),
                expectedContentLength = 3_606_154L,
                representationIdentity = null
            )
        )
    }

    @Test
    fun `descriptor match allows a current representation with missing content length`() {
        val audioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "high",
            mimeType = "audio/mp4",
            bitrateKbps = 192
        )
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = audioInfo,
            expectedContentLength = 2_000_000L,
            representationIdentity = "new-representation"
        )

        assertTrue(
            descriptor.matches(
                audioInfo = audioInfo,
                expectedContentLength = null,
                representationIdentity = "new-representation"
            )
        )
    }

    @Test
    fun `descriptor match allows a cached representation with missing content length`() {
        val audioInfo = biliAudioInfo()
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = audioInfo,
            expectedContentLength = null,
            representationIdentity = "new-representation"
        )

        assertTrue(
            descriptor.matches(
                audioInfo = audioInfo,
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation"
            )
        )
    }

    @Test
    fun `descriptor match ignores available quality options`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = biliAudioInfo().copy(
                qualityOptions = listOf(PlaybackQualityOption("high", "high"))
            ),
            expectedContentLength = 2_000_000L,
            representationIdentity = "new-representation"
        )

        assertTrue(
            descriptor.matches(
                audioInfo = biliAudioInfo().copy(
                    qualityOptions = listOf(
                        PlaybackQualityOption("high", "high"),
                        PlaybackQualityOption("medium", "medium")
                    )
                ),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation"
            )
        )
    }

    @Test
    fun `only applied or metadata-free synchronization allows cache writes`() {
        assertTrue(CachedPlaybackDescriptorSynchronizationResult.APPLIED.allowsCustomCacheKey())
        assertTrue(CachedPlaybackDescriptorSynchronizationResult.NO_METADATA.allowsCustomCacheKey())
        assertFalse(CachedPlaybackDescriptorSynchronizationResult.SKIPPED.allowsCustomCacheKey())
        assertFalse(CachedPlaybackDescriptorSynchronizationResult.CACHE_UNUSABLE.allowsCustomCacheKey())
    }

    @Test
    fun `descriptor synchronization does not overwrite metadata when stale cache removal fails`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpan(cacheKey, cachedFile)
        doThrow(IllegalStateException("cache removal failed"))
            .`when`(mediaCache)
            .removeResource(cacheKey)
        PlayerManager.cache = mediaCache

        try {
            val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
                cacheKey = cacheKey,
                audioInfo = biliAudioInfo(),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation"
            )

            assertEquals(CachedPlaybackDescriptorSynchronizationResult.CACHE_UNUSABLE, result)
            assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
            assertNull(PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
            verify(mediaCache).removeResource(cacheKey)
            val mutations = ArgumentCaptor.forClass(ContentMetadataMutations::class.java)
            verify(mediaCache).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                mutations.capture()
            )
            assertEquals(
                "1",
                mutations.value.editedValues[CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY]
            )
            assertFalse(
                mutations.value.editedValues.containsKey(CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY)
            )
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `descriptor synchronization does not mutate cache after ownership is lost`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val mediaCache = cacheWithSpan(cacheKey, cachedFile)
        PlayerManager.cache = mediaCache

        try {
            PlayerManager.synchronizeCachedPlaybackDescriptor(
                cacheKey = cacheKey,
                audioInfo = biliAudioInfo(),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation",
                shouldApplyMutation = { false }
            )

            verify(mediaCache, never()).removeResource(cacheKey)
            verify(mediaCache, never()).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
            )
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `descriptor synchronization rechecks ownership before writing replacement metadata`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val initialSpans = cachedSpans(cacheKey, cachedFile)
        val emptySpans = TreeSet<CacheSpan>()
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(initialSpans, emptySpans)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = 1_000_000L)
        )
        var ownershipChecks = 0
        PlayerManager.cache = mediaCache

        try {
            val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
                cacheKey = cacheKey,
                audioInfo = biliAudioInfo(),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation",
                shouldApplyMutation = { ++ownershipChecks < 3 }
            )

            assertEquals(CachedPlaybackDescriptorSynchronizationResult.SKIPPED, result)
            verify(mediaCache).removeResource(cacheKey)
            verify(mediaCache, never()).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
            )
        } finally {
            cachedFile.delete()
        }
    }

    @Test
    fun `youtube representation identity distinguishes matching formats with different itags`() {
        val first = YouTubePlayableAudio(
            url = "https://googlevideo.example/videoplayback?itag=140",
            mimeType = "audio/mp4",
            bitrateKbps = 128,
            sampleRateHz = 44_100,
            streamType = YouTubePlayableStreamType.DIRECT
        )
        val second = first.copy(
            url = "https://googlevideo.example/videoplayback?itag=141"
        )

        assertFalse(buildYouTubeRepresentationIdentity(first) == buildYouTubeRepresentationIdentity(second))
    }

    @Test
    fun `persisted unsafe cache key is reloaded after cache recreation`() = runBlocking {
        val cacheKey = "stale-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(
                customValues = mapOf(
                    CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY to "1"
                )
            )
        )
        PlayerManager.cache = mediaCache

        assertTrue(PlayerManager.loadPlaybackCacheKeySafety(cacheKey))
        assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
        assertNull(PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
        verify(mediaCache, never()).applyContentMetadataMutations(
            org.mockito.ArgumentMatchers.eq(cacheKey),
            org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
        )
    }

    @Test
    fun `custom cache key remains available when no cache is initialized`() {
        PlayerManager.cache = null
        assertEquals("remote-cache", PlayerManager.safeCustomPlaybackCacheKey("remote-cache"))
    }

    @Test
    fun `custom cache key only checks the in memory safety state`() {
        val cacheKey = "remote-cache"
        val mediaCache = mock(Cache::class.java)
        PlayerManager.cache = mediaCache

        assertEquals(cacheKey, PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
        verify(mediaCache, never()).getContentMetadata(cacheKey)
    }

    @Test
    fun `synchronization keeps a safe custom cache key without audio metadata`() = runBlocking {
        val cacheKey = "listen-together-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(DefaultContentMetadata.EMPTY)
        PlayerManager.cache = mediaCache

        val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
            cacheKey = cacheKey,
            audioInfo = null,
            expectedContentLength = null,
            representationIdentity = null
        )

        assertEquals(CachedPlaybackDescriptorSynchronizationResult.NO_METADATA, result)
        assertTrue(result.allowsCustomCacheKey())
        assertEquals(cacheKey, PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
        verify(mediaCache, never()).applyContentMetadataMutations(
            org.mockito.ArgumentMatchers.eq(cacheKey),
            org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
        )
    }

    @Test
    fun `synchronization leaves an unsafe marker untouched without audio metadata`() = runBlocking {
        val cacheKey = "stale-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(TreeSet())
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(
                customValues = mapOf(
                    CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY to "1"
                )
            )
        )
        PlayerManager.cache = mediaCache

        val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
            cacheKey = cacheKey,
            audioInfo = null,
            expectedContentLength = 1L,
            representationIdentity = null
        )

        assertEquals(CachedPlaybackDescriptorSynchronizationResult.NO_METADATA, result)
        assertTrue(result.allowsCustomCacheKey())
        assertNull(PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
        verify(mediaCache, never()).applyContentMetadataMutations(
            org.mockito.ArgumentMatchers.eq(cacheKey),
            org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
        )
    }

    @Test
    fun `unsafe cache key is cleared after a successful replacement`() = runBlocking {
        val cacheKey = "stale-cache"
        val staleFile = temporaryFile(length = 1)
        val initialSpans = cachedSpans(cacheKey, staleFile)
        val emptySpans = TreeSet<CacheSpan>()
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(initialSpans, emptySpans)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = 1_000_000L)
        )
        PlayerManager.cache = mediaCache
        PlayerManager.markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)

        try {
            val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
                cacheKey = cacheKey,
                audioInfo = biliAudioInfo(),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation"
            )

            assertEquals(CachedPlaybackDescriptorSynchronizationResult.APPLIED, result)
            assertFalse(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
            assertEquals(cacheKey, PlayerManager.safeCustomPlaybackCacheKey(cacheKey))
            verify(mediaCache).removeResource(cacheKey)
            val mutations = ArgumentCaptor.forClass(ContentMetadataMutations::class.java)
            verify(mediaCache, times(2)).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                mutations.capture()
            )
            assertTrue(
                mutations.allValues.any { mutation ->
                    CACHED_PLAYBACK_DESCRIPTOR_METADATA_KEY in mutation.editedValues &&
                        CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY in mutation.removedValues
                }
            )
        } finally {
            staleFile.delete()
        }
    }

    @Test
    fun `descriptor synchronization does not mutate a cache replaced during inspection`() = runBlocking {
        val cacheKey = "stale-cache"
        val cachedFile = temporaryFile(length = 1)
        val replacementCache = mock(Cache::class.java)
        val staleCache = mock(Cache::class.java)
        `when`(staleCache.getCachedSpans(cacheKey)).thenAnswer {
            PlayerManager.cache = replacementCache
            cachedSpans(cacheKey, cachedFile)
        }
        `when`(staleCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = 1_000_000L)
        )
        PlayerManager.cache = staleCache

        try {
            val result = PlayerManager.synchronizeCachedPlaybackDescriptor(
                cacheKey = cacheKey,
                audioInfo = biliAudioInfo(),
                expectedContentLength = 2_000_000L,
                representationIdentity = "new-representation"
            )

            assertEquals(CachedPlaybackDescriptorSynchronizationResult.SKIPPED, result)
            verify(staleCache, never()).removeResource(cacheKey)
            verify(staleCache, never()).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
            )
            verify(replacementCache, never()).applyContentMetadataMutations(
                org.mockito.ArgumentMatchers.eq(cacheKey),
                org.mockito.ArgumentMatchers.any(ContentMetadataMutations::class.java)
            )
        } finally {
            cachedFile.delete()
        }
    }

    private fun biliAudioInfo() = PlaybackAudioInfo(
        source = PlaybackAudioSource.BILIBILI,
        qualityKey = "high",
        mimeType = "audio/mp4",
        bitrateKbps = 192
    )

    private fun cacheWithSpan(cacheKey: String, cachedFile: File): Cache {
        val mediaCache = mock(Cache::class.java)
        val spans = cachedSpans(cacheKey, cachedFile)
        `when`(mediaCache.getCachedSpans(cacheKey)).thenReturn(spans)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            contentMetadata(length = 1_000_000L)
        )
        return mediaCache
    }

    private fun cachedSpans(cacheKey: String, cachedFile: File): TreeSet<CacheSpan> {
        return TreeSet<CacheSpan>().apply {
            add(CacheSpan(cacheKey, 0L, 1L, 0L, cachedFile))
        }
    }

    private fun contentMetadata(
        length: Long = 0L,
        customValues: Map<String, String> = emptyMap()
    ): DefaultContentMetadata {
        val mutations = ContentMetadataMutations()
        if (length > 0L) {
            ContentMetadataMutations.setContentLength(mutations, length)
        }
        customValues.forEach { (key, value) ->
            mutations.set(key, value)
        }
        return DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations)
    }

    private fun temporaryFile(length: Int): File {
        return File.createTempFile("neriplayer-descriptor-cache", ".exo").also { file ->
            file.outputStream().use { output ->
                output.write(ByteArray(length))
            }
        }
    }
}
