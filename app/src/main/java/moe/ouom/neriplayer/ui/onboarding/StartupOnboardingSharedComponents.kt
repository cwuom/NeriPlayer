package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton

@Composable
internal fun StepHeader(icon: ImageVector, title: String, description: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = OnboardingControlShape,
            color = colors.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun OnboardingActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HapticOutlinedButton(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = 104.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
        shape = OnboardingControlShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun StatusPill(label: String, connected: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = statusPillBackground(connected)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = statusPillTextStyle(label.length),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = statusPillForeground(connected)
        )
    }
}

@Composable
private fun statusPillTextStyle(labelLength: Int): TextStyle {
    if (labelLength >= 14) return MaterialTheme.typography.labelSmall
    return standardStatusPillTextStyle(labelLength)
}

@Composable
private fun standardStatusPillTextStyle(labelLength: Int): TextStyle {
    return if (labelLength >= 10) MaterialTheme.typography.labelMedium
    else MaterialTheme.typography.labelLarge
}

@Composable
private fun statusPillBackground(connected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (connected) colors.primary.copy(alpha = 0.14f)
    else colors.outlineVariant.copy(alpha = 0.6f)
}

@Composable
private fun statusPillForeground(connected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (connected) colors.primary else colors.onSurfaceVariant
}

@Composable
internal fun HintCard(body: String) {
    HintCardSurface { HintCardBody(body) }
}

@Composable
internal fun HintCard(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    HintCardSurface {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        HintCardBody(body)
        content()
    }
}

@Composable
private fun HintCardBody(body: String) {
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun HintCardSurface(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}
