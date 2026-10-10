package moe.ouom.neriplayer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverResolutionFallbackPolicyTest {
    @Test
    fun `remote songs probe local candidates only without a usable immediate cover`() {
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = true, immediateCover = REMOTE_COVER))
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = false, immediateCover = null))
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = false, immediateCover = "   "))
        assertFalse(shouldProbeFastLocalCoverCandidate(isLocalSong = false, immediateCover = REMOTE_COVER))
    }

    @Test
    fun `plain http file uri and absolute paths are immediate cover candidates`() {
        assertEquals(
            "http://img.example.com/a.jpg",
            resolveImmediateCoverCandidate(" http://img.example.com/a.jpg ", null)
        )
        assertEquals(
            "FILE:///sdcard/Music/a.jpg",
            resolveImmediateCoverCandidate("FILE:///sdcard/Music/a.jpg", null)
        )
        assertEquals(
            "/sdcard/Music/cover.jpg",
            resolveImmediateCoverCandidate("/sdcard/Music/cover.jpg", null)
        )
    }

    @Test
    fun `relative names are skipped in favour of a usable fallback`() {
        assertEquals(REMOTE_COVER, resolveImmediateCoverCandidate("cover.jpg", REMOTE_COVER))
        assertNull(resolveImmediateCoverCandidate("cover.jpg", "folder.jpg"))
        assertNull(resolveImmediateCoverCandidate("   ", "   "))
    }

    @Test
    fun `media store album art is never an immediate candidate`() {
        assertEquals(
            "/sdcard/Music/cover.jpg",
            resolveImmediateCoverCandidate(
                "content://media/external/audio/albumart/42",
                "/sdcard/Music/cover.jpg"
            )
        )
    }

    @Test
    fun `pending resolution keeps the current cover until a non blank result arrives`() {
        assertEquals("current", retainCoverDuringResolution("current", null))
        assertEquals("current", retainCoverDuringResolution("current", "  "))
        assertEquals("resolved", retainCoverDuringResolution("current", "resolved"))
        assertNull(retainCoverDuringResolution(null, null))
    }

    @Test
    fun `unfinished resolution never clears the current cover`() {
        assertEquals(
            "resolved",
            finishCoverResolution("current", "resolved", resolutionComplete = false, currentCoverUsable = false)
        )
        assertEquals(
            "current",
            finishCoverResolution("current", null, resolutionComplete = false, currentCoverUsable = false)
        )
    }

    @Test
    fun `finished resolution prefers the result and drops only unusable current covers`() {
        assertEquals("resolved", finishCoverResolution("current", "resolved", resolutionComplete = true))
        assertNull(finishCoverResolution("current", " ", resolutionComplete = true, currentCoverUsable = false))
        assertEquals("current", finishCoverResolution("current", null, resolutionComplete = true))
        assertEquals(
            "current",
            finishCoverResolution("current", "", resolutionComplete = true, currentCoverUsable = true)
        )
    }

    private companion object {
        const val REMOTE_COVER = "https://img.example.com/b.jpg"
    }
}
