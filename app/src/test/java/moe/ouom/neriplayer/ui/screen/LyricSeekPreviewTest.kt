package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.ui.screen.playback.resolveListenTogetherProgressSeekEnabled
import moe.ouom.neriplayer.ui.screen.playback.resolveLyricPreviewTimeMs
import moe.ouom.neriplayer.ui.screen.playback.shouldAnimateAdvancedLyricsFromPlayback
import moe.ouom.neriplayer.ui.screen.playback.shouldReleaseLyricSeekPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSeekPreviewTest {

    @Test
    fun `resolveLyricPreviewTimeMs prioritizes dragging then pending preview then playback`() {
        assertEquals(
            12_000L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = true,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = 8_500L,
                playbackPositionMs = 6_000L
            )
        )
        assertEquals(
            8_500L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = false,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = 8_500L,
                playbackPositionMs = 6_000L
            )
        )
        assertEquals(
            6_000L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = false,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = null,
                playbackPositionMs = 6_000L
            )
        )
    }

    @Test
    fun `shouldReleaseLyricSeekPreview waits until playback catches preview`() {
        assertFalse(
            shouldReleaseLyricSeekPreview(
                playbackPositionMs = 9_000L,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
        assertTrue(
            shouldReleaseLyricSeekPreview(
                playbackPositionMs = 11_780L,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
    }

    @Test
    fun `shouldAnimateAdvancedLyricsFromPlayback disables interpolation while dragging or settling preview`() {
        assertFalse(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = true,
                pendingSeekPreviewPositionMs = null
            )
        )
        assertFalse(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = false,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
        assertTrue(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = false,
                pendingSeekPreviewPositionMs = null
            )
        )
    }

    @Test
    fun `resolveListenTogetherProgressSeekEnabled disables seek only for restricted listeners`() {
        assertTrue(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = null,
                fallbackRole = null,
                roomId = null,
                controllerUserUuid = null,
                controllerUserId = null,
                allowMemberControl = false
            )
        )
        assertTrue(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = "owner",
                fallbackRole = "listener",
                roomId = "ABC123",
                controllerUserUuid = "owner",
                controllerUserId = null,
                allowMemberControl = false
            )
        )
        assertTrue(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = "member",
                fallbackRole = "listener",
                roomId = "ABC123",
                controllerUserUuid = "owner",
                controllerUserId = null,
                allowMemberControl = true
            )
        )
        assertFalse(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = "member",
                fallbackRole = "listener",
                roomId = "ABC123",
                controllerUserUuid = "owner",
                controllerUserId = null,
                allowMemberControl = false
            )
        )
    }

    @Test
    fun `restricted seek compares trimmed identities and falls back to the session role`() {
        fun seekEnabled(
            sessionUserUuid: String?,
            fallbackRole: String?,
            controllerUserUuid: String?,
            controllerUserId: String?
        ) = resolveListenTogetherProgressSeekEnabled(
            sessionUserUuid = sessionUserUuid,
            fallbackRole = fallbackRole,
            roomId = "ABC123",
            controllerUserUuid = controllerUserUuid,
            controllerUserId = controllerUserId,
            allowMemberControl = false
        )

        assertTrue(seekEnabled(" owner ", "listener", "owner\n", null))
        assertTrue(seekEnabled("owner-id", "listener", "  ", " owner-id"))
        assertFalse(seekEnabled("member", "controller", null, "owner-id"))
        assertTrue(seekEnabled("  ", "controller", "owner", null))
        assertFalse(seekEnabled(null, "listener", "owner", null))
        assertTrue(seekEnabled("member", "controller", "", null))
        assertFalse(seekEnabled("member", null, null, null))
        assertTrue(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = "member",
                fallbackRole = "listener",
                roomId = " ",
                controllerUserUuid = "owner",
                controllerUserId = null,
                allowMemberControl = false
            )
        )
        assertTrue(
            resolveListenTogetherProgressSeekEnabled(
                sessionUserUuid = "member",
                fallbackRole = "listener",
                roomId = "ABC123",
                controllerUserUuid = "owner",
                controllerUserId = null,
                allowMemberControl = null
            )
        )
    }

    @Test
    fun `preview time never goes negative`() {
        assertEquals(
            0L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = true,
                sliderPreviewPositionMs = -40L,
                pendingSeekPreviewPositionMs = null,
                playbackPositionMs = 6_000L
            )
        )
        assertFalse(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = false,
                isDraggingSlider = false,
                pendingSeekPreviewPositionMs = null
            )
        )
    }
}
