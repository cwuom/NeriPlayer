package moe.ouom.neriplayer.activity

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingOrientationPolicyTest {
    @Test
    fun phoneOutsidePlayerRemainsPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            NowPlayingOrientationState().requestedOrientation(360))
    }

    @Test
    fun openingPlayerAllowsPhoneRotationAndClosingRestoresPortrait() {
        val open = NowPlayingOrientationState().onPlayerOpenChanged(true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, open.requestedOrientation(360))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            open.onPlayerOpenChanged(false).requestedOrientation(360))
    }

    @Test
    fun returningToPortraitStaysLockedForThisOpening() {
        val portrait = NowPlayingOrientationState().onPlayerOpenChanged(true).returnToPortrait()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, portrait.requestedOrientation(360))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            portrait.onPlayerOpenChanged(true).requestedOrientation(360))
    }

    @Test
    fun reopeningPlayerAllowsRotationAgain() {
        val reopened = NowPlayingOrientationState().onPlayerOpenChanged(true).returnToPortrait()
            .onPlayerOpenChanged(false).onPlayerOpenChanged(true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, reopened.requestedOrientation(360))
    }

    @Test
    fun restoredPortraitRequestDoesNotRestartRotation() {
        val restored = NowPlayingOrientationState(playerOpen = true, portraitRequested = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            restored.onPlayerOpenChanged(true).requestedOrientation(360))
    }

    @Test
    fun tabletsKeepSystemOrientationInEveryPlayerState() {
        for (state in listOf(NowPlayingOrientationState(), NowPlayingOrientationState(playerOpen = true),
            NowPlayingOrientationState(playerOpen = true, portraitRequested = true))) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state.requestedOrientation(600))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state.requestedOrientation(800))
        }
    }
}
