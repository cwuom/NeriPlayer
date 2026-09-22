package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.XiaomiSuperIslandSettings
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.shizuku.ShizukuPermissionHelper
import moe.ouom.neriplayer.shizuku.ShizukuKeepAliveManager
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XiaomiSuperIslandLyricsSetting(
    autoSettingsRepository: AutoSettingsRepository,
    settingsRepository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setting = AutoSettingsSchema.lyrics.xiaomiSuperIslandLyricEnabled
    val enabled by autoSettingsRepository.xiaomiSuperIslandLyricEnabledFlow.collectAsState(
        initial = setting.defaultValue
    )
    var showSheet by remember { mutableStateOf(false) }
    var notificationAllowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var shizukuGranted by remember { mutableStateOf(ShizukuPermissionHelper.isPermissionGranted()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationAllowed = granted || notificationsAllowed(context)
    }

    fun setEnabled(nextEnabled: Boolean) {
        scope.launch {
            autoSettingsRepository.setXiaomiSuperIslandLyricEnabled(nextEnabled)
        }
        if (nextEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PermissionChecker.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(showSheet) {
        if (showSheet) {
            notificationAllowed = notificationsAllowed(context)
            shizukuGranted = ShizukuPermissionHelper.isPermissionGrantedWhenReady()
        }
    }

    AutoSettingSpecListItem(
        setting = setting,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixSettingsTextButton(onClick = { showSheet = true }) {
                    Text(stringResource(R.string.settings_xiaomi_super_island_configure))
                }
                MiuixSettingsSwitch(
                    checked = enabled,
                    onCheckedChange = ::setEnabled
                )
            }
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showSheet = true }
    )

    if (!showSheet) return

    DensityScaledModalBottomSheet(
        onDismissRequest = { showSheet = false },
        sheetState = sheetState
    ) {
        val settings by settingsRepository.xiaomiSuperIslandSettingsFlow.collectAsState(
            initial = XiaomiSuperIslandSettings()
        )
        val update: (XiaomiSuperIslandSettings) -> Unit = { next ->
            scope.launch { settingsRepository.setXiaomiSuperIslandSettings(next) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .bottomSheetScrollGuard()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_xiaomi_super_island_settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )

            SuperIslandSectionTitle(R.string.settings_xiaomi_super_island_display_section)
            SuperIslandChoiceRow(
                title = stringResource(R.string.settings_xiaomi_super_island_lyric_content),
                summary = stringResource(R.string.settings_xiaomi_super_island_lyric_content_summary),
                labels = listOf(
                    stringResource(R.string.lyrics_original),
                    stringResource(R.string.lyrics_translation),
                    stringResource(R.string.settings_xiaomi_super_island_pronunciation)
                ),
                selectedIndex = settings.lyricTextMode,
                onSelected = { update(settings.copy(lyricTextMode = it)) }
            )
            SuperIslandChoiceRow(
                title = stringResource(R.string.settings_xiaomi_super_island_lyric_mode),
                summary = stringResource(R.string.settings_xiaomi_super_island_lyric_mode_summary),
                labels = listOf(
                    stringResource(R.string.settings_xiaomi_super_island_mode_standard),
                    stringResource(R.string.settings_xiaomi_super_island_mode_full)
                ),
                selectedIndex = settings.lyricMode,
                onSelected = { update(settings.copy(lyricMode = it)) }
            )
            if (settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
                SuperIslandSwitchRow(
                    title = stringResource(R.string.settings_xiaomi_super_island_left_cover),
                    summary = stringResource(R.string.settings_xiaomi_super_island_left_cover_summary),
                    checked = settings.fullLyricShowLeftCover,
                    onCheckedChange = {
                        update(settings.copy(fullLyricShowLeftCover = it))
                    }
                )
            }
            SuperIslandSwitchRow(
                title = stringResource(R.string.settings_xiaomi_super_island_scrolling),
                summary = stringResource(R.string.settings_xiaomi_super_island_scrolling_summary),
                checked = settings.scrollingEnabled,
                enabled = settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_STANDARD,
                onCheckedChange = { update(settings.copy(scrollingEnabled = it)) }
            )
            SuperIslandIntSlider(
                title = stringResource(R.string.settings_xiaomi_super_island_right_limit),
                summary = stringResource(R.string.settings_xiaomi_super_island_text_limit_summary),
                value = settings.rightTextChars,
                valueRange = 6..14,
                valueText = stringResource(R.string.settings_xiaomi_super_island_chars, settings.rightTextChars),
                onValueChange = { update(settings.copy(rightTextChars = it)) }
            )
            if (settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
                val leftValue = if (settings.fullLyricShowLeftCover) {
                    settings.leftWithCoverTextChars
                } else {
                    settings.leftWithoutCoverTextChars
                }
                val leftRange = if (settings.fullLyricShowLeftCover) 4..10 else 6..14
                SuperIslandIntSlider(
                    title = stringResource(R.string.settings_xiaomi_super_island_left_limit),
                    summary = stringResource(R.string.settings_xiaomi_super_island_text_limit_summary),
                    value = leftValue,
                    valueRange = leftRange,
                    valueText = stringResource(R.string.settings_xiaomi_super_island_chars, leftValue),
                    onValueChange = { value ->
                        update(
                            if (settings.fullLyricShowLeftCover) {
                                settings.copy(leftWithCoverTextChars = value)
                            } else {
                                settings.copy(leftWithoutCoverTextChars = value)
                            }
                        )
                    }
                )
            }
            SuperIslandSwitchRow(
                title = stringResource(R.string.settings_xiaomi_super_island_colorize),
                summary = stringResource(R.string.settings_xiaomi_super_island_colorize_summary),
                checked = settings.textColorEnabled,
                onCheckedChange = {
                    update(settings.copy(textColorEnabled = it, progressColorEnabled = it))
                }
            )
            if (settings.textColorEnabled) {
                SuperIslandChoiceRow(
                    title = stringResource(R.string.settings_xiaomi_super_island_color_source),
                    summary = stringResource(R.string.settings_xiaomi_super_island_color_source_summary),
                        labels = listOf(
                        stringResource(R.string.settings_xiaomi_super_island_color_album),
                        stringResource(R.string.settings_xiaomi_super_island_color_custom)
                    ),
                    selectedIndex = settings.colorSource,
                    onSelected = { update(settings.copy(colorSource = it)) }
                )
                if (settings.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM) {
                    FloatingLyricsColorPicker(
                        titleRes = R.string.settings_xiaomi_super_island_custom_color,
                        icon = Icons.Outlined.Colorize,
                        selectedColorHex = String.format("%06X", 0xFFFFFF and settings.customColor),
                        onColorSelected = { hex ->
                            val color = android.graphics.Color.parseColor("#$hex")
                            update(settings.copy(customColor = ComposeColor(color).toArgb()))
                        }
                    )
                }
            }

            SuperIslandSectionTitle(R.string.settings_xiaomi_super_island_notification_section)
            SuperIslandSwitchRow(
                title = stringResource(R.string.settings_xiaomi_super_island_progress_color),
                checked = settings.progressColorEnabled,
                onCheckedChange = { update(settings.copy(progressColorEnabled = it)) }
            )
            SuperIslandChoiceRow(
                title = stringResource(R.string.settings_xiaomi_super_island_actions),
                summary = stringResource(R.string.settings_xiaomi_super_island_actions_summary),
                labels = listOf(
                    stringResource(R.string.settings_xiaomi_super_island_actions_off),
                    stringResource(R.string.settings_xiaomi_super_island_actions_media)
                ),
                selectedIndex = settings.actionStyle,
                onSelected = { update(settings.copy(actionStyle = it)) }
            )
            if (settings.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS) {
                SuperIslandChoiceRow(
                    title = stringResource(R.string.settings_xiaomi_super_island_button_layout),
                    summary = stringResource(R.string.settings_xiaomi_super_island_button_layout_summary),
                    labels = listOf(
                        stringResource(R.string.settings_xiaomi_super_island_buttons_two),
                        stringResource(R.string.settings_xiaomi_super_island_buttons_three)
                    ),
                    selectedIndex = settings.mediaButtonLayout,
                    onSelected = { update(settings.copy(mediaButtonLayout = it)) }
                )
            }
            SuperIslandSwitchRow(
                title = stringResource(R.string.settings_xiaomi_super_island_share),
                checked = settings.shareEnabled,
                onCheckedChange = { update(settings.copy(shareEnabled = it)) }
            )
            if (settings.shareEnabled) {
                SuperIslandChoiceRow(
                    title = stringResource(R.string.settings_xiaomi_super_island_share_format),
                    summary = stringResource(R.string.settings_xiaomi_super_island_share_format_summary),
                    labels = listOf(
                        stringResource(R.string.settings_xiaomi_super_island_share_lyric_song),
                        stringResource(R.string.settings_xiaomi_super_island_share_inline),
                        stringResource(R.string.settings_xiaomi_super_island_share_artist_song)
                    ),
                    selectedIndex = settings.shareFormat,
                    onSelected = { update(settings.copy(shareFormat = it)) }
                )
            }

            SuperIslandSectionTitle(R.string.settings_xiaomi_super_island_compat_section)
            SuperIslandChoiceRow(
                title = stringResource(R.string.settings_xiaomi_super_island_xmsf_mode),
                summary = stringResource(
                    when (settings.xmsfBypassMode) {
                        XiaomiSuperIslandSettings.XMSF_MODE_DISABLED ->
                            R.string.settings_xiaomi_super_island_xmsf_disabled_summary
                        XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM ->
                            R.string.settings_xiaomi_super_island_xmsf_custom_summary
                        XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE ->
                            R.string.settings_xiaomi_super_island_xmsf_aggressive_summary
                        else -> R.string.settings_xiaomi_super_island_xmsf_standard_summary
                    }
                ),
                labels = listOf(
                    stringResource(R.string.settings_xiaomi_super_island_xmsf_disabled),
                    stringResource(R.string.settings_xiaomi_super_island_xmsf_standard),
                    stringResource(R.string.settings_xiaomi_super_island_xmsf_custom),
                    stringResource(R.string.settings_xiaomi_super_island_xmsf_aggressive)
                ),
                selectedIndex = settings.xmsfBypassMode,
                onSelected = { update(settings.copy(xmsfBypassMode = it)) }
            )
            if (settings.xmsfBypassMode == XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM) {
                val durationStep = (settings.xmsfCustomDurationMs / 50).coerceIn(2, 10)
                SuperIslandIntSlider(
                    title = stringResource(R.string.settings_xiaomi_super_island_xmsf_duration),
                    summary = stringResource(R.string.settings_xiaomi_super_island_xmsf_duration_summary),
                    value = durationStep,
                    valueRange = 2..10,
                    valueText = stringResource(
                        R.string.settings_xiaomi_super_island_duration_ms,
                        durationStep * 50
                    ),
                    onValueChange = { update(settings.copy(xmsfCustomDurationMs = it * 50)) }
                )
            }
            SuperIslandChoiceRow(
                title = stringResource(R.string.settings_xiaomi_super_island_dismiss_delay),
                summary = stringResource(R.string.settings_xiaomi_super_island_dismiss_delay_summary),
                labels = listOf(
                    stringResource(R.string.settings_xiaomi_super_island_dismiss_immediate),
                    stringResource(R.string.settings_xiaomi_super_island_dismiss_one),
                    stringResource(R.string.settings_xiaomi_super_island_dismiss_three),
                    stringResource(R.string.settings_xiaomi_super_island_dismiss_five)
                ),
                selectedIndex = listOf(0, 1_000, 3_000, 5_000)
                    .indexOf(settings.dismissDelayMs)
                    .coerceAtLeast(0),
                onSelected = { index ->
                    update(settings.copy(dismissDelayMs = listOf(0, 1_000, 3_000, 5_000)[index]))
                }
            )

            SuperIslandSectionTitle(R.string.settings_xiaomi_super_island_status_section)
            SuperIslandStatusRow(
                title = stringResource(R.string.settings_xiaomi_super_island_notification_status),
                summary = stringResource(
                    if (notificationAllowed) {
                        R.string.settings_xiaomi_super_island_notification_status_granted
                    } else {
                        R.string.settings_xiaomi_super_island_notification_status_missing
                    }
                ),
                actionLabel = stringResource(R.string.settings_xiaomi_super_island_open_notification_settings),
                onAction = { openNotificationSettings(context) }
            )
            SuperIslandStatusRow(
                title = stringResource(R.string.settings_xiaomi_super_island_shizuku_status),
                summary = stringResource(
                    if (shizukuGranted) {
                        R.string.settings_xiaomi_super_island_shizuku_status_granted
                    } else {
                        R.string.settings_xiaomi_super_island_shizuku_status_missing
                    }
                ),
                actionLabel = stringResource(R.string.settings_xiaomi_super_island_request_shizuku),
                onAction = {
                    scope.launch {
                        // This is an explicit user action. Even if Android still reports a
                        // stale package permission grant after a Shizuku restart/reinstall,
                        // send one real request so Shizuku Manager can register NeriPlayer in
                        // its authorized-app list again. Playback never calls this forced path.
                        shizukuGranted = ShizukuPermissionHelper.ensurePermission(forceRequest = true)
                        if (shizukuGranted) {
                            ShizukuKeepAliveManager.ensureBound(context)
                        }
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SuperIslandSectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun SuperIslandChoiceRow(
    title: String,
    summary: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    if (labels.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        labels.forEachIndexed { index, label ->
            MiuixSettingsChoiceRow(
                title = label,
                selected = index == selectedIndex.coerceIn(labels.indices),
                onClick = { onSelected(index) }
            )
        }
    }
}

@Composable
private fun SuperIslandSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = ComposeColor.Transparent)
    )
}

@Composable
private fun SuperIslandIntSlider(
    title: String,
    summary: String,
    value: Int,
    valueRange: IntRange,
    valueText: String,
    onValueChange: (Int) -> Unit
) {
    val safeValue = value.coerceIn(valueRange)
    var pendingValue by remember(value) { mutableFloatStateOf(safeValue.toFloat()) }
    LaunchedEffect(value) {
        pendingValue = safeValue.toFloat()
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                MiuixSettingsSlider(
                    value = pendingValue,
                    onValueChange = { pendingValue = it.roundToInt().toFloat() },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                    onValueChangeFinished = { onValueChange(pendingValue.roundToInt()) }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = ComposeColor.Transparent)
    )
}

@Composable
private fun SuperIslandStatusRow(
    title: String,
    summary: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            MiuixSettingsTextButton(onClick = onAction) {
                Text(actionLabel)
            }
        },
        colors = ListItemDefaults.colors(containerColor = ComposeColor.Transparent)
    )
}

private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val packageUri = android.net.Uri.parse("package:${context.packageName}")
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.recoverCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
