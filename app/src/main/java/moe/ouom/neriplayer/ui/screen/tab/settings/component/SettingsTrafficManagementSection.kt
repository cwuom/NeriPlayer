package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.traffic.TrafficStatsSummary
import moe.ouom.neriplayer.data.traffic.aggregateTrafficStatsForPeriod
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.ui.screen.StatsPeriodSelector
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun SettingsTrafficManagementSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dailyStats by AppContainer.trafficStatsRepo.dailyStatsFlow.collectAsState()
    val highRiskPromptEnabled by AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow
        .collectAsState(initial = true)
    var selectedPeriod by rememberSaveable { mutableStateOf(PlaybackStatsPeriod.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }
    val summary = remember(dailyStats, selectedPeriod) {
        aggregateTrafficStatsForPeriod(dailyStats, selectedPeriod)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatsPeriodSelector(
            selectedPeriod = selectedPeriod,
            onPeriodSelected = { selectedPeriod = it }
        )

        if (summary.hasTrafficData) {
            TrafficStatsCard(summary = summary)
        } else {
            TrafficEmptyCard()
        }

        AutoSettingsListItem(
            setting = AutoSettingsMetadata.requireSetting(
                AutoSettingsKeys.MOBILE_DATA_HIGH_RISK_PROMPT_ENABLED
            ),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.settings_mobile_data_high_risk_prompt),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            trailingContent = {
                MiuixSettingsSwitch(
                    checked = highRiskPromptEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            AppContainer.settingsRepo.setMobileDataHighRiskPromptEnabled(enabled)
                        }
                    }
                )
            },
            onClick = {
                scope.launch {
                    AppContainer.settingsRepo.setMobileDataHighRiskPromptEnabled(!highRiskPromptEnabled)
                }
            }
        )

        MiuixSettingsOutlinedButton(
            onClick = { showClearDialog = true },
            enabled = dailyStats.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(stringResource(R.string.traffic_stats_clear))
        }
    }

    if (showClearDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.traffic_stats_clear_title)) },
            text = { Text(stringResource(R.string.traffic_stats_clear_message)) },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        AppContainer.trafficStatsRepo.clearAll()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TrafficStatsCard(summary: TrafficStatsSummary) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Analytics,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.traffic_stats_overview),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TrafficMetricRow(
                label = stringResource(R.string.traffic_total_network),
                value = formatTrafficBytes(context, summary.networkBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_wifi),
                value = formatTrafficBytes(context, summary.wifiBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_mobile),
                value = formatTrafficBytes(context, summary.mobileBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_roaming),
                value = formatTrafficBytes(context, summary.roamingBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_playback_network),
                value = formatTrafficBytes(context, summary.playbackNetworkBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_download_network),
                value = formatTrafficBytes(context, summary.downloadNetworkBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_cache_hit_bytes),
                value = formatTrafficBytes(context, summary.cacheHitBytes)
            )
            TrafficMetricRow(
                label = stringResource(R.string.traffic_cache_hit_rate),
                value = formatPercent(summary.cacheHitRate)
            )
        }
    }
}

@Composable
private fun TrafficEmptyCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = stringResource(R.string.traffic_period_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrafficMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatTrafficBytes(
    context: android.content.Context,
    bytes: Long
): String {
    return Formatter.formatFileSize(context, bytes.coerceAtLeast(0L))
}

private fun formatPercent(rate: Float): String {
    return String.format(Locale.getDefault(), "%.1f%%", rate.coerceIn(0f, 1f) * 100f)
}
