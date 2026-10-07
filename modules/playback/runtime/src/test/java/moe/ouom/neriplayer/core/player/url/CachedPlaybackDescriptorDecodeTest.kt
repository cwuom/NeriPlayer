package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class CachedPlaybackDescriptorDecodeTest {

    @After
    fun clearCacheReference() {
        PlayerManager.cache = null
        clearPlaybackCacheSafetyForTesting()
    }

    @Test
    fun `blank or malformed descriptors decode to null`() {
        assertNull(decodeCachedPlaybackDescriptor(null))
        assertNull(decodeCachedPlaybackDescriptor("  "))
        assertNull(decodeCachedPlaybackDescriptor("{not json"))
        assertNull(decodeCachedPlaybackDescriptor("""{"source":"SPOTIFY"}"""))
        assertNull(decodeCachedPlaybackDescriptor("""{"version":2}"""))
    }

    @Test
    fun `optional descriptor fields treat null, zero and blank values as absent`() {
        val decoded = decodeCachedPlaybackDescriptor(
            """
            {"source":"NETEASE","qualityKey":" lossless ","mimeType":null,"codecLabel":"  ",
             "bitrateKbps":0,"sampleRateHz":null,"bitDepth":24,
             "qualityOptionKeys":[" exhigh ","","lossless"],
             "expectedContentLength":0,"representationFingerprint":"fp"}
            """.trimIndent()
        )!!

        assertEquals(0, decoded.version)
        assertEquals(PlaybackAudioSource.NETEASE, decoded.source)
        assertEquals("lossless", decoded.qualityKey)
        assertNull(decoded.mimeType)
        assertNull(decoded.codecLabel)
        assertNull(decoded.bitrateKbps)
        assertNull(decoded.sampleRateHz)
        assertEquals(24, decoded.bitDepth)
        assertNull(decoded.channelCount)
        assertEquals(listOf("exhigh", "lossless"), decoded.qualityOptionKeys)
        assertNull(decoded.expectedContentLength)
        assertNull(decoded.representationIdentity)
        assertEquals("fp", decoded.representationFingerprint)
    }

    @Test
    fun `content length is kept only when it is positive`() {
        fun contentLength(raw: String) = decodeCachedPlaybackDescriptor(
            """{"source":"BILIBILI","version":2$raw}"""
        )!!.expectedContentLength

        assertEquals(4_096L, contentLength(""","expectedContentLength":4096"""))
        assertNull(contentLength(""","expectedContentLength":null"""))
        assertNull(contentLength(""))
        assertEquals(
            emptyList<String>(),
            decodeCachedPlaybackDescriptor("""{"source":"LOCAL"}""")!!.qualityOptionKeys
        )
    }

    @Test
    fun `clearing an unsafe key needs a non blank key and the active cache`() {
        val mediaCache = mock(Cache::class.java)

        assertTrue(PlayerManager.clearPlaybackCacheKeyUnsafe(mediaCache, " "))
        PlayerManager.cache = mock(Cache::class.java)
        assertFalse(PlayerManager.clearPlaybackCacheKeyUnsafe(mediaCache, "stale-cache"))
        verifyNoInteractions(mediaCache)
    }

    @Test
    fun `clearing an unsafe key removes the persisted marker`() {
        val cacheKey = "stale-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenReturn(
            DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
                ContentMetadataMutations().set(CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY, "1")
            )
        )
        PlayerManager.cache = mediaCache
        PlayerManager.markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)

        assertTrue(PlayerManager.clearPlaybackCacheKeyUnsafe(mediaCache, cacheKey))

        assertFalse(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
        val mutations = ArgumentCaptor.forClass(ContentMetadataMutations::class.java)
        verify(mediaCache, org.mockito.Mockito.times(2))
            .applyContentMetadataMutations(eq(cacheKey), mutations.capture())
        assertEquals(listOf(CACHED_PLAYBACK_CACHE_UNSAFE_METADATA_KEY), mutations.allValues.last().removedValues)
    }

    @Test
    fun `failed marker removal keeps the key unsafe`() {
        val cacheKey = "stale-cache"
        val mediaCache = mock(Cache::class.java)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenThrow(IllegalStateException("cache released"))
        PlayerManager.cache = mediaCache

        assertFalse(PlayerManager.clearPlaybackCacheKeyUnsafe(mediaCache, cacheKey))

        assertTrue(PlayerManager.isPlaybackCacheKeyUnsafe(cacheKey))
    }

    @Test
    fun `clearing an unsafe key fails when the cache is replaced meanwhile`() {
        val cacheKey = "stale-cache"
        val mediaCache = mock(Cache::class.java)
        val replacementCache = mock(Cache::class.java)
        `when`(mediaCache.getContentMetadata(cacheKey)).thenAnswer {
            PlayerManager.cache = replacementCache
            DefaultContentMetadata.EMPTY
        }
        PlayerManager.cache = mediaCache

        assertFalse(PlayerManager.clearPlaybackCacheKeyUnsafe(mediaCache, cacheKey))

        verify(mediaCache, never()).applyContentMetadataMutations(eq(cacheKey), any(ContentMetadataMutations::class.java))
        verifyNoInteractions(replacementCache)
    }
}
