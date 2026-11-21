package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StartupFinishStepBody(
    summaries: List<StartupChoiceSummary>,
    onChoiceClick: (StartupChoice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(AppR.string.onboarding_finish_summary_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        // 顶部胶囊在完成页落成三张卡片, 一路的选择在这里收束
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            summaries.forEach { summary ->
                StartupFinishChoiceTile(
                    summary = summary,
                    onClick = { onChoiceClick(summary.choice) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        Text(
            text = stringResource(AppR.string.onboarding_learning_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(AppR.string.onboarding_learning_desc),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StartupLearningGuideContent()
    }
}

private data class StartupConfetti(
    val angle: Float,
    val speed: Float,
    val spin: Float,
    val width: Float,
    val height: Float,
    val colorIndex: Int
)

/** 进入应用前的一次彩带迸发, 从底部操作区向上散开再受重力回落 */
@Composable
internal fun StartupCelebrationBurst(active: Boolean, origin: Offset?) {
    if (!active) return
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.tertiary, colors.secondary, colors.primaryContainer, colors.error)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 1_150, easing = LinearOutSlowInEasing))
    }
    val confetti = remember {
        val random = Random(seed = 20261121)
        List(42) {
            StartupConfetti(
                angle = (-PI.toFloat() * (0.12f + random.nextFloat() * 0.76f)),
                speed = 0.45f + random.nextFloat() * 0.75f,
                spin = (random.nextFloat() - 0.5f) * 4f,
                width = 6f + random.nextFloat() * 8f,
                height = 10f + random.nextFloat() * 10f,
                colorIndex = random.nextInt(5)
            )
        }
    }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOffset = it.positionInWindow() }
            .clearAndSetSemantics { }
    ) {
        val t = progress.value
        val center = origin?.minus(rootOffset) ?: Offset(size.width / 2f, size.height * 0.88f)
        val glowRadius = size.maxDimension * (0.15f + 0.55f * t)
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.primary.copy(alpha = 0.28f * (1f - t)),
                1f to Color.Transparent,
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )
        val travel = 1f - (1f - t) * (1f - t)
        confetti.forEach { piece ->
            val distance = piece.speed * size.minDimension * 0.9f * travel
            val gravity = size.height * 0.32f * t * t
            val position = center + Offset(cos(piece.angle) * distance, sin(piece.angle) * distance + gravity)
            val alpha = (1f - t * t).coerceIn(0f, 1f)
            val pieceSize = Size(piece.width.dp.toPx() * 0.6f, piece.height.dp.toPx() * 0.6f)
            rotate(degrees = piece.spin * 360f * t, pivot = position) {
                drawRoundRect(
                    color = palette[piece.colorIndex].copy(alpha = alpha),
                    topLeft = position - Offset(pieceSize.width / 2f, pieceSize.height / 2f),
                    size = pieceSize,
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun StartupFinishChoiceTile(
    summary: StartupChoiceSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val editDescription = stringResource(AppR.string.onboarding_choice_edit, summary.label)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Button
            contentDescription = editDescription
        },
        shape = OnboardingCardShape,
        color = if (clean) colors.surfaceContainerHigh else colors.secondaryContainer,
        contentColor = if (clean) colors.onSurface else colors.onSecondaryContainer,
        border = if (clean) BorderStroke(0.5.dp, colors.outlineVariant) else null
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colors.primary.copy(alpha = if (clean) 0.12f else 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(summary.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                text = stringResource(
                    when (summary.choice) {
                        StartupChoice.Language -> AppR.string.onboarding_finish_language
                        StartupChoice.Style -> AppR.string.onboarding_finish_style
                        StartupChoice.Motion -> AppR.string.onboarding_finish_motion
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = summary.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
