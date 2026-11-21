package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

private data class StartupAmbientAnchors(val first: Offset, val second: Offset, val third: Offset)

// 每个章节一套光斑落点, 换页时光斑缓慢漂移过去, 背景本身就是一条连续的线索
private fun startupAmbientAnchors(stepIndex: Int): StartupAmbientAnchors {
    val drift = (stepIndex % 3) * 0.06f
    return when (StartupStep.entries.getOrNull(stepIndex)?.chapter ?: StartupChapter.Look) {
        StartupChapter.Look -> StartupAmbientAnchors(
            Offset(0.18f + drift, 0.12f), Offset(0.92f, 0.3f - drift), Offset(0.32f, 0.92f)
        )
        StartupChapter.Privacy -> StartupAmbientAnchors(
            Offset(0.82f, 0.1f), Offset(0.1f, 0.46f), Offset(0.7f, 0.96f)
        )
        StartupChapter.Connect -> StartupAmbientAnchors(
            Offset(0.5f - drift, 0.06f), Offset(0.96f, 0.62f + drift), Offset(0.04f, 0.82f)
        )
        StartupChapter.Playback -> StartupAmbientAnchors(
            Offset(0.12f, 0.2f + drift), Offset(0.86f, 0.12f), Offset(0.56f, 0.9f)
        )
        StartupChapter.Personalize -> StartupAmbientAnchors(
            Offset(0.88f, 0.24f), Offset(0.2f + drift, 0.08f), Offset(0.4f, 0.86f)
        )
        StartupChapter.Ready -> StartupAmbientAnchors(
            Offset(0.5f, 0.18f), Offset(0.14f, 0.7f), Offset(0.9f, 0.76f)
        )
    }
}

@Composable
internal fun StartupAmbientBackdrop(
    stepIndex: Int,
    hasCustomBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val dark = colors.background.luminanceApprox() < 0.5f
    val anchors = startupAmbientAnchors(stepIndex)
    val drift = spring<Offset>(dampingRatio = 1f, stiffness = 18f)
    val first by animateOffsetAsState(anchors.first, drift, label = "startup_ambient_first")
    val second by animateOffsetAsState(anchors.second, drift, label = "startup_ambient_second")
    val third by animateOffsetAsState(anchors.third, drift, label = "startup_ambient_third")
    val phase by rememberInfiniteTransition(label = "startup_ambient").animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 16_000, easing = LinearEasing), RepeatMode.Restart),
        label = "startup_ambient_phase"
    )
    val strength = if (hasCustomBackground) 0.45f else 1f
    val palette = when {
        clean && dark -> listOf(colors.primary.copy(alpha = 0.2f), colors.tertiary.copy(alpha = 0.1f), colors.primary.copy(alpha = 0.08f))
        clean -> listOf(colors.primary.copy(alpha = 0.13f), colors.tertiary.copy(alpha = 0.07f), colors.primary.copy(alpha = 0.06f))
        dark -> listOf(colors.primary.copy(alpha = 0.22f), colors.tertiary.copy(alpha = 0.18f), colors.secondary.copy(alpha = 0.14f))
        else -> listOf(colors.primaryContainer.copy(alpha = 0.85f), colors.tertiaryContainer.copy(alpha = 0.7f), colors.secondaryContainer.copy(alpha = 0.7f))
    }.map { color -> color.copy(alpha = color.alpha * strength) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .drawBehind {
                val unit = max(size.width, size.height)
                fun blob(anchor: Offset, color: Color, radiusFactor: Float, wobble: Float) {
                    val center = Offset(
                        size.width * anchor.x + cos(phase + wobble) * unit * 0.035f,
                        size.height * anchor.y + sin(phase * 0.8f + wobble) * unit * 0.03f
                    )
                    val radius = unit * radiusFactor
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to color,
                            0.55f to color.copy(alpha = color.alpha * 0.35f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
                blob(first, palette[0], if (clean) 0.62f else 0.58f, 0f)
                blob(second, palette[1], if (clean) 0.48f else 0.5f, 2.1f)
                blob(third, palette[2], 0.46f, 4.2f)
            }
    )
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
internal fun startupChapterLabel(chapter: StartupChapter): String = stringResource(
    when (chapter) {
        StartupChapter.Look -> AppR.string.onboarding_chapter_look
        StartupChapter.Privacy -> AppR.string.onboarding_chapter_privacy
        StartupChapter.Connect -> AppR.string.onboarding_chapter_connect
        StartupChapter.Playback -> AppR.string.onboarding_chapter_playback
        StartupChapter.Personalize -> AppR.string.onboarding_chapter_personalize
        StartupChapter.Ready -> AppR.string.onboarding_chapter_ready
    }
)

@Composable
internal fun StartupProgressHeader(
    stepIndex: Int,
    stepCount: Int,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val chapter = StartupStep.entries.getOrNull(stepIndex)?.chapter ?: StartupChapter.Look
    Column(modifier = modifier.fillMaxWidth()) {
        StartupProgressTrack(stepIndex = stepIndex, stepCount = stepCount)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = chapter,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInVertically(spring(0.85f, 380f)) { height -> if (forward) height else -height } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(180)) { height -> if (forward) -height else height } + fadeOut(tween(120)))
                        .using(SizeTransform(clip = true))
                },
                label = "startup_chapter_label"
            ) { current ->
                Text(
                    text = startupChapterLabel(current),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    maxLines = 1
                )
            }
            Text(
                text = stringResource(AppR.string.onboarding_step_counter, stepIndex + 1, stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StartupProgressTrack(stepIndex: Int, stepCount: Int) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val steps = StartupStep.entries.take(stepCount)
    val barHeight = if (clean) 4.dp else 6.dp
    val description = stringResource(AppR.string.onboarding_step_counter, stepIndex + 1, stepCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                val chapterBreak = steps[index - 1].chapter != step.chapter
                Spacer(Modifier.width(if (chapterBreak) 10.dp else 4.dp))
            }
            val weight by animateFloatAsState(
                targetValue = if (index == stepIndex) 3.2f else 1f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 320f),
                label = "startup_progress_weight"
            )
            val color by animateColorAsState(
                targetValue = when {
                    index <= stepIndex -> colors.primary
                    clean -> colors.onSurface.copy(alpha = 0.1f)
                    else -> colors.secondaryContainer
                },
                animationSpec = tween(320),
                label = "startup_progress_color"
            )
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Immutable
internal data class StartupChoiceSummary(
    val choice: StartupChoice,
    val icon: ImageVector,
    val label: String
)

@Composable
internal fun StartupChoiceRail(
    currentStep: StartupStep,
    summaries: List<StartupChoiceSummary>,
    onChoiceClick: (StartupChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        summaries.forEach { summary ->
            AnimatedVisibility(
                visible = isStartupChoiceInRail(summary.choice, currentStep),
                enter = fadeIn(tween(220)) + expandHorizontally(spring(0.9f, 400f)),
                exit = fadeOut(tween(160)) + shrinkHorizontally(tween(260))
            ) {
                val handle = rememberStartupSharedHandle(StartupSharedKeys.choice(summary.choice), this)
                StartupChoicePill(
                    summary = summary,
                    onClick = { onChoiceClick(summary.choice) },
                    modifier = Modifier.startupSharedBounds(handle, zIndexInOverlay = 3f)
                )
            }
        }
    }
}

@Composable
private fun StartupChoicePill(
    summary: StartupChoiceSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val editDescription = stringResource(AppR.string.onboarding_choice_edit, summary.label)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 32.dp)
            .semantics {
                role = Role.Button
                contentDescription = editDescription
            },
        shape = if (clean) CircleShape else RoundedCornerShape(10.dp),
        color = if (clean) colors.surfaceContainerHigh.copy(alpha = 0.86f) else colors.secondaryContainer,
        contentColor = if (clean) colors.onSurface else colors.onSecondaryContainer,
        border = if (clean) BorderStroke(0.5.dp, colors.outlineVariant) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(summary.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = summary.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun StartupActionBar(
    showBack: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryProgress: Float? = null,
    primaryIcon: ImageVector? = Icons.AutoMirrored.Rounded.ArrowForward
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = showBack,
            enter = fadeIn(tween(200)) + expandHorizontally(spring(0.86f, 380f)),
            exit = fadeOut(tween(140)) + shrinkHorizontally(tween(220))
        ) {
            StartupBackButton(enabled = backEnabled, onClick = onBack)
        }
        StartupPrimaryButton(
            label = primaryLabel,
            enabled = primaryEnabled,
            onClick = onPrimary,
            progress = primaryProgress,
            icon = primaryIcon,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StartupBackButton(enabled: Boolean, onClick: () -> Unit) {
    val clean = NeriDesign.isClean
    val colors = MaterialTheme.colorScheme
    HapticOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(if (clean) 52.dp else 56.dp),
        shape = if (clean) CircleShape else RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(CoreCommonR.string.action_back),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            maxLines = 1
        )
    }
}

@Composable
private fun StartupPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    progress: Float?,
    icon: ImageVector?,
    modifier: Modifier = Modifier
) {
    val clean = NeriDesign.isClean
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Material 按下时圆角收紧, 简洁风格则整体轻微下沉
    val corner by animateDpAsState(
        targetValue = if (!clean && pressed) 12.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "startup_primary_corner"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (clean && pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "startup_primary_scale"
    )
    val shape = if (clean) CircleShape else RoundedCornerShape(corner)
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 1f,
        animationSpec = tween(durationMillis = 980, easing = LinearEasing),
        label = "startup_primary_progress"
    )
    Box(
        modifier = modifier
            .height(if (clean) 52.dp else 56.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        HapticButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
            interactionSource = interaction
        ) {
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (slideInVertically(spring(0.86f, 420f)) { height -> height / 2 } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(160)) { height -> -height / 2 } + fadeOut(tween(120)))
                        .using(SizeTransform(clip = false))
                },
                label = "startup_primary_label"
            ) { current ->
                Text(
                    text = current,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (icon != null && enabled) {
                Spacer(Modifier.width(8.dp))
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (progress != null && !enabled) {
            // 阅读倒计时: 进度从左向右铺满按钮, 读完才可点
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .drawBehind {
                        drawRect(
                            color = colors.primary.copy(alpha = 0.22f),
                            size = Size(size.width * animatedProgress, size.height)
                        )
                    }
            )
        }
    }
}

/** 滚动内容在页眉和操作栏处柔和淡出; 顶部只在滚动后出现, 不影响首屏的徽记 */
internal fun Modifier.startupEdgeFade(
    topStrength: () -> Float,
    bottomStrength: () -> Float,
    topHeight: Dp = 18.dp,
    bottomHeight: Dp = 28.dp
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = topStrength().coerceIn(0f, 1f)
        if (top > 0f) {
            val height = topHeight.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 1f - top),
                    1f to Color.Black,
                    startY = 0f,
                    endY = height
                ),
                size = Size(size.width, height),
                blendMode = BlendMode.DstIn
            )
        }
        val bottom = bottomStrength().coerceIn(0f, 1f)
        if (bottom > 0f) {
            val height = bottomHeight.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Black.copy(alpha = 1f - bottom),
                    startY = size.height - height,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height - height),
                size = Size(size.width, height),
                blendMode = BlendMode.DstIn
            )
        }
    }
