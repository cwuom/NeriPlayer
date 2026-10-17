package moe.ouom.neriplayer.ui.screen.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriMotion

private val ProbeRowShape = ContinuousRoundedCornerShape(14.dp)
private val ProbeCodeShape = ContinuousRoundedCornerShape(14.dp)

/** Scrolling page shared by every probe: a large title, one line of context, then sections. */
@Composable
internal fun DebugProbePage(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = miniPlayerHeight + 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        content()
    }
}

/** Caption above a card, in the grouped-list style; [trailing] hosts a spinner or a count. */
@Composable
internal fun DebugProbeSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        MiuixSettingsSectionCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun DebugProbeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    inRow: Boolean = false
) {
    MiuixSettingsTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = if (inRow) 4.dp else 12.dp, vertical = 4.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text)
    )
}

/** Two short fields side by side, e.g. part and CID or HL and GL. */
@Composable
internal fun DebugProbeFieldRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), content = content)
}

/**
 * A probe call as a compact row. The copy glyph tells the user the raw JSON lands on the
 * clipboard, so labels no longer need to repeat it.
 */
@Composable
internal fun DebugProbeAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    copiesResult: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(ProbeRowShape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (copiesResult) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
internal fun DebugProbeRunningIndicator(running: Boolean) {
    AnimatedVisibility(visible = running, enter = fadeIn(), exit = fadeOut()) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

/**
 * Status line plus the raw response in a scrollable code block. Long JSON stays inside the
 * block instead of stretching the page.
 */
@Composable
internal fun DebugProbeResult(
    title: String,
    status: String,
    running: Boolean,
    output: String,
    emptyOutput: String,
    summary: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val dotColor by animateColorAsState(
        targetValue = when {
            running -> colors.primary
            output.isNotBlank() -> Color(0xFF30D158)
            else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = NeriMotion.smooth(),
        label = "probe_status_dot"
    )
    DebugProbeSection(title = title, trailing = { DebugProbeRunningIndicator(running) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                status.ifBlank { stringResource(CoreCommonR.string.debug_probe_idle) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(
            visible = running,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(3.dp)
                    .clip(CircleShape)
            )
        }
        if (!summary.isNullOrBlank()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(ProbeCodeShape)
                .background(colors.onSurface.copy(alpha = 0.05f))
                .heightIn(min = 56.dp, max = 360.dp)
        ) {
            Text(
                text = output.ifBlank { emptyOutput },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (output.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
        if (actions != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                content = actions
            )
        }
    }
}
