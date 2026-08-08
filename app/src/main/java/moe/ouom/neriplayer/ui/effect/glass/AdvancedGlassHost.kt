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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    val backdrops = LocalAdvancedGlassBackdrops.current
    val regionRegistry = backdrops?.regionRegistry
    val guardKey = remember { Any() }
    SideEffect {
        regionRegistry?.setHandoffGuard(guardKey, enabled)
        backdrops?.background?.setLocalBlurHandoffGuard(guardKey, enabled)
        backdrops?.content?.setLocalBlurHandoffGuard(guardKey, enabled)
    }
    DisposableEffect(backdrops, regionRegistry, guardKey) {
        onDispose {
            regionRegistry?.removeHandoffGuard(guardKey)
            backdrops?.background?.removeLocalBlurHandoffGuard(guardKey)
            backdrops?.content?.removeLocalBlurHandoffGuard(guardKey)
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
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val navigationHandoffActive = regionRegistry.retainsEffectDuringHandoff
    val localBlurRendererCacheKey = sessionController.advancedBlurQuality.ordinal
    val blurRadiusDp = sessionController.normalizedBlurAmountDp
    val blurRadiusPx = with(density) { blurRadiusDp.dp.toPx() }
    val backgroundLocalBlurPlan = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        renderProfile,
        localBlurRendererCacheKey,
        renderRegionState.background
    ) {
        if (sessionController.isBaseBlurEnabled && renderProfile.usesRegionLocalRendering) {
            resolveAdvancedGlassLocalBlurPlan(
                regions = renderRegionState.background,
                radiusPx = blurRadiusPx,
                maximumMergedInputAreaRatio = renderProfile.maximumMergedInputAreaRatio,
                downscaleFactor = renderProfile.downscaleFactorFor(blurRadiusPx),
                rendererCacheKey = localBlurRendererCacheKey
            )
        } else {
            null
        }
    }
    val contentLocalBlurPlan = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        renderProfile,
        localBlurRendererCacheKey,
        renderRegionState.content
    ) {
        if (sessionController.isBaseBlurEnabled && renderProfile.usesRegionLocalRendering) {
            resolveAdvancedGlassLocalBlurPlan(
                regions = renderRegionState.content,
                radiusPx = blurRadiusPx,
                maximumMergedInputAreaRatio = renderProfile.maximumMergedInputAreaRatio,
                downscaleFactor = renderProfile.downscaleFactorFor(blurRadiusPx),
                rendererCacheKey = localBlurRendererCacheKey
            )
        } else {
            null
        }
    }
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
        if (renderProfile.usesRegionLocalRendering) {
            Result.success(null)
        } else {
            buildBackdropEffect(
                controller = sessionController,
                radiusPx = blurRadiusPx,
                renderRegions = renderRegionState.background,
                renderEffectSession = backgroundRenderEffectSession
            )
        }
    }
    val contentEffectResult = remember(
        sessionController.isBaseBlurEnabled,
        blurRadiusPx,
        renderProfile,
        renderRegionState.content,
        contentRenderEffectSession
    ) {
        if (renderProfile.usesRegionLocalRendering) {
            Result.success(null)
        } else {
            buildBackdropEffect(
                controller = sessionController,
                radiusPx = blurRadiusPx,
                renderRegions = renderRegionState.content,
                renderEffectSession = contentRenderEffectSession
            )
        }
    }
    val backendFailed = backgroundEffectResult.isFailure || contentEffectResult.isFailure
    if (backendFailed && sessionHealthy) {
        SideEffect { sessionHealthy = false }
    }

    if (renderProfile.usesRegionLocalRendering) {
        ApplyLocalBlurPlan(
            backdrop = backgroundBackdrop,
            nextPlan = backgroundLocalBlurPlan,
            retainCurrentPlan = navigationHandoffActive &&
                !renderRegionState.hasNavigationSceneRegion &&
                sessionController.isBaseBlurEnabled &&
                renderRegionState.backgroundBackdropReady,
            allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
                renderRegionState.backgroundBackdropReady
        )
        ApplyLocalBlurPlan(
            backdrop = contentBackdrop,
            nextPlan = contentLocalBlurPlan,
            retainCurrentPlan = false,
            allowOneFrameHandoff = sessionController.isBaseBlurEnabled &&
                renderRegionState.contentBackdropReady
        )
    } else {
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
    }
    DisposableEffect(lifecycleOwner, backgroundBackdrop, contentBackdrop) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    backgroundBackdrop.invalidateLocalBlurRenderer()
                    contentBackdrop.invalidateLocalBlurRenderer()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            backgroundBackdrop.renderEffect = null
            contentBackdrop.renderEffect = null
            backgroundBackdrop.localBlurPlan = null
            contentBackdrop.localBlurPlan = null
            backgroundBackdrop.invalidateLocalBlurRenderer()
            contentBackdrop.invalidateLocalBlurRenderer()
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
            AdvancedGlassOverscrollFactory
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
    if (backdrop.localBlurPlan != null) {
        SideEffect {
            backdrop.localBlurPlan = null
            backdrop.invalidateLocalBlurRenderer()
        }
    }
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

@Composable
private fun ApplyLocalBlurPlan(
    backdrop: AdvancedGlassBackdrop,
    nextPlan: AdvancedGlassLocalBlurPlan?,
    retainCurrentPlan: Boolean,
    allowOneFrameHandoff: Boolean
) {
    if (backdrop.renderEffect != null) {
        SideEffect { backdrop.renderEffect = null }
    }
    val currentPlan = backdrop.localBlurPlan
    if (
        shouldRetainCurrentLocalBlurPlan(
            currentPlan = currentPlan,
            handoffActive = retainCurrentPlan
        )
    ) {
        if (!backdrop.freezeLocalBlurFrame) {
            SideEffect { backdrop.freezeLocalBlurFrame = true }
        }
        return
    }
    if (nextPlan == currentPlan) {
        if (backdrop.freezeLocalBlurFrame) {
            SideEffect { backdrop.freezeLocalBlurFrame = false }
        }
        return
    }
    if (
        shouldFreezeLocalBlurFrame(
            currentPlan = currentPlan,
            nextPlan = nextPlan,
            retainCurrentPlan = retainCurrentPlan,
            allowOneFrameHandoff = allowOneFrameHandoff
        )
    ) {
        if (!backdrop.freezeLocalBlurFrame) {
            SideEffect { backdrop.freezeLocalBlurFrame = true }
        }
        LaunchedEffect(backdrop, currentPlan, nextPlan, retainCurrentPlan, allowOneFrameHandoff) {
            withFrameNanos { }
            if (!backdrop.hasLocalBlurHandoffGuard && backdrop.localBlurPlan == currentPlan) {
                backdrop.localBlurPlan = null
                backdrop.invalidateLocalBlurRenderer()
            }
        }
    } else {
        SideEffect {
            backdrop.localBlurPlan = nextPlan
            backdrop.freezeLocalBlurFrame = false
            if (nextPlan == null) {
                backdrop.invalidateLocalBlurRenderer()
            }
        }
    }
}

internal fun shouldRetainCurrentLocalBlurPlan(
    currentPlan: AdvancedGlassLocalBlurPlan?,
    handoffActive: Boolean
): Boolean = handoffActive && currentPlan != null

internal fun shouldFreezeLocalBlurFrame(
    currentPlan: AdvancedGlassLocalBlurPlan?,
    nextPlan: AdvancedGlassLocalBlurPlan?,
    retainCurrentPlan: Boolean,
    allowOneFrameHandoff: Boolean
): Boolean = currentPlan != null &&
    nextPlan == null &&
    (retainCurrentPlan || allowOneFrameHandoff)

private fun buildBackdropEffect(
    controller: AdvancedGlassController,
    radiusPx: Float,
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
            regions = renderRegions
        )
    }
}
