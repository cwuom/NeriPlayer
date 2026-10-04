package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.ui.settings.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.lyrics.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.lyrics.scaledLyricFontSize
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.PlaybackControlLayoutSettings
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
internal fun SettingsPersonalizationStartCard(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    defaultStartDestinationLabel: String,
    onOpenDefaultStartDestination: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val autoShowKeyboard by autoSettingsRepository.autoShowKeyboardFlow.collectAsState(initial = false)
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_start_section),
            description = stringResource(CoreCommonR.string.settings_personalization_start_section_desc)
        )
        AutoSettingsListItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DEFAULT_START_DESTINATION),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = stringResource(CoreCommonR.string.settings_default_start_screen),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = {
                Text(
                    stringResource(
                        CoreCommonR.string.settings_default_start_screen_desc,
                        defaultStartDestinationLabel
                    )
                )
            },
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished,
            onClick = onOpenDefaultStartDestination
        )

        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.AUTO_SHOW_KEYBOARD),
            checked = autoShowKeyboard,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setAutoShowKeyboard(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}

@Composable
internal fun SettingsPersonalizationHomeCard(
    internationalEnabled: Boolean,
    homeTrendingLabelRes: Int,
    homeRadarLabelRes: Int,
    homeRecommendedLabelRes: Int,
    homeTrendingSupportingRes: Int?,
    homeRadarSupportingRes: Int?,
    homeRecommendedSupportingRes: Int?,
    homeStartAvailable: Boolean,
    showHomeContinueCard: Boolean,
    onShowHomeContinueCardChange: (Boolean) -> Unit,
    showHomeTrendingCard: Boolean,
    onShowHomeTrendingCardChange: (Boolean) -> Unit,
    showHomeRadarCard: Boolean,
    onShowHomeRadarCardChange: (Boolean) -> Unit,
    showHomeRecommendedCard: Boolean,
    onShowHomeRecommendedCardChange: (Boolean) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_home_section),
            description = stringResource(CoreCommonR.string.settings_personalization_home_section_desc)
        )
        SettingsHomeCardSwitch(
            title = stringResource(CoreCommonR.string.player_continue),
            icon = Icons.Outlined.History,
            checked = showHomeContinueCard,
            onCheckedChange = onShowHomeContinueCardChange,
            description = null,
            enabled = true,
            targetId = "setting:home_card_continue",
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        SettingsHomeCardSwitch(
            title = stringResource(homeTrendingLabelRes),
            description = optionalStringResource(homeTrendingSupportingRes),
            icon = Icons.Outlined.Bolt,
            checked = showHomeTrendingCard,
            onCheckedChange = onShowHomeTrendingCardChange,
            enabled = true,
            targetId = "setting:home_card_trending",
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        SettingsHomeCardSwitch(
            title = stringResource(homeRadarLabelRes),
            description = optionalStringResource(homeRadarSupportingRes),
            icon = homeRadarIcon(internationalEnabled),
            checked = showHomeRadarCard,
            onCheckedChange = onShowHomeRadarCardChange,
            enabled = true,
            targetId = "setting:home_card_radar",
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        SettingsHomeCardSwitch(
            title = stringResource(homeRecommendedLabelRes),
            description = optionalStringResource(homeRecommendedSupportingRes),
            icon = Icons.Outlined.Star,
            checked = showHomeRecommendedCard,
            onCheckedChange = onShowHomeRecommendedCardChange,
            enabled = true,
            targetId = "setting:home_card_recommended",
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        LazyAnimatedVisibility(visible = !homeStartAvailable) {
            Text(
                text = stringResource(CoreCommonR.string.settings_home_hidden_notice),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun optionalStringResource(resId: Int?): String? = resId?.let { stringResource(it) }

private fun homeRadarIcon(internationalEnabled: Boolean): ImageVector =
    if (internationalEnabled) Icons.Outlined.Explore else Icons.Outlined.Radar

@Composable
internal fun SettingsPersonalizationPlaybackInfoCard(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val showCoverSourceBadge by autoSettingsRepository.showCoverSourceBadgeFlow.collectAsState(initial = true)
    val nowPlayingSongTitleMarqueeEnabled by autoSettingsRepository
        .nowPlayingSongTitleMarqueeEnabledFlow.collectAsState(initial = true)
    val nowPlayingCoverLyricsEnabled by autoSettingsRepository.nowPlayingCoverLyricsEnabledFlow
        .collectAsState(initial = true)
    val nowPlayingProgressShowQualitySwitch by autoSettingsRepository
        .nowPlayingProgressShowQualitySwitchFlow.collectAsState(initial = true)
    val nowPlayingProgressShowAudioCodec by autoSettingsRepository
        .nowPlayingProgressShowAudioCodecFlow.collectAsState(initial = true)
    val nowPlayingProgressShowAudioSpec by autoSettingsRepository
        .nowPlayingProgressShowAudioSpecFlow.collectAsState(initial = true)
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_playback_info_section),
            description = stringResource(CoreCommonR.string.settings_personalization_playback_info_section_desc)
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.SHOW_COVER_SOURCE_BADGE),
            checked = showCoverSourceBadge,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setShowCoverSourceBadge(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(
                AutoSettingsKeys.NOW_PLAYING_SONG_TITLE_MARQUEE_ENABLED
            ),
            checked = nowPlayingSongTitleMarqueeEnabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    autoSettingsRepository.setNowPlayingSongTitleMarqueeEnabled(enabled)
                }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOW_PLAYING_COVER_LYRICS_ENABLED),
            checked = nowPlayingCoverLyricsEnabled,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingCoverLyricsEnabled(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(
                AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_QUALITY_SWITCH
            ),
            checked = nowPlayingProgressShowQualitySwitch,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingProgressShowQualitySwitch(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_CODEC),
            checked = nowPlayingProgressShowAudioCodec,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingProgressShowAudioCodec(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_SPEC),
            checked = nowPlayingProgressShowAudioSpec,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingProgressShowAudioSpec(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}

@Composable
internal fun SettingsPersonalizationControlsCard(
    autoSettingsRepository: AutoSettingsRepository,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val alwaysUseNewTabStyle by autoSettingsRepository.alwaysUseNewTabStyleFlow
        .collectAsState(initial = true)
    val nowPlayingKeepScreenOn by autoSettingsRepository.nowPlayingKeepScreenOnFlow
        .collectAsState(initial = true)
    val nowPlayingToolbarDockEnabled by autoSettingsRepository.nowPlayingToolbarDockEnabledFlow
        .collectAsState(initial = true)
    val playbackControlLayoutPreferences by settingsRepository
        .playbackControlLayoutPreferencesFlow.collectAsState(
            initial = settingsRepository.defaultPlaybackControlLayoutPreferences
        )
    val nowPlayingControlsAtBottom =
        playbackControlLayoutPreferences.nowPlayingPlacement.placesControlsAtBottom
    val toolbarDockState = resolveToolbarDockSwitchState(
        toolbarDockEnabled = nowPlayingToolbarDockEnabled,
        controlsAtBottom = nowPlayingControlsAtBottom
    )
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_playback_controls_section),
            description = stringResource(CoreCommonR.string.settings_personalization_playback_controls_section_desc)
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ALWAYS_USE_NEW_TAB_STYLE),
            checked = alwaysUseNewTabStyle,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setAlwaysUseNewTabStyle(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_KEEP_SCREEN_ON),
            checked = nowPlayingKeepScreenOn,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingKeepScreenOn(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        ToolbarDockSwitch(
            autoSettingsRepository = autoSettingsRepository,
            scope = scope,
            state = toolbarDockState,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PlaybackControlLayoutSettings(
            preferences = playbackControlLayoutPreferences,
            onPreferencesChange = { preferences ->
                scope.launch {
                    settingsRepository.setPlaybackControlLayoutPreferences(preferences)
                }
            },
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}

@Composable
private fun ToolbarDockSwitch(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    state: ToolbarDockSwitchState,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    PersonalizationSwitchItem(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_TOOLBAR_DOCK_ENABLED),
        checked = state.checked,
        onCheckedChange = toolbarDockChangeAction(autoSettingsRepository, scope),
        enabled = state.enabled,
        supportingContent = toolbarDockSupportingContent(state.showExplanation),
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

internal data class ToolbarDockSwitchState(
    val checked: Boolean,
    val enabled: Boolean,
    val showExplanation: Boolean
)

internal fun resolveToolbarDockSwitchState(
    toolbarDockEnabled: Boolean,
    controlsAtBottom: Boolean
): ToolbarDockSwitchState = ToolbarDockSwitchState(
    checked = toolbarDockEnabled && !controlsAtBottom,
    enabled = !controlsAtBottom,
    showExplanation = controlsAtBottom
)

private fun toolbarDockSupportingContent(showExplanation: Boolean): (@Composable () -> Unit)? =
    if (showExplanation) {
        {
            Text(stringResource(CoreCommonR.string.settings_nowplaying_toolbar_dock_disabled_by_control_position))
        }
    } else {
        null
    }

@Composable
internal fun SettingsPersonalizationBackgroundCard(
    backgroundImageUri: String?,
    onPickBackgroundImage: () -> Unit,
    onClearBackgroundImage: () -> Unit,
    pendingBackgroundImageBlur: Float,
    onPendingBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurCommit: () -> Unit,
    pendingBackgroundImageAlpha: Float,
    onPendingBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaCommit: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_background_section),
            description = stringResource(CoreCommonR.string.settings_personalization_background_section_desc)
        )
        AutoSettingsListItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_URI),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Wallpaper,
                    contentDescription = stringResource(CoreCommonR.string.settings_custom_background),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = {
                Text(
                    if (backgroundImageUri != null) {
                        stringResource(CoreCommonR.string.settings_background_change)
                    } else {
                        stringResource(CoreCommonR.string.settings_background_select)
                    }
                )
            },
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished,
            onClick = onPickBackgroundImage
        )

        LazyAnimatedVisibility(visible = backgroundImageUri != null) {
            Column {
                MiuixSettingsTextButton(onClick = onClearBackgroundImage) {
                    Text(stringResource(CoreCommonR.string.background_clear))
                }

                AutoSettingsListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_BLUR),
                    showDefaultIcon = false,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                    supportingContent = {
                        MiuixSettingsSlider(
                            value = pendingBackgroundImageBlur,
                            onValueChange = onPendingBackgroundImageBlurChange,
                            onValueChangeFinished = onBackgroundImageBlurCommit,
                            valueRange = 0f..25f
                        )
                    }
                )

                AutoSettingsListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_ALPHA),
                    showDefaultIcon = false,
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                    supportingContent = {
                        MiuixSettingsSlider(
                            value = pendingBackgroundImageAlpha,
                            onValueChange = onPendingBackgroundImageAlphaChange,
                            onValueChangeFinished = onBackgroundImageAlphaCommit,
                            valueRange = 0.1f..1.0f
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PersonalizationCardContainer(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PersonalizationDetailCard(content)
    }
}

@Composable
internal fun SettingsLyricsAppearanceContent(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    LyricsTranslationControls(
        autoSettingsRepository = autoSettingsRepository,
        scope = scope,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    CoverLyricsScaleControls(
        lyricFontScales = lyricFontScales,
        onLyricFontScaleChange = onLyricFontScaleChange,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    LyricsPageScaleControls(
        lyricFontScales = lyricFontScales,
        onLyricFontScaleChange = onLyricFontScaleChange,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun LyricsTranslationControls(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    MiuixSettingsSectionIntro(
        title = stringResource(CoreCommonR.string.settings_lyrics_appearance_section),
        description = stringResource(CoreCommonR.string.settings_lyrics_appearance_section_desc)
    )
    ShowLyricTranslationSwitch(
        autoSettingsRepository = autoSettingsRepository,
        scope = scope,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    LyricPhoneticSwitch(
        autoSettingsRepository = autoSettingsRepository,
        scope = scope,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun ShowLyricTranslationSwitch(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val showLyricTranslation by autoSettingsRepository.showLyricTranslationFlow.collectAsState(initial = true)
    PersonalizationSwitchItem(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.SHOW_LYRIC_TRANSLATION),
        checked = showLyricTranslation,
        onCheckedChange = lyricTranslationChangeAction(autoSettingsRepository, scope),
        enabled = true,
        supportingContent = null,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun LyricPhoneticSwitch(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val lyricTranslationUsePhonetic by autoSettingsRepository.lyricTranslationUsePhoneticFlow.collectAsState(
        initial = false
    )
    PersonalizationSwitchItem(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRIC_TRANSLATION_USE_PHONETIC),
        checked = lyricTranslationUsePhonetic,
        onCheckedChange = lyricPhoneticChangeAction(autoSettingsRepository, scope),
        enabled = true,
        supportingContent = null,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun CoverLyricsScaleControls(
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    MiuixSettingsSectionIntro(
        title = stringResource(CoreCommonR.string.settings_lyrics_cover_page_section),
        description = stringResource(CoreCommonR.string.settings_lyrics_cover_page_section_desc)
    )
    LyricScaleRow(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_COVER_LYRIC_FONT_SCALE),
        currentScale = lyricFontScales.coverLyric,
        target = LyricFontScaleTarget.COVER_LYRIC,
        onLyricFontScaleChange = onLyricFontScaleChange,
        sampleText = stringResource(CoreCommonR.string.settings_lyrics_sample),
        sampleBaseSizeSp = 18f,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    LyricScaleRow(
        setting = AutoSettingsMetadata.requireSetting(
            AutoSettingsKeys.NOWPLAYING_COVER_TRANSLATION_FONT_SCALE
        ),
        currentScale = lyricFontScales.coverTranslation,
        target = LyricFontScaleTarget.COVER_TRANSLATION,
        onLyricFontScaleChange = onLyricFontScaleChange,
        sampleText = stringResource(CoreCommonR.string.settings_lyrics_translation_sample),
        sampleBaseSizeSp = 14f,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun LyricsPageScaleControls(
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    MiuixSettingsSectionIntro(
        title = stringResource(CoreCommonR.string.settings_lyrics_page_section),
        description = stringResource(CoreCommonR.string.settings_lyrics_page_section_desc)
    )
    LyricScaleRow(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRICS_PAGE_LYRIC_FONT_SCALE),
        currentScale = lyricFontScales.lyricsPageLyric,
        target = LyricFontScaleTarget.LYRICS_PAGE_LYRIC,
        onLyricFontScaleChange = onLyricFontScaleChange,
        sampleText = stringResource(CoreCommonR.string.settings_lyrics_sample),
        sampleBaseSizeSp = 20f,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    LyricScaleRow(
        setting = AutoSettingsMetadata.requireSetting(
            AutoSettingsKeys.LYRICS_PAGE_TRANSLATION_FONT_SCALE
        ),
        currentScale = lyricFontScales.lyricsPageTranslation,
        target = LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION,
        onLyricFontScaleChange = onLyricFontScaleChange,
        sampleText = stringResource(CoreCommonR.string.settings_lyrics_translation_sample),
        sampleBaseSizeSp = 16f,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun LyricScaleRow(
    setting: AutoSettingInfo,
    currentScale: Float,
    target: LyricFontScaleTarget,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    sampleText: String,
    sampleBaseSizeSp: Float,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    LyricFontScaleSettingsItem(
        setting = setting,
        currentScale = currentScale,
        onScaleCommit = lyricScaleCommitAction(target, onLyricFontScaleChange),
        sampleText = sampleText,
        sampleBaseSizeSp = sampleBaseSizeSp,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

private fun toolbarDockChangeAction(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope
): (Boolean) -> Unit = { enabled ->
    scope.launch { autoSettingsRepository.setNowPlayingToolbarDockEnabled(enabled) }
}

private fun lyricTranslationChangeAction(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope
): (Boolean) -> Unit = { enabled ->
    scope.launch { autoSettingsRepository.setShowLyricTranslation(enabled) }
}

private fun lyricPhoneticChangeAction(
    autoSettingsRepository: AutoSettingsRepository,
    scope: CoroutineScope
): (Boolean) -> Unit = { enabled ->
    scope.launch { autoSettingsRepository.setLyricTranslationUsePhonetic(enabled) }
}

private fun lyricScaleCommitAction(
    target: LyricFontScaleTarget,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit
): (Float) -> Unit = { scale -> onLyricFontScaleChange(target, scale) }

@Composable
private fun PersonalizationDetailCard(
    content: @Composable () -> Unit
) {
    MiuixSettingsSectionCard(
        content = content
    )
}

@Composable
private fun PersonalizationSwitchItem(
    setting: AutoSettingInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    supportingContent: (@Composable () -> Unit)?,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    AutoSettingsListItem(
        setting = setting,
        enabled = enabled,
        supportingContent = supportingContent,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = switchRowClick(enabled, checked, onCheckedChange)
    )
}

internal fun switchRowClick(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): (() -> Unit)? = if (enabled) {
    { onCheckedChange(!checked) }
} else {
    null
}

@Composable
private fun LyricFontScaleSettingsItem(
    setting: AutoSettingInfo,
    currentScale: Float,
    onScaleCommit: (Float) -> Unit,
    sampleText: String,
    sampleBaseSizeSp: Float,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var pendingScale by remember(setting.keyName) {
        mutableFloatStateOf(normalizeLyricFontScale(currentScale))
    }

    LaunchedEffect(currentScale) {
        val normalizedScale = normalizeLyricFontScale(currentScale)
        if ((pendingScale - normalizedScale).absoluteValue > 0.001f) {
            pendingScale = normalizedScale
        }
    }

    AutoSettingsListItem(
        setting = setting,
        showDefaultIcon = true,
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        CoreCommonR.string.settings_lyrics_font_scale_value,
                        (pendingScale * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = pendingScale,
                    onValueChange = { pendingScale = it },
                    onValueChangeFinished = {
                        onScaleCommit(normalizeLyricFontScale(pendingScale))
                    },
                    valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
                    steps = 10
                )
                Text(
                    text = sampleText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = scaledLyricFontSize(sampleBaseSizeSp, pendingScale).sp
                )
            }
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun SettingsHomeCardSwitch(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String?,
    enabled: Boolean,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .homeCardInteractionModifier(enabled, checked, onCheckedChange),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = homeCardSupportingContent(description),
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun Modifier.homeCardInteractionModifier(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): Modifier = if (enabled) {
    settingsItemClickable { onCheckedChange(!checked) }
} else {
    alpha(0.5f)
}

private fun homeCardSupportingContent(description: String?): (@Composable () -> Unit)? =
    description?.let { text ->
        {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
