package moe.ouom.neriplayer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverResolutionFallbackTest {

    @Test
    fun `local songs probe local candidates even without an immediate cover`() {
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = true, immediateCover = null))
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = true, immediateCover = " "))
    }

    @Test
    fun `remote songs probe only when the immediate cover is missing or blank`() {
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = false, immediateCover = null))
        assertTrue(shouldProbeFastLocalCoverCandidate(isLocalSong = false, immediateCover = "  "))
        assertFalse(
            shouldProbeFastLocalCoverCandidate(
                isLocalSong = false,
                immediateCover = "content://covers/remote.jpg"
            )
        )
    }

    @Test
    fun `in-flight resolution keeps the current cover until a usable one arrives`() {
        assertEquals(
            "content://covers/current.jpg",
            retainCoverDuringResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = null
            )
        )
        assertEquals(
            "content://covers/current.jpg",
            retainCoverDuringResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = "   "
            )
        )
        assertEquals(
            "content://covers/resolved.jpg",
            retainCoverDuringResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = "content://covers/resolved.jpg"
            )
        )
        assertNull(retainCoverDuringResolution(currentCover = null, resolvedCover = ""))
    }

    @Test
    fun `incomplete resolution never clears the visible cover`() {
        assertEquals(
            "content://covers/current.jpg",
            finishCoverResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = null,
                resolutionComplete = false,
                currentCoverUsable = false
            )
        )
        assertEquals(
            "content://covers/resolved.jpg",
            finishCoverResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = "content://covers/resolved.jpg",
                resolutionComplete = false
            )
        )
    }

    @Test
    fun `completed resolution prefers the resolved cover over the current frame`() {
        assertEquals(
            "content://covers/resolved.jpg",
            finishCoverResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = "content://covers/resolved.jpg",
                resolutionComplete = true,
                currentCoverUsable = false
            )
        )
    }

    @Test
    fun `completed resolution keeps a usable or unverified current cover`() {
        assertEquals(
            "content://covers/current.jpg",
            finishCoverResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = " ",
                resolutionComplete = true,
                currentCoverUsable = true
            )
        )
        assertEquals(
            "content://covers/current.jpg",
            finishCoverResolution(
                currentCover = "content://covers/current.jpg",
                resolvedCover = null,
                resolutionComplete = true,
                currentCoverUsable = null
            )
        )
    }

    @Test
    fun `immediate candidate accepts every supported reference scheme`() {
        listOf(
            "HTTPS://example.com/cover.jpg",
            "Http://example.com/cover.jpg",
            "CONTENT://provider/cover",
            "File:///music/cover.jpg",
            "/storage/emulated/0/Music/cover.jpg"
        ).forEach { reference ->
            assertEquals(
                reference,
                resolveImmediateCoverCandidate(
                    primaryCoverUrl = reference,
                    fallbackCoverUrl = "https://example.com/fallback.jpg"
                )
            )
        }
    }

    @Test
    fun `immediate candidate skips references that cannot be loaded as covers`() {
        assertEquals(
            "https://example.com/fallback.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "covers/relative.jpg",
                fallbackCoverUrl = " https://example.com/fallback.jpg "
            )
        )
        assertEquals(
            "https://example.com/fallback.jpg",
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "   ",
                fallbackCoverUrl = "https://example.com/fallback.jpg"
            )
        )
        assertNull(
            resolveImmediateCoverCandidate(
                primaryCoverUrl = "data:image/png;base64,AAAA",
                fallbackCoverUrl = "ftp-cover.jpg"
            )
        )
        assertNull(resolveImmediateCoverCandidate(primaryCoverUrl = null, fallbackCoverUrl = " "))
    }
}
