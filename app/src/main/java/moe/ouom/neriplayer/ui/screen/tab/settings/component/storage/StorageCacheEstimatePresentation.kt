package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.theme.NeriMotion

@Composable
internal fun AnimatedCacheEstimateText(
    value: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    alignment: Alignment = Alignment.CenterStart
) {
    Box(
        modifier.clearAndSetSemantics { text = AnnotatedString(value) },
        contentAlignment = alignment
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(tween(240, easing = NeriMotion.EmphasizedDecelerate)) +
                    slideInVertically(NeriMotion.smooth()) { it / 8 })
                    .togetherWith(fadeOut(tween(100, easing = NeriMotion.EmphasizedAccelerateSoft)))
                    .using(SizeTransform(clip = false) { _, _ -> NeriMotion.smooth() })
            },
            contentAlignment = alignment,
            label = "cache_estimate_value"
        ) { visibleValue ->
            Text(visibleValue, style = style, color = color)
        }
    }
}

@Composable
internal fun StorageCacheEstimateNotice(isEstimating: Boolean, isUnavailable: Boolean) {
    val messages = listOf(
        stringResource(CoreCommonR.string.storage_cleanup_size_notice),
        stringResource(CoreCommonR.string.storage_cleanup_estimating),
        stringResource(CoreCommonR.string.storage_cleanup_estimate_unavailable)
    )
    val message = when {
        isEstimating -> messages[1]
        isUnavailable -> messages[2]
        else -> messages[0]
    }
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        // 按最长提示预留高度，重算时滑块不会被长短不同的文案推走
        messages.forEach { reserved ->
            Text(reserved, Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics {}, style = style)
        }
        AnimatedCacheEstimateText(
            value = message,
            style = style,
            modifier = Modifier.fillMaxWidth().testTag("cache_estimate_notice"),
            color = color
        )
    }
}
