package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedPlaybackDescriptorTest {

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
    fun `legacy descriptor without representation identity remains readable`() {
        val descriptor = cachedPlaybackDescriptorFromAudioInfo(
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.NETEASE,
                qualityKey = "exhigh",
                mimeType = "audio/mp4"
            ),
            expectedContentLength = null,
            representationIdentity = null
        )

        assertNotNull(descriptor.toPlaybackAudioInfo { it.toString() })
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
}
