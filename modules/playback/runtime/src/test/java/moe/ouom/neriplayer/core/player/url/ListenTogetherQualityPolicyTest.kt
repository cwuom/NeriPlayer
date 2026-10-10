package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.ltw.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherQualityPolicyTest {

    @Test
    fun `stream candidate limits depend on the source`() {
        assertEquals(
            MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES,
            maxListenTogetherStreamUrlCandidates(PlaybackAudioSource.NETEASE)
        )
        assertEquals(2, maxListenTogetherStreamUrlCandidates(PlaybackAudioSource.BILIBILI))
        assertEquals(1, maxListenTogetherStreamUrlCandidates(PlaybackAudioSource.YOUTUBE_MUSIC))
        assertEquals(0, maxListenTogetherStreamUrlCandidates(PlaybackAudioSource.LOCAL))
    }

    @Test
    fun `bili order adds lossless after the default high tier`() {
        assertEquals(
            listOf("high", "lossless"),
            buildListenTogetherBiliQualityOrder("  ", setOf("medium", "lossless", "high"))
        )
    }

    @Test
    fun `bili order keeps an explicit preference ahead of high`() {
        assertEquals(
            listOf("dolby", "high"),
            buildListenTogetherBiliQualityOrder(" Dolby ", setOf("high", "dolby", "lossless"))
        )
    }

    @Test
    fun `bili order walks down when high is unavailable`() {
        assertEquals(listOf("medium", "low"), buildListenTogetherBiliQualityOrder("high", setOf("low", "medium")))
        assertEquals(listOf("low"), buildListenTogetherBiliQualityOrder("high", setOf("low")))
        assertEquals(emptyList<String>(), buildListenTogetherBiliQualityOrder("high", emptySet()))
    }

    @Test
    fun `bili order does not add lower tiers below an available high`() {
        assertEquals(listOf("high"), buildListenTogetherBiliQualityOrder("high", setOf("high", "low")))
    }
}
