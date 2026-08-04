package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalAdvancedGlassController = staticCompositionLocalOf {
    AdvancedGlassController(
        sdkInt = 0,
        advancedBlurEnabled = false,
        enhancedAdvancedBlurEnabled = false,
        backendReady = false
    )
}

internal data class AdvancedGlassBackdrops(
    val background: AdvancedGlassBackdrop,
    val content: AdvancedGlassBackdrop,
    val regionRegistry: AdvancedGlassRegionRegistry
)

private data class AdvancedGlassRenderRegionState(
    val background: List<AdvancedGlassRenderRegion>,
    val content: List<AdvancedGlassRenderRegion>,
    val hasNavigationSceneRegion: Boolean,
    val backgroundBackdropReady: Boolean,
    val contentBackdropReady: Boolean
)

internal val LocalAdvancedGlassBackdrops = staticCompositionLocalOf<AdvancedGlassBackdrops?> { null }
internal val LocalAdvancedGlassDepth = staticCompositionLocalOf { 0 }
internal val LocalAdvancedGlassActiveNavigationOwners =
    staticCompositionLocalOf<Set<Any>?> { null }
internal val LocalAdvancedGlassNavigationOwner =
    staticCompositionLocalOf<Any?> { null }
internal val LocalAdvancedGlassSceneActive = staticCompositionLocalOf { true }
internal val LocalAdvancedGlassBackdropRegistrationEnabled = staticCompositionLocalOf { true }

@Composable
internal fun AdvancedGlassScene(
    active: Boolean,
    content: @Composable () -> Unit
) {
    val parentActive = LocalAdvancedGlassSceneActive.current
    CompositionLocalProvider(
        LocalAdvancedGlassSceneActive provides (parentActive && active),
        content = content
    )
}

@Composable
internal fun AdvancedGlassNavigationHandoff(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val regionRegistry = LocalAdvancedGlassBackdrops.current?.regionRegistry
    val guardKey = remember { Any() }
    SideEffect {
        regionRegistry?.setHandoffGuard(guardKey, enabled)
    }
    DisposableEffect(regionRegistry, guardKey) {
        onDispose {
            regionRegistry?.removeHandoffGuard(guardKey)
        }
    }
    content()
}

@Composable
internal fun AdvancedGlassHost(
    controller: AdvancedGlassController,
    backgroundBackdrop: AdvancedGlassBackdrop,
    contentBackdrop: AdvancedGlassBackdrop,
    activeNavigationOwners: Set<Any>? = null,
    disableStretchOverscroll: Boolean = false,
    content: @Composable () -> Unit
) {
    val assetManager = LocalContext.current.applicationContext.assets
    val density = LocalDensity.current
    val parentOverscrollFactory = LocalOverscrollFactory.current
    val regionRegistry = remember { AdvancedGlassRegionRegistry() }
    val shaderSource = remember(assetManager) {
        AdvancedGlassShaderSource(assetManager)
    }
    val renderRegionState by remember(
        backgroundBackdrop,
        contentBackdrop,
        regionRegistry,
        activeNavigationOwners
    ) {
        derivedStateOf {
            val renderedRegions = regionRegistry.regions.filter { region ->
                region.navigationOwner == null ||
                    activeNavigationOwners == null ||
                    region.navigationOwner in activeNavigationOwners
            }
            val contentRegions = renderedRegions.filter { region ->
                region.role == AdvancedGlassRole.MiniPlayer ||
                    region.role == AdvancedGlassRole.BottomNavigation
            }
            AdvancedGlassRenderRegionState(
                background = resolveStableAdvancedGlassRenderRegions(
                    backdropPositionInWindow = backgroundBackdrop.positionInWindow,
                    regions = renderedRegions
                ),
                content = resolveStableAdvancedGlassRenderRegions(
                    backdropPositionInWindow = contentBackdrop.positionInWindow,
                    regions = contentRegions
                ),
                hasNavigationSceneRegion = renderedRegions.any { region ->
                    region.role != AdvancedGlassRole.MiniPlayer &&
                        region.role != AdvancedGlassRole.BottomNavigation
                },
                backgroundBackdropReady = backgroundBackdrop.positionInWindow.isSpecified,
                contentBackdropReady = contentBackdrop.positionInWindow.isSpecified
            )
        }
    }
    var sessionHealthy by remember { mutableStateOf(true) }
    val sessionController = if (sessionHealthy) controller else controller.afterBackendFailure()
    val renderProfile = sessionController.advancedBlurQuality.renderProfile()
    val blurRadiusDp = sessionController.normalizedBlurAmountDp
    val blurRadiusPx = with(density) { blurRadiusDp.dp.toPx() }
    val backgroundRenderEffectSession = remember(
        sessionController.sdkInt,
        sessionController.backendReady,
        shaderSource
    ) {
        createAdvancedGlassRenderEffectSession(
            shaderSource = shaderSource,
            sdkInt = sessionController.sdkInt
        )
    }
    val contentRenderEffectSession = remember(
        sessionController.sdkInt,
        sessionController.backendReady,
        shaderSource
    ) {
        createAdvancedGlassRenderEffectSession(
            shaderSource = shaderSource,
            sdkInt = sessionController.sdkInt
        )
    }

    val backgroundEffectResult = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        renderProfile,
        renderRegionState.background,
        backgroundRenderEffectSession
    ) {
        buildBackdropEffect(
            controller = sessionController,
            radiusPx = blurRadiusPx,
            renderProfile = renderProfile,
            renderRegions = renderRegionState.background,
            renderEffectSession = backgroundRenderEffectSession
        )
    }
    val contentEffectResult = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        renderProfile,
        renderRegionState.content,
        contentRenderEffectSession
    ) {
        buildBackdropEffect(
            controller = sessionController,
            radiusPx = blurRadiusPx,
            renderProfile = renderProfile,
            renderRegions = renderRegionState.content,
            renderEffectSession = contentRenderEffectSession
        )
    }
    val backendFailed = backgroundEffectResult.isFailure || contentEffectResult.isFailure
    if (backendFailed && sessionHealthy) {
        SideEffect { sessionHealthy = false }
    }

    ApplyBackdropEffect(
        backdrop = backgroundBackdrop,
        effectResult = backgroundEffectResult,
        retainCurrentEffect = regionRegistry.retainsEffectDuringHandoff &&
            !renderRegionState.hasNavigationSceneRegion &&
            sessionController.isBaseBlurEnabled &&
            backgroundEffectResult.isSuccess &&
            renderRegionState.backgroundBackdropReady,
        allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
            backgroundEffectResult.isSuccess &&
            renderRegionState.backgroundBackdropReady
    )
    ApplyBackdropEffect(
        backdrop = contentBackdrop,
        effectResult = contentEffectResult,
        retainCurrentEffect = false,
        allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
            contentEffectResult.isSuccess &&
            renderRegionState.contentBackdropReady
    )
    DisposableEffect(backgroundBackdrop, contentBackdrop) {
        onDispose {
            backgroundBackdrop.renderEffect = null
            contentBackdrop.renderEffect = null
        }
    }

    CompositionLocalProvider(
        LocalAdvancedGlassController provides sessionController,
        LocalAdvancedGlassBackdrops provides AdvancedGlassBackdrops(
            background = backgroundBackdrop,
            content = contentBackdrop,
            regionRegistry = regionRegistry
        ),
        LocalAdvancedGlassDepth provides 0,
        LocalAdvancedGlassActiveNavigationOwners provides activeNavigationOwners,
        LocalOverscrollFactory provides if (
            sessionController.isEnabled && disableStretchOverscroll
        ) {
            null
        } else {
            parentOverscrollFactory
        },
        content = content
    )
}

@Composable
private fun ApplyBackdropEffect(
    backdrop: AdvancedGlassBackdrop,
    effectResult: Result<androidx.compose.ui.graphics.RenderEffect?>,
    retainCurrentEffect: Boolean,
    allowOneFrameHandoff: Boolean
) {
    val nextEffect = effectResult.getOrNull()
    if (retainCurrentEffect && backdrop.renderEffect != null) {
        return
    }
    if (nextEffect === backdrop.renderEffect) {
        return
    }
    val shouldHoldPrevious = allowOneFrameHandoff &&
        nextEffect == null &&
        backdrop.renderEffect != null
    if (shouldHoldPrevious) {
        LaunchedEffect(backdrop, effectResult) {
            withFrameNanos { }
            backdrop.renderEffect = null
        }
    } else {
        SideEffect {
            backdrop.renderEffect = nextEffect
        }
    }
}

private fun buildBackdropEffect(
    controller: AdvancedGlassController,
    radiusPx: Float,
    renderProfile: AdvancedGlassRenderProfile,
    renderRegions: List<AdvancedGlassRenderRegion>,
    renderEffectSession: AdvancedGlassRenderEffectSession
): Result<androidx.compose.ui.graphics.RenderEffect?> {
    if (!controller.isBaseBlurEnabled ||
        renderRegions.isEmpty()
    ) {
        return Result.success(null)
    }
    return runCatching {
        renderEffectSession.update(
            radiusPx = radiusPx,
            renderProfile = renderProfile,
            regions = renderRegions
        )
    }
}
