package moe.ouom.neriplayer.ui.onboarding

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalStartupSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalStartupStepVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 共享徽记过渡时画在 SharedTransitionLayout 的浮层里, 不受滚动容器裁切
 * 页面滚过后再切步骤, 徽记会从顶栏后面飞出来, 所以按步骤内容区裁切浮层
 */
@Stable
internal class StartupSharedViewport {
    var layoutOffsetInRoot by mutableStateOf(Offset.Zero)
    var viewportInRoot by mutableStateOf(Rect.Zero)

    val viewportInLayout: Rect
        get() = viewportInRoot.translate(-layoutOffsetInRoot)
}

internal val LocalStartupSharedViewport = staticCompositionLocalOf<StartupSharedViewport?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private class StartupViewportOverlayClip(private val viewport: StartupSharedViewport?) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density
    ): Path? {
        // 没有内容区时沿用默认行为: 跟随父级共享元素的裁切
        val rect = viewport?.viewportInLayout
        if (rect == null || rect.isEmpty) return sharedContentState.parentSharedContentState?.clipPathInOverlay
        path.rewind()
        path.addRect(rect)
        return path
    }
}

internal object StartupSharedKeys {
    const val HeroEmblem = "startup_hero_emblem"
    const val PrivacyPolicy = "startup_privacy_policy"
}

@OptIn(ExperimentalSharedTransitionApi::class)
private object StartupBoundsTransform : BoundsTransform {
    override fun createAnimationSpec(initialBounds: Rect, targetBounds: Rect): FiniteAnimationSpec<Rect> =
        spring(dampingRatio = 0.88f, stiffness = 340f, visibilityThreshold = Rect.VisibilityThreshold)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Stable
internal class StartupSharedHandle(
    val transitionScope: SharedTransitionScope,
    val state: SharedTransitionScope.SharedContentState,
    val visibilityScope: AnimatedVisibilityScope
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberStartupSharedHandle(
    key: Any,
    visibilityScope: AnimatedVisibilityScope? = LocalStartupStepVisibility.current
): StartupSharedHandle? {
    val transitionScope = LocalStartupSharedTransition.current ?: return null
    val scope = visibilityScope ?: return null
    val state = with(transitionScope) { rememberSharedContentState(key) }
    return remember(transitionScope, state, scope) {
        StartupSharedHandle(transitionScope, state, scope)
    }
}

/**
 * 徽记在步骤间移动: 两端容器都不透明, 只让图标快速交叉淡化, 位置和尺寸连续插值
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.startupSharedElement(
    handle: StartupSharedHandle?,
    zIndexInOverlay: Float = 0f,
    viewport: StartupSharedViewport? = null
): Modifier {
    if (handle == null) return this
    return with(handle.transitionScope) {
        this@startupSharedElement.sharedBounds(
            sharedContentState = handle.state,
            animatedVisibilityScope = handle.visibilityScope,
            enter = fadeIn(tween(durationMillis = 140)),
            exit = fadeOut(tween(durationMillis = 140)),
            boundsTransform = StartupBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = zIndexInOverlay,
            clipInOverlayDuringTransition = StartupViewportOverlayClip(viewport)
        )
    }
}

/** 容器变换: 新容器立即不透明, 只让内容淡入, 用于完整说明从卡片展开 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.startupContainerTransform(handle: StartupSharedHandle?, zIndexInOverlay: Float = 0f): Modifier {
    if (handle == null) return this
    return with(handle.transitionScope) {
        this@startupContainerTransform.sharedBounds(
            sharedContentState = handle.state,
            animatedVisibilityScope = handle.visibilityScope,
            enter = fadeIn(tween(durationMillis = 90)),
            exit = fadeOut(tween(durationMillis = 220, delayMillis = 60)),
            boundsTransform = StartupBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = zIndexInOverlay
        )
    }
}

internal fun startupStepContentTransform(forward: Boolean, clean: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    // 旧页在 90ms 内淡出, 新页 90ms 后才开始淡入, 两页的文字不会同时出现
    val transform = if (clean) {
        val push = spring(dampingRatio = 0.92f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
        (slideInHorizontally(push) { width -> width * direction / 4 } +
            fadeIn(tween(durationMillis = 180, delayMillis = 90, easing = LinearOutSlowInEasing)))
            .togetherWith(
                slideOutHorizontally(push) { width -> -width * direction / 8 } +
                    fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing))
            )
    } else {
        // Material 共享轴: 位移克制, 节奏来自强调缓动
        (slideInHorizontally(tween(durationMillis = 400, easing = NeriMotion.EmphasizedDecelerate)) { width ->
            (width * 0.14f * direction).toInt()
        } + fadeIn(tween(durationMillis = 200, delayMillis = 90, easing = LinearOutSlowInEasing)))
            .togetherWith(
                slideOutHorizontally(tween(durationMillis = 240, easing = NeriMotion.EmphasizedAccelerate)) { width ->
                    -(width * 0.06f * direction).toInt()
                } + fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing))
            )
    }
    return ContentTransform(
        targetContentEnter = transform.targetContentEnter,
        initialContentExit = transform.initialContentExit,
        sizeTransform = SizeTransform(clip = false)
    )
}

/** 波浪圆: 花瓣数在两个整数之间插值, 让徽记在步骤之间变形而不是切换 */
private fun scallopRadius(lobes: Int, theta: Float, depth: Float): Float =
    if (lobes <= 0) 1f else 1f - depth * (0.5f - 0.5f * cos(lobes * theta))

internal fun buildStartupScallopPath(
    size: Size,
    lobesFrom: Int,
    lobesTo: Int,
    morph: Float,
    depth: Float,
    rotation: Float,
    path: Path = Path()
): Path {
    path.reset()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(cx, cy)
    val segments = 160
    for (index in 0..segments) {
        val theta = index.toFloat() / segments * (2f * PI.toFloat())
        val factor = scallopRadius(lobesFrom, theta, depth) * (1f - morph) +
            scallopRadius(lobesTo, theta, depth) * morph
        val x = cx + radius * factor * cos(theta + rotation)
        val y = cy + radius * factor * sin(theta + rotation)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Immutable
internal class StartupScallopShape(
    private val lobes: Int,
    private val depth: Float = 0.12f
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(buildStartupScallopPath(size, lobes, lobes, 0f, depth, 0f))
}

/** icon 为空时直接显示音理的启动图标, 裁成当前风格的徽章外形 */
@Composable
internal fun StartupEmblem(
    icon: ImageVector?,
    lobes: Int,
    size: Dp,
    modifier: Modifier = Modifier
) {
    if (NeriDesign.isClean) {
        CleanStartupEmblem(icon = icon, size = size, modifier = modifier)
    } else {
        MaterialStartupEmblem(icon = icon, lobes = lobes, size = size, modifier = modifier)
    }
}

@Composable
private fun MaterialStartupEmblem(
    icon: ImageVector?,
    lobes: Int,
    size: Dp,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    var fromLobes by remember { mutableIntStateOf(lobes) }
    var toLobes by remember { mutableIntStateOf(lobes) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(lobes) {
        if (lobes == toLobes) return@LaunchedEffect
        fromLobes = if (morph.value >= 0.5f) toLobes else fromLobes
        toLobes = lobes
        morph.snapTo(0f)
        morph.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 220f))
    }
    val spin by rememberInfiniteTransition(label = "startup_emblem").animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 36_000, easing = LinearEasing)),
        label = "startup_emblem_spin"
    )
    val corePath = remember { Path() }
    val container = colors.primaryContainer
    val edge = colors.primary.copy(alpha = 0.18f)
    val appIcon = rememberStartupAppIcon()
    val appIconAlpha by animateFloatAsState(if (icon == null) 1f else 0f, tween(220), label = "startup_app_icon")
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                // 只转外形不转图标, 纯色色块保持干净, 边缘一圈细描边提升清晰度
                buildStartupScallopPath(this.size, fromLobes, toLobes, morph.value, 0.12f, spin, corePath)
                drawPath(corePath, container)
                clipPath(corePath) { drawStartupAppIcon(appIcon, appIconAlpha) }
                drawPath(corePath, edge, style = Stroke(width = 1.dp.toPx()))
            },
        contentAlignment = Alignment.Center
    ) {
        StartupEmblemGlyph(icon = icon, tint = colors.onPrimaryContainer, size = size)
    }
}

@Composable
private fun CleanStartupEmblem(
    icon: ImageVector?,
    size: Dp,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = remember(size) { ContinuousRoundedCornerShape(size * 0.3f) }
    val top = lerp(colors.primary, Color.White, 0.14f)
    val bottom = lerp(colors.primary, Color.Black, 0.08f)
    val appIcon = rememberStartupAppIcon()
    val appIconAlpha by animateFloatAsState(if (icon == null) 1f else 0f, tween(220), label = "startup_app_icon")
    val outlinePath = remember { Path() }
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = size * 0.1f,
                shape = shape,
                ambientColor = colors.primary.copy(alpha = 0.18f),
                spotColor = colors.primary.copy(alpha = 0.3f)
            )
            .drawWithContent {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                drawOutline(outline, Brush.verticalGradient(listOf(top, bottom)))
                outlinePath.rewind()
                outlinePath.addOutline(outline)
                clipPath(outlinePath) { drawStartupAppIcon(appIcon, appIconAlpha) }
                // 只留一道细的上缘高光, 不铺白雾
                drawOutline(
                    outline,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.42f),
                        0.35f to Color.Transparent
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )
                drawContent()
            },
        contentAlignment = Alignment.Center
    ) {
        StartupEmblemGlyph(icon = icon, tint = colors.onPrimary, size = size)
    }
}

@Composable
private fun StartupEmblemGlyph(icon: ImageVector?, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (fadeIn(tween(200, delayMillis = 40)) + scaleIn(spring(0.62f, 420f), initialScale = 0.6f))
                .togetherWith(fadeOut(tween(100)) + scaleOut(tween(140), targetScale = 0.7f))
        },
        label = "startup_emblem_icon"
    ) { target ->
        if (target == null) {
            // 启动图标画在徽章底板上, 这里只占位
            Spacer(Modifier.size(size))
        } else {
            Icon(
                imageVector = target,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}

/** 直接取系统同款的自适应启动图标, 前景是 1024px 位图, 开 mipmap 让缩小后边缘不发糊 */
@Composable
private fun rememberStartupAppIcon(): AdaptiveIconDrawable? {
    val context = LocalContext.current
    return remember(context) {
        (ContextCompat.getDrawable(context, AppR.mipmap.ic_launcher)?.mutate() as? AdaptiveIconDrawable)?.also { icon ->
            (icon.foreground as? BitmapDrawable)?.apply {
                setMipMap(true)
                setFilterBitmap(true)
            }
        }
    }
}

private fun DrawScope.drawStartupAppIcon(icon: AdaptiveIconDrawable?, alpha: Float) {
    if (icon == null || alpha <= 0f) return
    // 自适应图标两层都是 108dp, 只有中间 72dp 可见, 所以向外扩出四分之一
    val inset = size.minDimension * 0.25f
    val left = -inset.roundToInt()
    val top = -inset.roundToInt()
    val right = (size.width + inset).roundToInt()
    val bottom = (size.height + inset).roundToInt()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val saved = native.saveLayerAlpha(0f, 0f, size.width, size.height, (alpha * 255).roundToInt())
        listOfNotNull(icon.background, icon.foreground).forEach { layer ->
            layer.setBounds(left, top, right, bottom)
            layer.draw(native)
        }
        native.restoreToCount(saved)
    }
}

@Immutable
internal data class StartupHeroSpec(
    val icon: ImageVector?,
    val lobes: Int,
    val title: String,
    val description: String
)

internal enum class StartupHeroLayout {
    Welcome,
    Compact,
    Pane
}

@Composable
internal fun StartupStepHero(
    spec: StartupHeroSpec,
    layout: StartupHeroLayout,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = LocalStartupStepVisibility.current,
    accessory: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val emblemHandle = rememberStartupSharedHandle(StartupSharedKeys.HeroEmblem, visibilityScope)
    val emblemViewport = LocalStartupSharedViewport.current
    val welcome = layout == StartupHeroLayout.Welcome
    val emblemSize = when (layout) {
        StartupHeroLayout.Welcome -> 112.dp
        StartupHeroLayout.Pane -> 80.dp
        StartupHeroLayout.Compact -> 60.dp
    }
    val titleStyle = when (layout) {
        StartupHeroLayout.Welcome, StartupHeroLayout.Pane -> MaterialTheme.typography.headlineLarge
        StartupHeroLayout.Compact -> MaterialTheme.typography.headlineMedium
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (welcome) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.Top
    ) {
        StartupEmblem(
            icon = spec.icon,
            lobes = spec.lobes,
            size = emblemSize,
            modifier = Modifier.startupSharedElement(emblemHandle, zIndexInOverlay = 2f, viewport = emblemViewport)
        )
        Spacer(Modifier.height(if (welcome) 26.dp else 18.dp))
        accessory()
        Text(
            text = spec.title,
            modifier = Modifier.semantics { heading() },
            style = titleStyle,
            fontWeight = if (clean) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = if (welcome) TextAlign.Center else TextAlign.Start,
            color = colors.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = spec.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = if (welcome) TextAlign.Center else TextAlign.Start,
            color = colors.onSurfaceVariant
        )
    }
}

/** 多语言问候轮播, 只做装饰, 读屏跳过 */
@Composable
internal fun StartupGreetingTicker(greetings: List<String>, modifier: Modifier = Modifier) {
    if (greetings.isEmpty()) return
    // 用无限过渡驱动轮播, 测试时钟和系统动画缩放都能接管
    val cycle by rememberInfiniteTransition(label = "startup_greeting_cycle").animateFloat(
        initialValue = 0f,
        targetValue = greetings.size.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = greetings.size * 1_700, easing = LinearEasing)
        ),
        label = "startup_greeting_index"
    )
    val index = cycle.toInt().coerceIn(0, greetings.lastIndex)
    val colors = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = index,
        modifier = modifier.clearAndSetSemantics { },
        transitionSpec = {
            (slideInVertically(spring(0.8f, 260f)) { height -> height } + fadeIn(tween(260)))
                .togetherWith(slideOutVertically(tween(220)) { height -> -height } + fadeOut(tween(160)))
                .using(SizeTransform(clip = true))
        },
        label = "startup_greeting"
    ) { current ->
        Text(
            text = greetings[current],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary
        )
    }
}
