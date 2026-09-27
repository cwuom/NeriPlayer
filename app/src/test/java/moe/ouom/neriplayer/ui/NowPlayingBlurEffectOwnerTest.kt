package moe.ouom.neriplayer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingBlurEffectOwnerTest {
    @Test
    fun `preload enqueues once per distinct blur request`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val queued = mutableListOf<String>()
        val request = NowPlayingBlurPreloadRequest(
            true, listOf("previous", "next"), 4f, 640, "revision", false
        )
        owner.updatePreload(request, queued::add)
        owner.updatePreload(request, queued::add)
        assertEquals(listOf("previous", "next"), queued)
        owner.updatePreload(request.copy(blurStrength = 8f), queued::add)
        assertEquals(listOf("previous", "next", "previous", "next"), queued)
    }

    @Test
    fun `retention cancels stale grace when a replacement song arrives`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        val missing = NowPlayingBlurRetentionRequest(true, true, null, null)
        owner.updateRetention(missing)
        runCurrent()
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, true, "new", null))
        runCurrent()
        advanceTimeBy(PLAYBACK_VISUAL_COVER_GRACE_MS)
        runCurrent()
        assertEquals("retained", state.stableCoverUrl)
    }

    @Test
    fun `retention preference change clears cover`() = runTest {
        val owner = NowPlayingBlurEffectOwner(this)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, false, "song", "cover"))
        runCurrent()
        assertNull(state.stableCoverUrl)
    }

    @Test
    fun `forgetting backdrop cancels pending cover clear`() = runTest {
        val owner = NowPlayingBlurEffectOwner(backgroundScope)
        val state = owner.blurState
        state.onImageSuccess("key", "key", "retained", 4f)
        owner.updateRetention(NowPlayingBlurRetentionRequest(true, true, null, null))
        runCurrent()
        owner.onForgotten()
        advanceTimeBy(PLAYBACK_VISUAL_COVER_GRACE_MS)
        runCurrent()
        assertEquals("retained", state.stableCoverUrl)
    }
}
