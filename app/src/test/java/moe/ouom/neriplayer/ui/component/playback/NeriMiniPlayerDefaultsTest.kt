package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriMiniPlayerDefaultsTest {
    @Test
    fun staleCoverRequestCannotReplaceTheLatestSongFrame() {
        val oldFrame = MiniPlayerCoverFrame(
            coverUrl = "content://old-cover",
            identityKey = "local:old-song"
        )
        val latestFrame = MiniPlayerCoverFrame(
            coverUrl = "content://new-cover",
            identityKey = "local:new-song"
        )

        assertFalse(shouldCommitMiniPlayerCoverRequest(oldFrame, latestFrame))
        assertTrue(shouldCommitMiniPlayerCoverRequest(latestFrame, latestFrame))
    }

    @Test
    fun reenteringTheSameCoverRejectsCallbacksFromThePreviousRequest() {
        val firstFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song.jpg",
            identityKey = "local:song",
            decodedBitmap = markerBitmap,
            requestToken = "request-a1"
        )
        val reenteredFrame = firstFrame.copy(requestToken = "request-a2")

        assertFalse(shouldCommitMiniPlayerCoverRequest(firstFrame, reenteredFrame))
        assertEquals(
            firstFrame,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = reenteredFrame,
                displayedFrame = firstFrame,
                cachedFrame = null,
                retainedFrame = null,
                failedFrame = reenteredFrame
            )
        )
    }

    @Test
    fun sameSourcePendingRetainedFrameUsesTheLatestRequestToken() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song.jpg",
            identityKey = "local:song",
            requestToken = "request-a1"
        )
        val requestedFrame = retainedFrame.copy(requestToken = "request-a2")

        assertEquals(
            requestedFrame,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = requestedFrame,
                displayedFrame = null,
                cachedFrame = null,
                retainedFrame = retainedFrame
            )
        )
    }

    @Test
    fun retainedCoverCanCommitWhenTheCurrentRequestIsTemporarilyMissing() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song.jpg",
            identityKey = "local:song",
            requestToken = "retained-a"
        )

        assertTrue(
            shouldCommitMiniPlayerCoverFrame(
                completedFrame = retainedFrame,
                latestRequestedFrame = null,
                latestRetainedFrame = retainedFrame,
                currentIdentityKey = "local:song"
            )
        )
    }

    @Test
    fun retainedCoverCannotCommitForAnotherActiveSong() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-a.jpg",
            identityKey = "local:song-a",
            requestToken = "retained-a"
        )

        assertFalse(
            shouldCommitMiniPlayerCoverFrame(
                completedFrame = retainedFrame,
                latestRequestedFrame = null,
                latestRetainedFrame = retainedFrame,
                currentIdentityKey = "local:song-b"
            )
        )
    }

    @Test
    fun requestedCoverAlwaysWinsOverAStaleRetainedCallback() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-a.jpg",
            identityKey = "local:song-a",
            requestToken = "retained-a"
        )
        val requestedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-b.jpg",
            identityKey = "local:song-b",
            requestToken = "requested-b"
        )

        assertFalse(
            shouldCommitMiniPlayerCoverFrame(
                completedFrame = retainedFrame,
                latestRequestedFrame = requestedFrame,
                latestRetainedFrame = retainedFrame,
                currentIdentityKey = "local:song-b"
            )
        )
        assertTrue(
            shouldCommitMiniPlayerCoverFrame(
                completedFrame = requestedFrame,
                latestRequestedFrame = requestedFrame,
                latestRetainedFrame = retainedFrame,
                currentIdentityKey = "local:song-b"
            )
        )
    }

    @Test
    fun visibleFrameRetainsThePreviousDecodedCoverDuringLoading() {
        val previousFrame = MiniPlayerCoverFrame(
            coverUrl = "content://old-cover",
            identityKey = "local:old-song"
        )
        val requestedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://new-cover",
            identityKey = "local:new-song"
        )

        assertEquals(
            previousFrame,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = requestedFrame,
                displayedFrame = previousFrame,
                cachedFrame = null,
                retainedFrame = null
            )
        )
    }

    @Test
    fun failedCurrentFrameFallsBackToPlaceholderWhenNothingWasDecoded() {
        val failedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://failed-cover",
            identityKey = "local:song"
        )

        assertNull(
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = failedFrame,
                displayedFrame = null,
                cachedFrame = null,
                retainedFrame = null,
                failedFrame = failedFrame
            )
        )
    }

    @Test
    fun retainedOnlyFailedFrameFallsBackToPlaceholder() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/removed-song.jpg",
            identityKey = "local:removed-song",
            requestToken = "retained-request"
        )

        assertNull(
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = null,
                displayedFrame = null,
                cachedFrame = null,
                retainedFrame = retainedFrame,
                failedFrame = retainedFrame
            )
        )
    }

    @Test
    fun retainedCoverClearsOnlyAfterTheGraceWindow() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/old-song.jpg",
            identityKey = "local:old-song"
        )

        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = retainedFrame,
                failedFrame = null,
                clearDelayElapsed = false,
                hasCurrentSong = false
            )
        )
        assertTrue(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = retainedFrame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
        assertNull(
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = null,
                displayedFrame = retainedFrame,
                cachedFrame = retainedFrame,
                retainedFrame = retainedFrame,
                clearRetainedFrame = true
            )
        )
        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = retainedFrame,
                retainedFrame = retainedFrame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = false
            )
        )
    }

    @Test
    fun failedCurrentCoverKeepsTheRetainedFrameWhileTheSongIsActive() {
        val requestedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/new-song.jpg",
            identityKey = "local:new-song"
        )

        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = requestedFrame,
                retainedFrame = MiniPlayerCoverFrame(
                    coverUrl = "content://tree/Covers/old-song.jpg",
                    identityKey = "local:old-song"
                ),
                failedFrame = requestedFrame,
                clearDelayElapsed = true,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun activeMiniPlayerKeepsARetainedCoverPastTheGraceWindow() {
        val retainedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-a.jpg",
            identityKey = "local:song-a",
            decodedBitmap = markerBitmap
        )

        assertFalse(
            shouldClearMiniPlayerRetainedCoverAfterGrace(
                requestedFrame = null,
                retainedFrame = retainedFrame,
                failedFrame = null,
                clearDelayElapsed = true,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun failedCurrentFrameKeepsAPreviouslyDisplayedSource() {
        val previousFrame = MiniPlayerCoverFrame(
            coverUrl = "content://old-cover",
            identityKey = "local:song"
        )
        val failedFrame = MiniPlayerCoverFrame(
            coverUrl = "content://new-cover",
            identityKey = "local:song"
        )

        assertEquals(
            previousFrame,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = failedFrame,
                displayedFrame = previousFrame,
                cachedFrame = null,
                retainedFrame = null,
                failedFrame = failedFrame
            )
        )
    }

    @Test
    fun rapidLocalABASwitchRestoresTheCachedFirstCover() {
        val songA = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-a.jpg",
            identityKey = "local:song-a"
        )
        val songB = MiniPlayerCoverFrame(
            coverUrl = "content://tree/Covers/song-b.jpg",
            identityKey = "local:song-b"
        )

        assertEquals(
            songA,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = null,
                displayedFrame = songA,
                cachedFrame = null,
                retainedFrame = songA
            )
        )
        assertFalse(shouldCommitMiniPlayerCoverRequest(songB, songA))
        assertEquals(
            songA,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = songA,
                displayedFrame = songA,
                cachedFrame = songA,
                retainedFrame = null,
                failedFrame = songB
            )
        )
    }

    @Test
    fun coverCacheKeySeparatesIdentityFromTheCurrentReference() {
        val frame = MiniPlayerCoverFrame(
            coverUrl = "content://cover-a",
            identityKey = "local:song"
        )

        assertEquals(
            "local:song|data=content://cover-a",
            miniPlayerCoverCacheKey(frame)
        )
    }

    @Test
    fun `changed cover reference does not reuse an old cached bitmap`() {
        val oldFrame = MiniPlayerCoverFrame(
            coverUrl = "content://cover-old",
            identityKey = "local:song"
        )
        val newFrame = oldFrame.copy(coverUrl = "content://cover-new")

        assertFalse(shouldCommitMiniPlayerCoverRequest(oldFrame, newFrame))
        assertEquals(
            newFrame,
            resolveMiniPlayerVisibleCoverFrame(
                requestedFrame = newFrame,
                displayedFrame = null,
                cachedFrame = null,
                retainedFrame = null
            )
        )
    }

    @Test
    fun coverIdentityFallsBackToUrlForLegacyCallers() {
        assertEquals(
            "content://cover",
            miniPlayerCoverIdentityKey(
                identityKey = null,
                coverUrl = " content://cover "
            )
        )
        assertNull(miniPlayerCoverIdentityKey(identityKey = " ", coverUrl = null))
    }

    @Test
    fun normalFontScaleKeepsOriginalMetadataSizeRange() {
        val range = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 16f,
            maxLineHeightDp = 24f,
            fontScale = 1f,
            minVisualFontSizeSp = 10f,
            lineHeightEm = 1.5f
        )

        assertEquals(
            16f,
            range.maxFontSizeSp,
            0.001f
        )
        assertEquals(10f, range.minFontSizeSp, 0.001f)
    }

    @Test
    fun largeFontScaleReducesMaximumToTheAvailableLineHeight() {
        val titleRange = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 16f,
            maxLineHeightDp = 24f,
            fontScale = 2f,
            minVisualFontSizeSp = 10f,
            lineHeightEm = 1.5f
        )
        val artistRange = resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = 14f,
            maxLineHeightDp = 20f,
            fontScale = 2f,
            minVisualFontSizeSp = 9f,
            lineHeightEm = 20f / 14f
        )

        assertEquals(8f, titleRange.maxFontSizeSp, 0.001f)
        assertEquals(5f, titleRange.minFontSizeSp, 0.001f)
        assertTrue(titleRange.maxFontSizeSp > artistRange.maxFontSizeSp)
        assertTrue(titleRange.minFontSizeSp <= titleRange.maxFontSizeSp)
    }

    private companion object {
        val markerBitmap = object : ImageBitmap {
            override val width: Int = 1
            override val height: Int = 1
            override val colorSpace = ColorSpaces.Srgb
            override val hasAlpha: Boolean = true
            override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

            override fun readPixels(
                buffer: IntArray,
                startX: Int,
                startY: Int,
                width: Int,
                height: Int,
                bufferOffset: Int,
                stride: Int
            ) = Unit

            override fun prepareToDraw() = Unit
        }
    }
}
