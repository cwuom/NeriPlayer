package moe.ouom.neriplayer.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBlurStateTest {
    @Test
    fun `only the current image request can replace the stable cover`() {
        val state = NowPlayingBlurState()
        state.onImageSuccess("new", "old", "stale", 2f)
        assertNull(state.stableCoverUrl)
        state.onImageSuccess("new", "new", "current", 4f)
        assertEquals("current", state.stableCoverUrl)
        assertEquals(4f, state.stableBlurStrength)
        state.onImageError("new", "new")
        assertFalse(state.loadFailed)
    }

    @Test
    fun `missing first image falls back but a stale error does not`() {
        val state = NowPlayingBlurState()
        state.onImageError("new", "old")
        assertFalse(state.loadFailed)
        state.onImageError("new", "new")
        assertTrue(state.loadFailed)
        state.onImageSuccess("new", "new", "cover", 3f)
        assertFalse(state.loadFailed)
    }

    @Test
    fun `blur disable clears retained image immediately`() = runTest {
        val state = NowPlayingBlurState()
        state.onImageSuccess("key", "key", "cover", 4f)
        state.observeRequest("song", "cover")
        state.reconcileRetention(NowPlayingBlurRetentionRequest(false, true, "song", "cover"))
        assertNull(state.stableCoverUrl)
        assertNull(state.stableBlurStrength)
    }

    @Test
    fun `preference disable clears retained image on supported devices`() = runTest {
        val state = NowPlayingBlurState()
        state.onImageSuccess("key", "key", "cover", 4f)
        state.observeRequest("song", "cover")
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, false, "song", "cover"))
        assertNull(state.stableCoverUrl)
    }

    @Test
    fun `requested cover or replacement song keeps retained image`() = runTest {
        val state = NowPlayingBlurState()
        state.onImageSuccess("key", "key", "cover", 4f)
        state.observeRequest(null, "new")
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, true, null, "new"))
        assertEquals("cover", state.stableCoverUrl)
        state.observeRequest("replacement", null)
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, true, null, null))
        assertEquals("cover", state.stableCoverUrl)
    }

    @Test
    fun `retained cover clears after grace only when no replacement appeared`() = runTest {
        val state = NowPlayingBlurState()
        state.onImageSuccess("key", "key", "cover", 4f)
        state.observeRequest(null, "new-cover")
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, true, null, null))
        assertEquals("cover", state.stableCoverUrl)
        state.observeRequest(null, null)
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, true, null, null))
        assertNull(state.stableCoverUrl)
    }

    @Test
    fun `active song preserves cover while transient cover URL is missing`() = runTest {
        val state = NowPlayingBlurState()
        state.onImageSuccess("key", "key", "cover", 4f)
        state.observeRequest("song", null)
        state.reconcileRetention(NowPlayingBlurRetentionRequest(true, true, "song", null))
        assertEquals("cover", state.stableCoverUrl)
    }
}
