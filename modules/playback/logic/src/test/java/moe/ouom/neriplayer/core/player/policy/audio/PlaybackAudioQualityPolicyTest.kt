package moe.ouom.neriplayer.core.player.policy.audio

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackAudioQualityPolicyTest {

    @Test
    fun `quality merge only accepts a remote label or key for local playback`() {
        val local = PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL, sampleRateHz = 48_000)
        val remote = PlaybackAudioInfo(source = PlaybackAudioSource.NETEASE)
        assertNull(mergeLocalPlaybackAudioInfoWithRemoteQuality(null, remote))
        assertEquals(remote, mergeLocalPlaybackAudioInfoWithRemoteQuality(remote, local))
        assertEquals(local, mergeLocalPlaybackAudioInfoWithRemoteQuality(local, null))
        assertEquals(local, mergeLocalPlaybackAudioInfoWithRemoteQuality(local, remote))
        assertEquals(local, mergeLocalPlaybackAudioInfoWithRemoteQuality(local, remote.copy(qualityLabel = " ", qualityKey = "")))
        assertEquals("lossless", mergeLocalPlaybackAudioInfoWithRemoteQuality(local, remote.copy(qualityKey = "lossless"))?.qualityKey)
    }

    @Test
    fun mergeLocalPlaybackAudioInfoWithRemoteQuality_preservesDisplayedRemoteQuality() {
        val localAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            codecLabel = "FLAC",
            bitrateKbps = 1411,
            sampleRateHz = 48_000,
            bitDepth = 24
        )
        val previousRemoteAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = "lossless",
            qualityLabel = "无损",
            qualityOptions = listOf(PlaybackQualityOption("lossless", "无损"))
        )

        val merged = mergeLocalPlaybackAudioInfoWithRemoteQuality(
            localAudioInfo = localAudioInfo,
            previousAudioInfo = previousRemoteAudioInfo
        )

        assertEquals(PlaybackAudioSource.LOCAL, merged?.source)
        assertEquals("lossless", merged?.qualityKey)
        assertEquals("无损", merged?.qualityLabel)
        assertEquals(emptyList<PlaybackQualityOption>(), merged?.qualityOptions)
        assertEquals("FLAC", merged?.codecLabel)
        assertEquals(1411, merged?.bitrateKbps)
        assertEquals(48_000, merged?.sampleRateHz)
        assertEquals(24, merged?.bitDepth)
    }

    @Test
    fun mergeLocalPlaybackAudioInfoWithRemoteQuality_ignoresLocalPreviousInfo() {
        val localAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            codecLabel = "AAC",
            bitrateKbps = 256
        )
        val previousLocalAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            qualityKey = "lossless",
            qualityLabel = "无损"
        )

        val merged = mergeLocalPlaybackAudioInfoWithRemoteQuality(
            localAudioInfo = localAudioInfo,
            previousAudioInfo = previousLocalAudioInfo
        )

        assertEquals(localAudioInfo, merged)
    }

    @Test
    fun inferYouTubeQualityKeyFromBitrate_mapsThresholds() {
        assertEquals("very_high", inferYouTubeQualityKeyFromBitrate(192))
        assertEquals("high", inferYouTubeQualityKeyFromBitrate(128))
        assertEquals("medium", inferYouTubeQualityKeyFromBitrate(96))
        assertEquals("low", inferYouTubeQualityKeyFromBitrate(64))
        assertEquals("low", inferYouTubeQualityKeyFromBitrate(null))
    }
}
