package moe.ouom.neriplayer.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

private val DisclaimerCardShape = RoundedCornerShape(16.dp)
private val DisclaimerControlShape = RoundedCornerShape(12.dp)

@Composable
internal fun StartupDisclaimerContent(
    onAgree: () -> Unit,
    initialCountdownSeconds: Int
) {
    var countdown by remember(initialCountdownSeconds) {
        mutableIntStateOf(initialCountdownSeconds.coerceAtLeast(0))
    }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000L)
            countdown--
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                DisclaimerHeader()
                Spacer(Modifier.height(20.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DisclaimerFact(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.disclaimer_summary_content_title),
                        body = stringResource(R.string.disclaimer_summary_content_body)
                    )
                    DisclaimerFact(
                        icon = Icons.Outlined.Storage,
                        title = stringResource(R.string.disclaimer_summary_data_title),
                        body = stringResource(R.string.disclaimer_summary_data_body)
                    )
                    DisclaimerFact(
                        icon = Icons.Outlined.CloudSync,
                        title = stringResource(R.string.disclaimer_summary_sync_title),
                        body = stringResource(R.string.disclaimer_summary_sync_body)
                    )
                    HapticTextButton(
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            if (detailsExpanded) {
                                stringResource(R.string.disclaimer_hide_details)
                            } else {
                                stringResource(R.string.disclaimer_show_details)
                            }
                        )
                    }
                    AnimatedVisibility(
                        visible = detailsExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        DisclaimerDetails()
                    }
                }
                Spacer(Modifier.height(16.dp))
                HapticButton(
                    onClick = onAgree,
                    enabled = countdown == 0,
                    shape = DisclaimerControlShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (countdown == 0) {
                            stringResource(R.string.disclaimer_agree_countdown)
                        } else {
                            stringResource(R.string.disclaimer_read_countdown, countdown)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerHeader() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = DisclaimerControlShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.disclaimer_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.disclaimer_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.disclaimer_last_updated),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisclaimerFact(
    icon: ImageVector,
    title: String,
    body: String
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DisclaimerCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DisclaimerDetails() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section1_title),
            lines = listOf(stringResource(R.string.disclaimer_section1_body))
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section2_title),
            lines = listOf(
                stringResource(R.string.disclaimer_section2_bullet1),
                stringResource(R.string.disclaimer_section2_bullet2),
                stringResource(R.string.disclaimer_section2_bullet3),
                stringResource(R.string.disclaimer_section2_bullet4)
            )
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section3_title),
            lines = listOf(
                stringResource(R.string.disclaimer_section3_bullet1),
                stringResource(R.string.disclaimer_section3_bullet2),
                stringResource(R.string.disclaimer_section3_bullet3)
            )
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section4_title),
            lines = listOf(
                stringResource(R.string.disclaimer_section4_bullet1),
                stringResource(R.string.disclaimer_section4_bullet2),
                stringResource(R.string.disclaimer_section4_bullet3)
            )
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section5_title),
            lines = listOf(
                stringResource(R.string.disclaimer_section5_bullet1),
                stringResource(R.string.disclaimer_section5_bullet2),
                stringResource(R.string.disclaimer_section5_bullet3),
                stringResource(R.string.disclaimer_section5_bullet4),
                stringResource(R.string.disclaimer_section5_bullet5),
                stringResource(R.string.disclaimer_section5_bullet6),
                stringResource(R.string.disclaimer_section5_bullet7),
                stringResource(R.string.disclaimer_section5_bullet8),
                stringResource(R.string.disclaimer_section5_bullet9)
            )
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section6_title),
            lines = listOf(
                stringResource(R.string.disclaimer_section6_bullet1),
                stringResource(R.string.disclaimer_section6_bullet2),
                stringResource(R.string.disclaimer_section6_bullet3)
            )
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section7_title),
            lines = listOf(stringResource(R.string.disclaimer_section7_body))
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section8_title),
            lines = listOf(stringResource(R.string.disclaimer_section8_body))
        )
        DisclaimerDetailSection(
            title = stringResource(R.string.disclaimer_section9_title),
            lines = listOf(stringResource(R.string.disclaimer_section9_body))
        )
    }
}

@Composable
private fun DisclaimerDetailSection(title: String, lines: List<String>) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}
