package moe.ouom.neriplayer.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneLayer
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassSceneZIndex
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassVisibilitySceneMotion

internal class AppNavigationSceneRenderer(
    private val advancedGlassController: AdvancedGlassController,
    private val backgroundImageUri: String?,
    private val backgroundImageBlur: Float,
    private val effectiveBackgroundImageAlpha: Float,
    private val coherentFeedbackEnabled: Boolean,
    private val currentRoute: String?,
    private val visibleNavigationRoutes: Set<String?>,
    private val mainTabNavigationMotion: MainTabNavigationMotionState
) {
    @Composable
    fun RenderNavigationScene(
        revealTopFraction: Float,
        contentTranslationYFraction: Float,
        contentScale: Float,
        navigationDepth: Int,
        fixedBackground: Boolean,
        content: @Composable () -> Unit
    ) {
        AdvancedGlassSceneLayer(
            controller = advancedGlassController,
            modifier = Modifier.advancedGlassSceneZIndex(navigationDepth),
            motion = AdvancedGlassSceneMotion(
                revealTopFraction = revealTopFraction,
                contentTranslationYFraction = contentTranslationYFraction,
                contentScale = contentScale
            ),
            disableStretchOverscroll = backgroundImageUri != null,
            fixedBackground = fixedBackground,
            background = {
                // 场景自绘壁纸背景, 玻璃模糊要采样它
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    CustomBackground(
                        imageUri = backgroundImageUri,
                        blur = backgroundImageBlur,
                        alpha = effectiveBackgroundImageAlpha
                    )
                }
            },
            content = { content() }
        )
    }

    @Composable
    fun AnimatedContentScope.RenderNavHostScene(
        sceneRoute: String?,
        content: @Composable () -> Unit
    ) {
        val intent = navHostSceneMotionIntent(
            sceneRoute, currentRoute, visibleNavigationRoutes
        )
        val motion = transition.animateAdvancedGlassVisibilitySceneMotion(
            coherentFeedbackEnabled = coherentFeedbackEnabled,
            enteringFromDeeperScene = intent.enteringFromDeeperScene,
            exitingToDeeperScene = intent.exitingToDeeperScene,
            label = "nav_scene_${sceneRoute.orEmpty()}"
        )
        RenderNavigationScene(
            revealTopFraction = motion.revealTopFraction,
            contentTranslationYFraction = motion.contentTranslationYFraction,
            contentScale = motion.contentScale,
            navigationDepth = intent.sceneDepth,
            fixedBackground = false,
            content = content
        )
    }

    @Composable
    fun RenderMainTabNavigationScene(
        revealTopFraction: Float,
        contentTranslationYFraction: Float,
        contentScale: Float,
        sceneDepth: Int,
        content: @Composable () -> Unit
    ) {
        val presentation = mainTabScenePresentation(
            contentTranslationYFraction,
            contentScale,
            sceneDepth,
            backgroundImageUri != null,
            mainTabNavigationMotion
        )
        RenderNavigationScene(
            revealTopFraction = revealTopFraction,
            contentTranslationYFraction = presentation.translationYFraction,
            contentScale = presentation.scale,
            navigationDepth = sceneDepth,
            // 只有 tab 根列表 (sceneDepth 0) 走固定背景, 横滑切 tab 时壁纸不动
            // 嵌套详情必须保留不透明自背景, 靠揭示裁剪盖住退出列表, 否则两页内容互透叠印
            fixedBackground = presentation.fixedBackground,
            content = content
        )
    }
}

internal data class AppNavHostSceneMotionIntent(
    val sceneDepth: Int,
    val enteringFromDeeperScene: Boolean,
    val exitingToDeeperScene: Boolean
)

internal fun navHostSceneMotionIntent(
    sceneRoute: String?,
    currentRoute: String?,
    visibleRoutes: Set<String?>
): AppNavHostSceneMotionIntent {
    val sceneDepth = transparentNavigationDepth(sceneRoute)
    val currentDepth = transparentNavigationDepth(currentRoute)
    return AppNavHostSceneMotionIntent(
        sceneDepth = sceneDepth,
        enteringFromDeeperScene = sceneRoute == currentRoute &&
            visibleRoutes.any { transparentNavigationDepth(it) > sceneDepth },
        exitingToDeeperScene = sceneRoute != currentRoute && currentDepth > sceneDepth
    )
}

internal data class AppMainTabScenePresentation(
    val translationYFraction: Float,
    val scale: Float,
    val fixedBackground: Boolean
)

internal fun mainTabScenePresentation(
    translationYFraction: Float,
    scale: Float,
    depth: Int,
    hasCustomBackground: Boolean,
    motion: MainTabNavigationMotionState
): AppMainTabScenePresentation {
    val drawerSink = motion.backgroundMotion == MainTabBackgroundMotion.DRAWER_SINK
    return AppMainTabScenePresentation(
        translationYFraction = translationYFraction +
            if (drawerSink) motion.backgroundTransform.translationYFraction else 0f,
        scale = scale * if (drawerSink) motion.backgroundTransform.scale else 1f,
        fixedBackground = hasCustomBackground && depth == 0
    )
}
