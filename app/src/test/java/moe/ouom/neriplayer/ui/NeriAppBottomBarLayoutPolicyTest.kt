package moe.ouom.neriplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NeriAppBottomBarLayoutPolicyTest {
    @Test
    fun disabledBlurKeepsNavigationContentAboveBottomBar() {
        val insets = resolveBottomBarLayoutInsets(
            baseBlurRequested = false,
            bottomBarInset = 80.dp,
            reservedMiniPlayerHeight = 64.dp
        )

        assertEquals(80.dp, insets.navContentBottomPadding)
        assertEquals(64.dp, insets.screenBottomInset)
        assertEquals(0.dp, insets.miniPlayerBottomPadding)
    }

    @Test
    fun requestedBlurLetsNavigationContentExtendBehindBottomBar() {
        val insets = resolveBottomBarLayoutInsets(
            baseBlurRequested = true,
            bottomBarInset = 80.dp,
            reservedMiniPlayerHeight = 64.dp
        )

        assertEquals(0.dp, insets.navContentBottomPadding)
        assertEquals(144.dp, insets.screenBottomInset)
        assertEquals(80.dp, insets.miniPlayerBottomPadding)
    }
}
