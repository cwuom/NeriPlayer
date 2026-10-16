package moe.ouom.neriplayer.ui.component.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

/**
 * Single-choice chip. Clean style drops the outline entirely: an ink-tinted pill at rest and
 * a filled accent pill when chosen, with the fill easing between the two.
 */
@Composable
fun NeriChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (!NeriDesign.isClean) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            label = { Text(label) }
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    val restFill = if (LocalHasCustomBackground.current) {
        colors.surfaceContainerHighest.copy(alpha = 0.55f)
    } else {
        colors.onSurface.copy(alpha = 0.06f)
    }
    val fill by animateColorAsState(
        targetValue = when {
            selected && enabled -> colors.primary
            selected -> colors.onSurface.copy(alpha = 0.12f)
            else -> restFill
        },
        animationSpec = NeriMotion.smooth(),
        label = "neri_choice_chip_fill"
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            selected -> colors.onPrimary
            else -> colors.onSurface
        },
        animationSpec = NeriMotion.smooth(),
        label = "neri_choice_chip_content"
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(CircleShape)
            .background(fill)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
