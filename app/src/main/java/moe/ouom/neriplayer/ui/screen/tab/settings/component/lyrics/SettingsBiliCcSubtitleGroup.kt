package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BILI_SUBTITLE_LANGUAGE_AUTO
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget

/**
 * 来源偏好卡片里的 CC 字幕二级折叠菜单。
 *
 * 开关、默认轨道与 AI 后备合并成一组，避免占用三行主列表。
 */
@Composable
internal fun SettingsBiliCcSubtitleGroup(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val enabled by repository.biliCcSubtitleLyricsEnabledFlow.collectAsState(initial = true)
    val language by repository.biliCcSubtitleLanguageFlow.collectAsState(
        initial = BILI_SUBTITLE_LANGUAGE_AUTO
    )
    val aiOnlyEnabled by repository.biliCcSubtitleAiOnlyEnabledFlow.collectAsState(initial = true)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }

    val trackLabel = biliCcSubtitleLanguageLabelRes(language)
        ?.let { stringResource(it) }
        ?: language
    val switchKey = AutoSettingsSchema.lyricSource.biliCcSubtitleLyricsEnabled.key
    val switchTarget = "setting:$switchKey"
    val groupTargets = remember(switchKey) {
        setOf(
            switchTarget,
            "setting:${AutoSettingsSchema.lyricSource.biliCcSubtitleLanguage.key}",
            "setting:${AutoSettingsSchema.lyricSource.biliCcSubtitleAiOnlyEnabled.key}"
        )
    }

    LaunchedEffect(highlightTargetId, highlightPulse) {
        if (highlightTargetId in groupTargets) expanded = true
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "biliCcSubtitleGroupArrow"
    )

    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = switchTarget,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .settingsItemClickable { expanded = !expanded },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ClosedCaption,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(stringResource(AppR.string.settings_bili_cc_subtitle_group))
        },
        supportingContent = {
            Text(
                if (enabled) {
                    stringResource(
                        AppR.string.settings_bili_cc_subtitle_group_enabled,
                        trackLabel
                    )
                } else {
                    stringResource(AppR.string.settings_bili_cc_subtitle_group_disabled)
                }
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    AnimatedVisibility(visible = expanded) {
        Column {
            AutoSettingSpecListItem(
                setting = AutoSettingsSchema.lyricSource.biliCcSubtitleLyricsEnabled,
                trailingContent = {
                    MiuixSettingsSwitch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            scope.launch { repository.setBiliCcSubtitleLyricsEnabled(value) }
                        }
                    )
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onClick = {
                    scope.launch { repository.setBiliCcSubtitleLyricsEnabled(!enabled) }
                }
            )

            AutoSettingSpecListItem(
                setting = AutoSettingsSchema.lyricSource.biliCcSubtitleLanguage,
                enabled = enabled,
                supportingContent = {
                    Text(stringResource(AppR.string.bili_cc_subtitle_current, trackLabel))
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onClick = { showTrackDialog = true }
            )

            AutoSettingSpecListItem(
                setting = AutoSettingsSchema.lyricSource.biliCcSubtitleAiOnlyEnabled,
                enabled = enabled,
                trailingContent = {
                    MiuixSettingsSwitch(
                        checked = aiOnlyEnabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            scope.launch { repository.setBiliCcSubtitleAiOnlyEnabled(value) }
                        }
                    )
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onClick = {
                    scope.launch { repository.setBiliCcSubtitleAiOnlyEnabled(!aiOnlyEnabled) }
                }
            )
        }
    }

    if (showTrackDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showTrackDialog = false },
            title = {
                Text(stringResource(AppR.string.bili_cc_subtitle_language_dialog_title))
            },
            text = {
                Column {
                    BILI_CC_SUBTITLE_LANGUAGE_OPTIONS.forEach { option ->
                        val optionLabel = biliCcSubtitleLanguageLabelRes(option)
                            ?.let { stringResource(it) }
                            ?: option
                        MiuixSettingsChoiceRow(
                            title = optionLabel,
                            selected = option == language,
                            onClick = {
                                scope.launch { repository.setBiliCcSubtitleLanguage(option) }
                                showTrackDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = { showTrackDialog = false },
                    text = { Text(stringResource(CoreCommonR.string.action_close)) }
                )
            }
        )
    }
}

private val BILI_CC_SUBTITLE_LANGUAGE_OPTIONS = listOf(
    BILI_SUBTITLE_LANGUAGE_AUTO,
    "zh-Hans",
    "zh-Hant",
    "ai-zh",
    "en-US",
    "ja",
    "ko"
)

private fun biliCcSubtitleLanguageLabelRes(language: String): Int? {
    val isAiGenerated = language.startsWith("ai-", ignoreCase = true)
    val baseLanguage = if (isAiGenerated) language.substring(3) else language
    return when {
        baseLanguage.isBlank() -> AppR.string.bili_cc_subtitle_auto
        isAiGenerated && baseLanguage.startsWith("zh", ignoreCase = true) ->
            AppR.string.bili_cc_subtitle_lang_zh_ai
        baseLanguage.startsWith("zh", ignoreCase = true) &&
            (baseLanguage.contains("Hant", ignoreCase = true) ||
                baseLanguage.contains("TW", ignoreCase = true) ||
                baseLanguage.contains("HK", ignoreCase = true) ||
                baseLanguage.contains("MO", ignoreCase = true)) ->
            AppR.string.bili_cc_subtitle_lang_zh_hant
        baseLanguage.startsWith("zh", ignoreCase = true) -> AppR.string.bili_cc_subtitle_lang_zh_hans
        baseLanguage.startsWith("en", ignoreCase = true) -> AppR.string.bili_cc_subtitle_lang_en
        baseLanguage.startsWith("ja", ignoreCase = true) -> AppR.string.bili_cc_subtitle_lang_ja
        baseLanguage.startsWith("ko", ignoreCase = true) -> AppR.string.bili_cc_subtitle_lang_ko
        else -> null
    }
}
