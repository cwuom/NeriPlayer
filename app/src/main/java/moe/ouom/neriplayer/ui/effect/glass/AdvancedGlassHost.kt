package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
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

internal val LocalAdvancedGlassBackdrops = staticCompositionLocalOf<AdvancedGlassBackdrops?> { null }
internal val LocalAdvancedGlassDepth = staticCompositionLocalOf { 0 }
internal val LocalAdvancedGlassActiveNavigationOwners =
    staticCompositionLocalOf<Set<Any>?> { null }
internal val LocalAdvancedGlassNavigationOwner =
    staticCompositionLocalOf<Any?> { null }
internal val LocalAdvancedGlassSceneActive = staticCompositionLocalOf { true }

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
    val renderedRegions = regionRegistry.regions.filter { region ->
        region.navigationOwner == null ||
            activeNavigationOwners == null ||
            region.navigationOwner in activeNavigationOwners
    }
    val contentRegions = renderedRegions.filter { region ->
        region.role == AdvancedGlassRole.MiniPlayer ||
            region.role == AdvancedGlassRole.BottomNavigation
    }
    val hasNavigationSceneRegion = renderedRegions.any { region ->
        region.role != AdvancedGlassRole.MiniPlayer &&
            region.role != AdvancedGlassRole.BottomNavigation
    }
    var sessionHealthy by remember { mutableStateOf(true) }
    val sessionController = if (sessionHealthy) controller else controller.afterBackendFailure()
    val blurRadiusDp = if (sessionController.isEnabled) {
        sessionController.normalizedEnhancedBlurRadiusDp
    } else {
        advancedGlassTokens(
            role = AdvancedGlassRole.BottomNavigation,
            isDarkTheme = false
        ).blurRadiusDp
    }
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
        backgroundBackdrop.positionInWindow,
        renderedRegions
    ) {
        buildBackdropEffect(
            controller = sessionController,
            radiusPx = blurRadiusPx,
            backdropPositionInWindow = backgroundBackdrop.positionInWindow,
            regions = renderedRegions,
            renderEffectSession = backgroundRenderEffectSession
        )
    }
    val contentEffectResult = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        contentBackdrop.positionInWindow,
        contentRegions
    ) {
        buildBackdropEffect(
            controller = sessionController,
            radiusPx = blurRadiusPx,
            backdropPositionInWindow = contentBackdrop.positionInWindow,
            regions = contentRegions,
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
            !hasNavigationSceneRegion &&
            sessionController.isBaseBlurEnabled &&
            backgroundEffectResult.isSuccess &&
            backgroundBackdrop.positionInWindow.isSpecified,
        allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
            backgroundEffectResult.isSuccess &&
            backgroundBackdrop.positionInWindow.isSpecified
    )
    ApplyBackdropEffect(
        backdrop = contentBackdrop,
        effectResult = contentEffectResult,
        retainCurrentEffect = false,
        allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
            contentEffectResult.isSuccess &&
            contentBackdrop.positionInWindow.isSpecified
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
    backdropPositionInWindow: Offset,
    regions: List<AdvancedGlassRegion>,
    renderEffectSession: AdvancedGlassRenderEffectSession
): Result<androidx.compose.ui.graphics.RenderEffect?> {
    if (!controller.isBaseBlurEnabled ||
        !backdropPositionInWindow.isSpecified ||
        regions.isEmpty()
    ) {
        return Result.success(null)
    }
    val renderRegions = regions.map { region ->
        val bounds = region.boundsInWindow
        AdvancedGlassRenderRegion(
            left = bounds.left - backdropPositionInWindow.x,
            top = bounds.top - backdropPositionInWindow.y,
            right = bounds.right - backdropPositionInWindow.x,
            bottom = bounds.bottom - backdropPositionInWindow.y,
            cornerRadiiPx = region.cornerRadiiPx
        )
    }
    return runCatching {
        renderEffectSession.update(
            radiusPx = radiusPx,
            regions = renderRegions
        )
    }
}
