package moe.ouom.neriplayer.activity

import android.content.pm.ActivityInfo
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

internal data class NowPlayingOrientationState(
    val playerOpen: Boolean = false,
    val portraitRequested: Boolean = false
) {
    fun onPlayerOpenChanged(open: Boolean): NowPlayingOrientationState = copy(
        playerOpen = open,
        portraitRequested = portraitRequested && playerOpen && open
    )

    fun returnToPortrait(): NowPlayingOrientationState = copy(portraitRequested = playerOpen)

    fun requestedOrientation(smallestScreenWidthDp: Int): Int = when {
        smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP ->
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerOpen && !portraitRequested -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

internal fun shouldHideTabletNavigationBar(
    smallestScreenWidthDp: Int,
    isLandscape: Boolean,
    safeModeActive: Boolean
): Boolean = smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP && isLandscape && !safeModeActive
