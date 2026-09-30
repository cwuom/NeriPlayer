package moe.ouom.neriplayer.core.player.runtime.quality


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackQualityOwnerTest {
    @Test
    fun `quality picker compares preference rather than measured stream`() = runTest {
        val port = RecordingPort(PlaybackAudioSource.NETEASE)
        val owner = PlaybackQualityOwner(backgroundScope, port)
        owner.setPreferredQuality(PlaybackAudioSource.NETEASE, "lossless")

        owner.changeCurrentPlaybackQuality(" lossless ")
        runCurrent()
        assertTrue(port.persisted.isEmpty())

        owner.changeCurrentPlaybackQuality(" EXHIGH ")
        runCurrent()
        assertEquals(listOf(PlaybackAudioSource.NETEASE to "exhigh"), port.persisted)
    }

    @Test
    fun `one source refresh replaces its previous pending job`() = runTest {
        val port = RecordingPort(PlaybackAudioSource.BILIBILI)
        val owner = PlaybackQualityOwner(backgroundScope, port)

        owner.scheduleRefresh(PlaybackAudioSource.BILIBILI, "old")
        owner.scheduleRefresh(PlaybackAudioSource.BILIBILI, "latest")
        owner.scheduleRefresh(PlaybackAudioSource.LOCAL, "ignored")
        runCurrent()

        assertEquals(listOf(PlaybackAudioSource.BILIBILI to "latest"), port.refreshed)
    }

    @Test
    fun `preferred keys retain independent platform values`() = runTest {
        val owner = PlaybackQualityOwner(backgroundScope, RecordingPort(null))
        owner.setPreferredQuality(PlaybackAudioSource.NETEASE, "hires")
        owner.setPreferredQuality(PlaybackAudioSource.YOUTUBE_MUSIC, "medium")

        assertEquals("hires", owner.preferredKeys.value.netease)
        assertEquals("medium", owner.preferredKeys.value.youtube)
        assertEquals("high", owner.preferredKeys.value.bili)
    }

    private class RecordingPort(private val source: PlaybackAudioSource?) : PlaybackQualityPort {
        val persisted = mutableListOf<Pair<PlaybackAudioSource, String>>()
        val refreshed = mutableListOf<Pair<PlaybackAudioSource, String>>()
        override fun currentAudioSource(): PlaybackAudioSource? = source
        override suspend fun persistPreferredQuality(source: PlaybackAudioSource, key: String) {
            persisted += source to key
        }
        override suspend fun refreshCurrentSong(source: PlaybackAudioSource, reason: String) {
            refreshed += source to reason
        }
    }
}
