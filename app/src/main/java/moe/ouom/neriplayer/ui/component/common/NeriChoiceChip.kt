package moe.ouom.neriplayer.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

/**
 * Choice chip. Clean style drops the outline entirely: an ink-tinted pill at rest and
 * a filled accent pill when chosen, with the fill easing between the two.
 */
@Composable
fun NeriChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    multiSelect: Boolean = false
) {
    val selectionSemantics = Modifier.semantics {
        role = if (multiSelect) Role.Checkbox else Role.RadioButton
        if (multiSelect) {
            toggleableState = ToggleableState(selected)
        } else {
            this.selected = selected
        }
    }
    if (!NeriDesign.isClean) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(selectionSemantics),
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            // 对勾随选中态展开, chip 宽度跟着平滑变化而不是跳变
            leadingIcon = {
                AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(tween(160)) + expandHorizontally(NeriMotion.smooth()),
                    exit = fadeOut(tween(100)) + shrinkHorizontally(NeriMotion.smooth())
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = enabled,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = Color.Transparent
            )
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
            .then(selectionSemantics)
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
