package moe.ouom.neriplayer.ui.screen.debug

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.debug/DebugHomeScreen
 * Updated: 2026/3/23
 */

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign

enum class DebugCrashTestType(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int
) {
    JvmHandled(
        titleRes = CoreCommonR.string.debug_test_exception_jvm_handled,
        descriptionRes = CoreCommonR.string.debug_test_exception_jvm_handled_desc
    ),
    JvmUncaughtMain(
        titleRes = CoreCommonR.string.debug_test_exception_jvm_uncaught_main,
        descriptionRes = CoreCommonR.string.debug_test_exception_jvm_uncaught_main_desc
    ),
    JvmUncaughtWorker(
        titleRes = CoreCommonR.string.debug_test_exception_jvm_uncaught_worker,
        descriptionRes = CoreCommonR.string.debug_test_exception_jvm_uncaught_worker_desc
    ),
    MainThreadAnr(
        titleRes = CoreCommonR.string.debug_test_exception_anr,
        descriptionRes = CoreCommonR.string.debug_test_exception_anr_desc
    ),
    NativeSigSegv(
        titleRes = CoreCommonR.string.debug_test_exception_native_sigsegv,
        descriptionRes = CoreCommonR.string.debug_test_exception_native_sigsegv_desc
    ),
    NativeSigAbrt(
        titleRes = CoreCommonR.string.debug_test_exception_native_sigabrt,
        descriptionRes = CoreCommonR.string.debug_test_exception_native_sigabrt_desc
    ),
}

private val DebugIndigo = Color(0xFF5E5CE6)
private val DebugRed = Color(0xFFFF453A)
private val DebugPink = Color(0xFFFF375F)
private val DebugGreen = Color(0xFF30D158)
private val DebugBlue = Color(0xFF0A84FF)
private val DebugTeal = Color(0xFF30B0C7)
private val DebugOrange = Color(0xFFFF9F0A)
private val DebugGraphite = Color(0xFF8E8E93)

/**
 * Developer tools home, laid out like a settings page: a summary header, then grouped cards
 * for platform probes, diagnostics and stability tests. Destructive actions sit apart at the
 * end in the error tint so they cannot be hit by habit.
 */
@Composable
fun DebugHomeScreen(
    scrollState: ScrollState,
    alwaysRecordLogsEnabled: Boolean,
    onAlwaysRecordLogsChange: (Boolean) -> Unit,
    onOpenListenTogetherDebug: () -> Unit,
    onOpenUsbExclusiveDebug: () -> Unit,
    onOpenYouTubeDebug: () -> Unit,
    onOpenBiliDebug: () -> Unit,
    onOpenNeteaseDebug: () -> Unit,
    onOpenSearchDebug: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCrashLogs: () -> Unit,
    onHideDebugMode: () -> Unit,
    onTestExceptionHandler: (DebugCrashTestType) -> Unit = {},
) {
    val miniH = LocalMiniPlayerHeight.current
    var showCrashTypeDialog by remember { mutableStateOf(false) }

    if (showCrashTypeDialog) {
        DebugCrashTypeDialog(
            onDismiss = { showCrashTypeDialog = false },
            onPick = { crashType ->
                showCrashTypeDialog = false
                onTestExceptionHandler(crashType)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = miniH + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DebugHeader()

        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.debug_section_platforms),
            description = stringResource(CoreCommonR.string.debug_section_platforms_desc)
        )
        MiuixSettingsSectionCard {
            Column {
                DebugRow(
                    glyph = rememberVectorPainter(Icons.Rounded.SmartDisplay),
                    hue = DebugRed,
                    title = stringResource(CoreCommonR.string.debug_youtube_probe_title),
                    description = stringResource(CoreCommonR.string.debug_youtube_probe_desc_short),
                    onClick = onOpenYouTubeDebug
                )
                DebugRow(
                    glyph = painterResource(CoreCommonR.drawable.ic_bilibili),
                    hue = DebugPink,
                    title = stringResource(CoreCommonR.string.debug_bili_api),
                    description = stringResource(CoreCommonR.string.debug_bili_api_desc),
                    onClick = onOpenBiliDebug
                )
                DebugRow(
                    glyph = painterResource(CoreCommonR.drawable.ic_netease_cloud_music),
                    hue = DebugRed,
                    title = stringResource(CoreCommonR.string.debug_netease_api),
                    description = stringResource(CoreCommonR.string.debug_netease_api_desc),
                    onClick = onOpenNeteaseDebug
                )
                DebugRow(
                    glyph = rememberVectorPainter(Icons.AutoMirrored.Rounded.ManageSearch),
                    hue = DebugBlue,
                    title = stringResource(CoreCommonR.string.debug_search_api),
                    description = stringResource(CoreCommonR.string.debug_search_api_desc),
                    onClick = onOpenSearchDebug,
                    divider = false
                )
            }
        }

        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.debug_section_diagnostics),
            description = stringResource(CoreCommonR.string.debug_section_diagnostics_desc)
        )
        MiuixSettingsSectionCard {
            Column {
                DebugRow(
                    glyph = rememberVectorPainter(Icons.Rounded.Headphones),
                    hue = DebugPink,
                    title = stringResource(CoreCommonR.string.listen_together_title),
                    description = stringResource(CoreCommonR.string.listen_together_debug_entry_desc),
                    onClick = onOpenListenTogetherDebug
                )
                DebugRow(
                    glyph = rememberVectorPainter(Icons.Rounded.Usb),
                    hue = DebugGreen,
                    title = stringResource(CoreCommonR.string.debug_usb_exclusive_title),
                    description = stringResource(CoreCommonR.string.debug_usb_exclusive_desc_short),
                    onClick = onOpenUsbExclusiveDebug
                )
                DebugRow(
                    glyph = rememberVectorPainter(Icons.Rounded.Description),
                    hue = DebugTeal,
                    title = stringResource(CoreCommonR.string.debug_view_logs),
                    description = stringResource(CoreCommonR.string.debug_view_logs_desc),
                    onClick = onOpenLogs
                )
                DebugRow(
                    glyph = rememberVectorPainter(Icons.AutoMirrored.Rounded.ReceiptLong),
                    hue = DebugGraphite,
                    title = stringResource(CoreCommonR.string.settings_always_record_logs),
                    description = stringResource(CoreCommonR.string.settings_always_record_logs_desc),
                    onClick = { onAlwaysRecordLogsChange(!alwaysRecordLogsEnabled) },
                    trailing = {
                        MiuixSettingsSwitch(
                            checked = alwaysRecordLogsEnabled,
                            onCheckedChange = onAlwaysRecordLogsChange
                        )
                    }
                )
                DebugRow(
                    glyph = rememberVectorPainter(Icons.Rounded.ErrorOutline),
                    hue = DebugOrange,
                    title = stringResource(CoreCommonR.string.crash_log_title),
                    description = stringResource(CoreCommonR.string.crash_log_desc),
                    onClick = onOpenCrashLogs,
                    divider = false
                )
            }
        }

        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.debug_section_stability),
            description = stringResource(CoreCommonR.string.debug_section_stability_desc)
        )
        MiuixSettingsSectionCard {
            DebugRow(
                glyph = rememberVectorPainter(Icons.Rounded.Warning),
                hue = DebugRed,
                title = stringResource(CoreCommonR.string.debug_test_exception),
                description = stringResource(CoreCommonR.string.debug_test_exception_desc),
                onClick = { showCrashTypeDialog = true },
                divider = false
            )
        }

        Spacer(Modifier.height(12.dp))
        DebugHideButton(onClick = onHideDebugMode)
        Text(
            text = stringResource(CoreCommonR.string.debug_hide_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun DebugHeader() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DebugGlyph(rememberVectorPainter(Icons.Rounded.BugReport), DebugIndigo, size = 52)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(CoreCommonR.string.debug_tools),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface
            )
            Text(
                text = stringResource(CoreCommonR.string.debug_select_platform),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DebugRow(
    glyph: Painter,
    hue: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.performHapticFeedback()
                    onClick()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DebugGlyph(glyph, hue)
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        if (divider) {
            Box(
                Modifier
                    .padding(start = 58.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.5f))
            )
        }
    }
}

/** Tile in clean style, a plain tinted symbol in Material so both themes stay native. */
@Composable
private fun DebugGlyph(painter: Painter, hue: Color, size: Int = 30) {
    if (NeriDesign.isClean) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(ContinuousRoundedCornerShape((size * 0.28f).dp))
                .background(hue),
            contentAlignment = Alignment.Center
        ) {
            Icon(painter, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size((size * 0.56f).dp)
            )
        }
    }
}

@Composable
private fun DebugHideButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(colors.error.copy(alpha = 0.12f))
            .clickable {
                context.performHapticFeedback()
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(CoreCommonR.string.debug_hide),
            style = MaterialTheme.typography.labelLarge,
            color = colors.error
        )
    }
}

@Composable
private fun DebugCrashTypeDialog(
    onDismiss: () -> Unit,
    onPick: (DebugCrashTestType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.debug_test_exception_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(CoreCommonR.string.debug_test_exception_picker_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                DebugCrashTestType.entries.forEach { crashType ->
                    val native = crashType == DebugCrashTestType.NativeSigSegv ||
                        crashType == DebugCrashTestType.NativeSigAbrt
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ContinuousRoundedCornerShape(14.dp))
                            .clickable { onPick(crashType) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DebugGlyph(
                            rememberVectorPainter(if (native) Icons.Rounded.Warning else Icons.Rounded.ErrorOutline),
                            if (native) DebugRed else DebugOrange,
                            size = 26
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(crashType.titleRes),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(crashType.descriptionRes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}
