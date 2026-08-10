package moe.ouom.neriplayer.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.startup.StartupStage
import androidx.compose.ui.res.stringResource

internal const val STARTUP_STAGE_ENTER_DURATION_MILLIS = 620
internal const val STARTUP_STAGE_CONTENT_DELAY_MILLIS = 520L
private const val STARTUP_STAGE_CONTENT_FADE_DURATION_MILLIS = 260

internal fun shouldDeferStartupStageContent(
    stage: StartupStage,
    previousStage: StartupStage? = null
): Boolean = stage == StartupStage.Onboarding && previousStage != StartupStage.Main

@Composable
internal fun StartupStageContentGate(
    stage: StartupStage,
    previousStage: StartupStage? = null,
    content: @Composable () -> Unit
) {
    val shouldDefer = remember(stage) {
        shouldDeferStartupStageContent(stage, previousStage)
    }
    var contentReady by remember(stage) { mutableStateOf(!shouldDefer) }

    LaunchedEffect(stage) {
        if (!shouldDefer) {
            contentReady = true
            return@LaunchedEffect
        }
        contentReady = false
        delay(STARTUP_STAGE_CONTENT_DELAY_MILLIS)
        contentReady = true
    }

    AnimatedContent(
        targetState = contentReady,
        transitionSpec = {
            (fadeIn(
                animationSpec = tween(
                    STARTUP_STAGE_CONTENT_FADE_DURATION_MILLIS,
                    easing = FastOutSlowInEasing
                )
            ) + scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(
                    STARTUP_STAGE_CONTENT_FADE_DURATION_MILLIS,
                    easing = FastOutSlowInEasing
                )
            )) togetherWith (fadeOut(
                animationSpec = tween(
                    STARTUP_STAGE_CONTENT_FADE_DURATION_MILLIS / 2,
                    easing = FastOutSlowInEasing
                )
            ) + scaleOut(
                targetScale = 1.01f,
                animationSpec = tween(
                    STARTUP_STAGE_CONTENT_FADE_DURATION_MILLIS / 2,
                    easing = FastOutSlowInEasing
                )
            )) using SizeTransform(clip = false)
        },
        label = "startup_stage_content_gate"
    ) { ready ->
        if (ready) {
            content()
        } else {
            StartupStageTransitionPlaceholder(stage = stage)
        }
    }
}

@Composable
private fun StartupStageTransitionPlaceholder(stage: StartupStage) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
        contentColor = colors.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (stage == StartupStage.Onboarding) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colors.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { 1f / 9f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(18.dp))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}
