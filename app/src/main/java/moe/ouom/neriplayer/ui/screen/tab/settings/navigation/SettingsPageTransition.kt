package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassScene
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassSceneOpacity
import moe.ouom.neriplayer.ui.effect.glass.isolatedAdvancedGlassHorizontalTransition
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage

internal const val SETTINGS_PAGE_ENTER_DURATION_MS = 300
// 旧页稍慢退场，让新页接上时画面和模糊遮罩保持连续
internal const val SETTINGS_PAGE_EXIT_DURATION_MS = 160
private const val SETTINGS_PAGE_ENTER_SCALE = 0.98f
private const val SETTINGS_PAGE_EXIT_SCALE = 0.99f
private val SettingsPageEnterEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
private val SettingsPageExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

internal fun settingsPageTransitionSceneTag(page: SettingsPage?): String =
    "settings-transition-scene-${page?.name ?: "home"}"

private fun settingsPageContentTransform(): ContentTransform {
    val enterSpec = tween<Float>(
        durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
        easing = SettingsPageEnterEasing
    )
    val exitSpec = tween<Float>(
        durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS,
        easing = SettingsPageExitEasing
    )
    return (fadeIn(enterSpec) + scaleIn(enterSpec, initialScale = SETTINGS_PAGE_ENTER_SCALE))
        .togetherWith(fadeOut(exitSpec) + scaleOut(exitSpec, targetScale = SETTINGS_PAGE_EXIT_SCALE))
}

private fun Modifier.blockInactiveSettingsPage(blocked: Boolean): Modifier = if (blocked) {
    clearAndSetSemantics { hideFromAccessibility() }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
} else {
    this
}

@Composable
internal fun SettingsPageTransitionHost(
    activePage: SettingsPage?,
    isolateAdvancedGlassTransitions: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (SettingsPage?) -> Unit
) {
    val transitionState = remember { MutableTransitionState(activePage) }
    var settledPage by remember { mutableStateOf(activePage) }
    // 连点时只保留最新请求，先完成当前交接，避免中断后旧场景被提前移除
    if (transitionState.isIdle && settledPage == transitionState.currentState) {
        transitionState.targetState = activePage
    }
    val displayedPage = transitionState.targetState
    val pageTransition = rememberTransition(transitionState, label = "settings_page_switch")
    pageTransition.AnimatedContent(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
        transitionSpec = { settingsPageContentTransform().using(SizeTransform(clip = true)) }
    ) { page ->
        val sceneAlpha = transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(
                        durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
                        easing = SettingsPageEnterEasing
                    )
                } else {
                    tween(durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS, easing = SettingsPageExitEasing)
                }
            },
            label = "settings_glass_opacity"
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
        val blocked by remember(page, activePage, displayedPage, sceneAlpha) {
            derivedStateOf { page != activePage || page != displayedPage || sceneAlpha.value <= 0f }
        }
        val parentGlassOpacity = LocalAdvancedGlassSceneOpacity.current
        val glassOpacity = remember(parentGlassOpacity, sceneAlpha) {
            { parentGlassOpacity() * sceneAlpha.value }
        }
        Box(Modifier.fillMaxSize().testTag(settingsPageTransitionSceneTag(page))) {
            Box(Modifier.fillMaxSize().blockInactiveSettingsPage(blocked)) {
                AdvancedGlassNavigationHandoff(
                    enabled = shouldHandoffGlass(isolateAdvancedGlassTransitions, transition.isRunning)
                ) {
                    AdvancedGlassScene(
                        active = shouldShowActiveGlassScene(isolateAdvancedGlassTransitions, page, activePage)
                    ) {
                        CompositionLocalProvider(LocalAdvancedGlassSceneOpacity provides glassOpacity) {
                            content(page)
                        }
                    }
                }
            }
        }
    }
    // 完成动画后先提交旧场景清理，避免切回上一页时复用尚未退出的场景
    SideEffect {
        if (transitionState.isIdle) {
            settledPage = transitionState.currentState
        }
    }
}

@Composable
internal fun PhoneSettingsPageTransitionHost(
    activePage: SettingsPage?,
    isolateAdvancedGlassTransitions: Boolean,
    content: @Composable (SettingsPage?) -> Unit
) {
    AnimatedContent(
        targetState = activePage,
        modifier = Modifier.fillMaxSize(),
        label = "settings_page_switch",
        transitionSpec = {
            isolatedAdvancedGlassHorizontalTransition(
                forward = isForwardSettingsPageTransition(initialState, targetState)
            ).using(SizeTransform(clip = true))
        }
    ) { page ->
        Box(Modifier.fillMaxSize().testTag(settingsPageTransitionSceneTag(page))) {
            AdvancedGlassNavigationHandoff(
                enabled = shouldHandoffGlass(isolateAdvancedGlassTransitions, transition.isRunning)
            ) {
                AdvancedGlassScene(
                    active = shouldShowActiveGlassScene(isolateAdvancedGlassTransitions, page, activePage)
                ) {
                    content(page)
                }
            }
        }
    }
}
