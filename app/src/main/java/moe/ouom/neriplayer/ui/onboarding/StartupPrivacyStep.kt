package moe.ouom.neriplayer.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign

private data class StartupPrivacyPrinciple(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int
)

private val StartupPrivacyPrinciples = listOf(
    StartupPrivacyPrinciple(Icons.Rounded.LibraryMusic, AppR.string.disclaimer_summary_content_title, AppR.string.disclaimer_summary_content_body),
    StartupPrivacyPrinciple(Icons.Rounded.PhonelinkLock, AppR.string.disclaimer_summary_data_title, AppR.string.disclaimer_summary_data_body),
    StartupPrivacyPrinciple(Icons.Rounded.CloudSync, AppR.string.disclaimer_summary_sync_title, AppR.string.disclaimer_summary_sync_body),
    StartupPrivacyPrinciple(Icons.Rounded.TouchApp, AppR.string.disclaimer_summary_permission_title, AppR.string.disclaimer_summary_permission_body)
)

@Composable
internal fun StartupPrivacyStepBody(
    accepted: Boolean,
    policyOpen: Boolean,
    onOpenPolicy: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(AppR.string.disclaimer_last_updated),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant
            )
            if (accepted) {
                StartupPrivacyBadge(text = stringResource(AppR.string.onboarding_privacy_accepted))
            }
        }
        StartupPrivacyPrinciples.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { principle ->
                    StartupPrivacyPrincipleCard(
                        principle = principle,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
        StartupPrivacyChangesCard()
        StartupPrivacyPolicyEntry(policyOpen = policyOpen, onOpenPolicy = onOpenPolicy)
    }
}

@Composable
private fun StartupPrivacyBadge(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(colors.primary.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = colors.primary)
    }
}

@Composable
private fun StartupPrivacyPrincipleCard(
    principle: StartupPrivacyPrinciple,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    OnboardingGlassSurface(
        modifier = modifier,
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (clean) colors.primary.copy(alpha = 0.12f) else colors.primaryContainer,
                        if (clean) ContinuousRoundedCornerShape(12.dp) else RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    principle.icon,
                    contentDescription = null,
                    tint = if (clean) colors.primary else colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = stringResource(principle.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = stringResource(principle.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StartupPrivacyChangesCard() {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = if (clean) colors.tertiary.copy(alpha = 0.08f) else colors.tertiaryContainer.copy(alpha = 0.7f),
        contentColor = if (clean) colors.onSurface else colors.onTertiaryContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(AppR.string.disclaimer_changes_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            listOf(
                AppR.string.disclaimer_changes_item1,
                AppR.string.disclaimer_changes_item2,
                AppR.string.disclaimer_changes_item3
            ).forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(5.dp)
                            .background(colors.tertiary, CircleShape)
                    )
                    Text(text = stringResource(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StartupPrivacyPolicyEntry(
    policyOpen: Boolean,
    onOpenPolicy: () -> Unit
) {
    val density = LocalDensity.current
    var entryHeightPx by remember { mutableIntStateOf(0) }
    // 卡片飞向全屏时保留原位高度, 下方内容不跟着跳动
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = with(density) { entryHeightPx.toDp() })
    ) {
        AnimatedVisibility(
            visible = !policyOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160))
        ) {
            val handle = rememberStartupSharedHandle(StartupSharedKeys.PrivacyPolicy, this)
            StartupPrivacyPolicyCard(
                onClick = onOpenPolicy,
                modifier = Modifier
                    .onSizeChanged { size -> if (size.height > 0) entryHeightPx = size.height }
                    .startupSharedBounds(handle, zIndexInOverlay = 4f, opaqueContainer = true)
            )
        }
    }
}

@Composable
private fun StartupPrivacyPolicyCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(OnboardingCardShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh,
        border = if (clean) BorderStroke(0.5.dp, colors.outlineVariant) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = colors.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(AppR.string.disclaimer_show_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_details_entry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = colors.onSurfaceVariant
            )
        }
    }
}

private data class StartupPolicySection(val titleRes: Int, val lineRes: List<Int>)

private val StartupPolicySections = listOf(
    StartupPolicySection(AppR.string.disclaimer_section1_title, listOf(AppR.string.disclaimer_section1_body)),
    StartupPolicySection(
        AppR.string.disclaimer_section2_title,
        listOf(
            AppR.string.disclaimer_section2_bullet1,
            AppR.string.disclaimer_section2_bullet2,
            AppR.string.disclaimer_section2_bullet3,
            AppR.string.disclaimer_section2_bullet4
        )
    ),
    StartupPolicySection(
        AppR.string.disclaimer_section3_title,
        listOf(
            AppR.string.disclaimer_section3_bullet1,
            AppR.string.disclaimer_section3_bullet2,
            AppR.string.disclaimer_section3_bullet3,
            AppR.string.disclaimer_section3_bullet4
        )
    ),
    StartupPolicySection(
        AppR.string.disclaimer_section4_title,
        listOf(
            AppR.string.disclaimer_section4_bullet1,
            AppR.string.disclaimer_section4_bullet2,
            AppR.string.disclaimer_section4_bullet3,
            AppR.string.disclaimer_section4_bullet4,
            AppR.string.disclaimer_section4_bullet5
        )
    ),
    StartupPolicySection(
        AppR.string.disclaimer_section5_title,
        listOf(
            AppR.string.disclaimer_section5_bullet1,
            AppR.string.disclaimer_section5_bullet2,
            AppR.string.disclaimer_section5_bullet3,
            AppR.string.disclaimer_section5_bullet4,
            AppR.string.disclaimer_section5_bullet5,
            AppR.string.disclaimer_section5_bullet6,
            AppR.string.disclaimer_section5_bullet7,
            AppR.string.disclaimer_section5_bullet8,
            AppR.string.disclaimer_section5_bullet9
        )
    ),
    StartupPolicySection(
        AppR.string.disclaimer_section6_title,
        listOf(
            AppR.string.disclaimer_section6_bullet1,
            AppR.string.disclaimer_section6_bullet2,
            AppR.string.disclaimer_section6_bullet3
        )
    ),
    StartupPolicySection(AppR.string.disclaimer_section7_title, listOf(AppR.string.disclaimer_section7_body)),
    StartupPolicySection(AppR.string.disclaimer_section8_title, listOf(AppR.string.disclaimer_section8_body)),
    StartupPolicySection(AppR.string.disclaimer_section9_title, listOf(AppR.string.disclaimer_section9_body))
)

/** 完整说明从入口卡片做容器变换展开成全屏阅读层, 底部保留同一个同意按钮 */
@Composable
internal fun StartupPrivacyPolicyOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    agreeLabel: String,
    agreeEnabled: Boolean,
    agreeProgress: Float?,
    onAgree: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = NeriDesign.tokens
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1)),
        exit = fadeOut(tween(1))
    ) {
        val handle = rememberStartupSharedHandle(StartupSharedKeys.PrivacyPolicy, this)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .startupSharedBounds(handle, zIndexInOverlay = 4f, opaqueContainer = true)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = tokens.dialogShape,
                color = colors.surfaceContainerLow,
                border = if (tokens.isClean) BorderStroke(0.5.dp, colors.outlineVariant) else null
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(AppR.string.disclaimer_title),
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                text = stringResource(AppR.string.disclaimer_last_updated),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                        HapticIconButton(onClick = onClose) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(AppR.string.disclaimer_hide_details)
                            )
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Text(
                            text = stringResource(AppR.string.disclaimer_intro),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface
                        )
                        StartupPolicySections.forEachIndexed { index, section ->
                            StartupPolicySectionBlock(index = index, section = section)
                        }
                    }
                    Box(modifier = Modifier.padding(16.dp)) {
                        StartupActionBar(
                            showBack = false,
                            backEnabled = false,
                            onBack = {},
                            primaryLabel = agreeLabel,
                            primaryEnabled = agreeEnabled,
                            onPrimary = onAgree,
                            primaryProgress = agreeProgress,
                            primaryIcon = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupPolicySectionBlock(index: Int, section: StartupPolicySection) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(AppR.string.disclaimer_section_number, index + 1),
            modifier = Modifier
                .background(colors.primary.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            section.lineRes.forEach { line ->
                Text(
                    text = stringResource(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun startupPrivacyAgreeLabel(accepted: Boolean, remainingSeconds: Int): String = when {
    accepted -> stringResource(AppR.string.onboarding_action_next)
    remainingSeconds > 0 -> stringResource(AppR.string.disclaimer_read_countdown, remainingSeconds)
    else -> stringResource(AppR.string.disclaimer_agree_countdown)
}
