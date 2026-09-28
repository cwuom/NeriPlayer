package moe.ouom.neriplayer.core.api.bili

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliPlaybackRepositoryTest {
    private val low = BiliAudioStreamInfo(1, "audio/mp4", 64, null, "https://example.test/low")
    private val high = BiliAudioStreamInfo(2, "audio/mp4", 192, null, "https://example.test/high")
    private val source = object : BiliAudioDataSource {
        override val client: BiliClient get() = error("repository must use the data source")
        override suspend fun fetchAudioStreams(bvid: String, cid: Long) = listOf(low, high)
    }

    @Test
    fun readsCurrentPreferenceForEachRequest() = runTest {
        var quality = "low"
        val repository = BiliPlaybackRepository(source) { quality }
        assertEquals(low, repository.getBestPlayableAudio("video", 1))
        quality = "high"
        assertEquals(high, repository.getAudioWithDecision("video", 1).second)
    }

    @Test
    fun explicitOverrideDoesNotReadPreferences() = runTest {
        val repository = BiliPlaybackRepository(source) { error("override must take precedence") }
        assertEquals(low, repository.getBestPlayableAudio("video", 1, "low"))
        assertEquals(high, repository.getAudioWithDecision("video", 1, "high").second)
    }
}
