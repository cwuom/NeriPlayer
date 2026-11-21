package moe.ouom.neriplayer.ui.onboarding

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalStartupSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalStartupStepVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

internal object StartupSharedKeys {
    const val HeroEmblem = "startup_hero_emblem"
    const val HeroTitle = "startup_hero_title"
    const val HeroBody = "startup_hero_body"
    const val PrivacyPolicy = "startup_privacy_policy"

    fun choice(choice: StartupChoice): String = "startup_choice_${choice.name}"
}

@OptIn(ExperimentalSharedTransitionApi::class)
private object StartupBoundsTransform : BoundsTransform {
    override fun createAnimationSpec(initialBounds: Rect, targetBounds: Rect): FiniteAnimationSpec<Rect> =
        spring(dampingRatio = 0.86f, stiffness = 300f, visibilityThreshold = Rect.VisibilityThreshold)
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

internal enum class StartupSharedResize {
    Remeasure,
    ScaleText
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.startupSharedBounds(
    handle: StartupSharedHandle?,
    resize: StartupSharedResize = StartupSharedResize.Remeasure,
    zIndexInOverlay: Float = 0f,
    opaqueContainer: Boolean = false
): Modifier {
    if (handle == null) return this
    return with(handle.transitionScope) {
        this@startupSharedBounds.sharedBounds(
            sharedContentState = handle.state,
            animatedVisibilityScope = handle.visibilityScope,
            // 容器变换时新容器尽快不透明, 只让内容交叉淡入, 避免半透明叠影
            enter = if (opaqueContainer) fadeIn(tween(durationMillis = 90)) else fadeIn(tween(durationMillis = 260, delayMillis = 70)),
            exit = if (opaqueContainer) fadeOut(tween(durationMillis = 220, delayMillis = 60)) else fadeOut(tween(durationMillis = 150)),
            boundsTransform = StartupBoundsTransform,
            resizeMode = when (resize) {
                StartupSharedResize.Remeasure -> SharedTransitionScope.ResizeMode.RemeasureToBounds
                // 标题在大小字号之间缩放, 保持字形不被重新折行
                StartupSharedResize.ScaleText -> SharedTransitionScope.ResizeMode.scaleToBounds(
                    ContentScale.FillWidth,
                    Alignment.CenterStart
                )
            },
            zIndexInOverlay = zIndexInOverlay
        )
    }
}

internal fun startupStepContentTransform(forward: Boolean, clean: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val transform = if (clean) {
        // 简洁风格用弹簧推入, 旧页面带视差后退
        val push = spring(dampingRatio = 0.9f, stiffness = 360f, visibilityThreshold = IntOffset.VisibilityThreshold)
        (slideInHorizontally(push) { width -> width * direction * 3 / 10 } +
            fadeIn(tween(durationMillis = 240, delayMillis = 40)))
            .togetherWith(
                slideOutHorizontally(push) { width -> -width * direction / 6 } +
                    fadeOut(tween(durationMillis = 200)) +
                    scaleOut(tween(durationMillis = 280), targetScale = 0.96f)
            )
    } else {
        // Material 共享轴: 先退后进, 位移克制, 节奏来自强调缓动
        (slideInHorizontally(tween(durationMillis = 440, easing = NeriMotion.EmphasizedDecelerate)) { width ->
            (width * 0.16f * direction).toInt()
        } + fadeIn(tween(durationMillis = 260, delayMillis = 100, easing = LinearOutSlowInEasing)))
            .togetherWith(
                slideOutHorizontally(tween(durationMillis = 280, easing = NeriMotion.EmphasizedAccelerate)) { width ->
                    -(width * 0.08f * direction).toInt()
                } + fadeOut(tween(durationMillis = 120, easing = FastOutLinearInEasing))
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

@Composable
internal fun StartupEmblem(
    icon: ImageVector,
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
    icon: ImageVector,
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
    val infinite = rememberInfiniteTransition(label = "startup_emblem")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 22_000, easing = LinearEasing)),
        label = "startup_emblem_spin"
    )
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_800, easing = NeriMotion.SmoothInOut), RepeatMode.Reverse),
        label = "startup_emblem_breathe"
    )
    val corePath = remember { Path() }
    val haloPath = remember { Path() }
    val container = colors.primaryContainer
    val halo = colors.tertiary.copy(alpha = 0.16f)
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                // 光环和主体都画在自身尺寸内, 不依赖父布局放宽裁剪
                buildStartupScallopPath(
                    this.size, toLobes + 3, toLobes + 3, 0f, 0.05f + breathe * 0.02f, -spin * 0.6f, haloPath
                )
                drawPath(haloPath, halo)
                val coreScale = 0.82f
                val coreSize = Size(this.size.width * coreScale, this.size.height * coreScale)
                val coreOffset = Offset(
                    (this.size.width - coreSize.width) / 2f,
                    (this.size.height - coreSize.height) / 2f
                )
                val depth = 0.11f + breathe * 0.025f
                buildStartupScallopPath(coreSize, fromLobes, toLobes, morph.value, depth, spin, corePath)
                    .translate(coreOffset)
                drawPath(
                    corePath,
                    Brush.linearGradient(
                        listOf(lerp(container, colors.primary, 0.08f), container),
                        start = coreOffset,
                        end = coreOffset + Offset(coreSize.width, coreSize.height)
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        StartupEmblemIcon(icon = icon, tint = colors.onPrimaryContainer, size = size * 0.36f)
    }
}

@Composable
private fun CleanStartupEmblem(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = remember(size) { ContinuousRoundedCornerShape(size * 0.3f) }
    val infinite = rememberInfiniteTransition(label = "startup_clean_emblem")
    val sheen by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 4_600, easing = NeriMotion.SmoothInOut)),
        label = "startup_clean_emblem_sheen"
    )
    val float by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3_400, easing = NeriMotion.SmoothInOut), RepeatMode.Reverse),
        label = "startup_clean_emblem_float"
    )
    val top = lerp(colors.primary, Color.White, 0.22f)
    val bottom = lerp(colors.primary, Color.Black, 0.12f)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { translationY = (float - 0.5f) * 3.dp.toPx() }
            .shadow(
                elevation = size * 0.14f,
                shape = shape,
                ambientColor = colors.primary.copy(alpha = 0.4f),
                spotColor = colors.primary.copy(alpha = 0.5f)
            )
            .drawWithContent {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                drawOutline(outline, Brush.verticalGradient(listOf(top, bottom)))
                // 顶部高光和斜向流光, 模拟玻璃釉面
                drawOutline(
                    outline,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.28f),
                        0.5f to Color.Transparent
                    )
                )
                val band = this.size.width * 0.45f
                val start = this.size.width * sheen
                drawOutline(
                    outline,
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        start = Offset(start - band, 0f),
                        end = Offset(start, this.size.height)
                    )
                )
                drawContent()
            },
        contentAlignment = Alignment.Center
    ) {
        StartupEmblemIcon(icon = icon, tint = colors.onPrimary, size = size * 0.44f)
    }
}

@Composable
private fun StartupEmblemIcon(icon: ImageVector, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(0.6f, 420f), initialScale = 0.6f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.7f))
        },
        label = "startup_emblem_icon"
    ) { targetIcon ->
        Icon(
            imageVector = targetIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}

@Immutable
internal data class StartupHeroSpec(
    val icon: ImageVector,
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
    val titleHandle = rememberStartupSharedHandle(StartupSharedKeys.HeroTitle, visibilityScope)
    val bodyHandle = rememberStartupSharedHandle(StartupSharedKeys.HeroBody, visibilityScope)
    val welcome = layout == StartupHeroLayout.Welcome
    val emblemSize = when (layout) {
        StartupHeroLayout.Welcome -> 108.dp
        StartupHeroLayout.Pane -> 80.dp
        StartupHeroLayout.Compact -> 56.dp
    }
    val titleStyle = when (layout) {
        StartupHeroLayout.Welcome -> MaterialTheme.typography.headlineLarge
        StartupHeroLayout.Pane -> MaterialTheme.typography.headlineLarge
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
            modifier = Modifier.startupSharedBounds(emblemHandle, zIndexInOverlay = 2f)
        )
        Spacer(Modifier.height(if (welcome) 28.dp else 18.dp))
        accessory()
        Text(
            text = spec.title,
            modifier = Modifier
                .semantics { heading() }
                .startupSharedBounds(titleHandle, StartupSharedResize.ScaleText, zIndexInOverlay = 1f),
            style = titleStyle,
            fontWeight = if (clean) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = if (welcome) TextAlign.Center else TextAlign.Start,
            color = colors.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = spec.description,
            modifier = Modifier.startupSharedBounds(bodyHandle, StartupSharedResize.ScaleText),
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
