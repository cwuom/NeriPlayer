package moe.ouom.neriplayer.ui.navigation

import androidx.compose.animation.EnterExitState
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.IOS_PUSH_EASING
import moe.ouom.neriplayer.ui.effect.glass.IOS_PUSH_GESTURE_EASING
import moe.ouom.neriplayer.ui.effect.glass.IOS_PUSH_UNDERLAY_DIM
import moe.ouom.neriplayer.ui.effect.glass.IOS_PUSH_UNDERLAY_SHIFT_FRACTION
import moe.ouom.neriplayer.ui.effect.glass.resolveAdvancedGlassDrawerSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.resolveAdvancedGlassVisibilitySceneMotion
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.resolveIosPushUnderlayDim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosPushNavigationMotionTest {
    @Test
    fun pushedPageEntersFromTheTrailingEdgeAndLeavesTheSameWay() {
        val entering = resolveAdvancedGlassVisibilitySceneMotion(
            EnterExitState.PreEnter, enteringFromDeeperScene = false, exitingToDeeperScene = false, iosPush = true
        )
        val popped = resolveAdvancedGlassVisibilitySceneMotion(
            EnterExitState.PostExit, enteringFromDeeperScene = false, exitingToDeeperScene = false, iosPush = true
        )

        for (motion in listOf(entering, popped)) {
            assertEquals(1f, motion.sceneTranslationXFraction, 0f)
            assertEquals(0f, motion.sceneDim, 0f)
            // iOS 推入不再使用抽屉式的揭示裁剪与纵向位移
            assertEquals(0f, motion.revealTopFraction, 0f)
            assertEquals(0f, motion.contentTranslationYFraction, 0f)
            assertEquals(1f, motion.contentScale, 0f)
        }
    }

    @Test
    fun pageUnderAPushDriftsLeftAndDims() {
        val covered = resolveAdvancedGlassVisibilitySceneMotion(
            EnterExitState.PostExit, enteringFromDeeperScene = false, exitingToDeeperScene = true, iosPush = true
        )
        val uncovering = resolveAdvancedGlassVisibilitySceneMotion(
            EnterExitState.PreEnter, enteringFromDeeperScene = true, exitingToDeeperScene = false, iosPush = true
        )

        for (motion in listOf(covered, uncovering)) {
            assertEquals(IOS_PUSH_UNDERLAY_SHIFT_FRACTION, motion.sceneTranslationXFraction, 0f)
            assertEquals(IOS_PUSH_UNDERLAY_DIM, motion.sceneDim, 0f)
        }
        assertEquals(
            AdvancedGlassSceneMotion.None,
            resolveAdvancedGlassVisibilitySceneMotion(EnterExitState.Visible, true, true, iosPush = true)
        )
    }

    @Test
    fun hostScenesFollowTheSamePushGeometryByDepth() {
        val depth = { route: String -> if (route == "detail") 1 else 0 }

        val root = resolveAdvancedGlassDrawerSceneMotion("root", "detail", depth, iosPush = true)
        val detail = resolveAdvancedGlassDrawerSceneMotion("detail", "root", depth, iosPush = true)

        assertEquals(IOS_PUSH_UNDERLAY_SHIFT_FRACTION, root.sceneTranslationXFraction, 0f)
        assertEquals(1f, detail.sceneTranslationXFraction, 0f)
        assertEquals(AdvancedGlassSceneMotion.None, resolveAdvancedGlassDrawerSceneMotion("root", "root", depth, true))
    }

    @Test
    fun withoutIosPushTheDrawerGeometryIsUnchanged() {
        val drawer = resolveAdvancedGlassVisibilitySceneMotion(EnterExitState.PreEnter, false, false)

        assertEquals(1f, drawer.revealTopFraction, 0f)
        assertEquals(1f, drawer.contentTranslationYFraction, 0f)
        assertEquals(0f, drawer.sceneTranslationXFraction, 0f)
        assertEquals(0f, drawer.sceneDim, 0f)
    }





    @Test
    fun gestureEasingUndoesThePushCurveSoThePageTracksTheFinger() {
        for (step in 0..20) {
            val finger = step / 20f
            val seek = IOS_PUSH_GESTURE_EASING.transform(finger)
            assertEquals(finger, IOS_PUSH_EASING.transform(seek), 1e-4f)
        }
        // 缓出曲线前段很陡，同样的手指位移只需要更小的拖动进度
        assertTrue(IOS_PUSH_GESTURE_EASING.transform(0.4f) < 0.4f)
        assertEquals(0f, IOS_PUSH_GESTURE_EASING.transform(-1f), 1e-4f)
        assertEquals(1f, IOS_PUSH_GESTURE_EASING.transform(2f), 1e-4f)
    }



    @Test
    fun onlyTheSettingsPageUnderneathDims() {
        // 推入时被盖住的是旧页，返回时正在露出的是新页
        assertEquals(IOS_PUSH_UNDERLAY_DIM, resolveIosPushUnderlayDim(EnterExitState.PostExit, forward = true), 0f)
        assertEquals(0f, resolveIosPushUnderlayDim(EnterExitState.PreEnter, forward = true), 0f)
        assertEquals(IOS_PUSH_UNDERLAY_DIM, resolveIosPushUnderlayDim(EnterExitState.PreEnter, forward = false), 0f)
        assertEquals(0f, resolveIosPushUnderlayDim(EnterExitState.PostExit, forward = false), 0f)
        assertEquals(0f, resolveIosPushUnderlayDim(EnterExitState.Visible, forward = true), 0f)
    }
}
