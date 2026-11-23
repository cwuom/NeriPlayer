package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LiveTv
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
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
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.data.model.settings.home.applyHomeSectionOrder
import moe.ouom.neriplayer.ui.screen.tab.settings.home.internationalHomeSectionTitles
import moe.ouom.neriplayer.ui.settings.route.AppHomeCardsSettingsState
import moe.ouom.neriplayer.ui.settings.route.HomeSectionSettingsActions
import sh.calvin.reorderable.ReorderableItem
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

private class HomeSectionOrderEditor(val order: List<String>, val reorder: (List<String>) -> Unit)

private class HomeSubSections(defaults: List<String>, order: List<String>, val hidden: Set<String>) {
    private val order = applyHomeSectionOrder(defaults, order, emptySet())

    fun editor(parentId: String, actions: HomeSectionSettingsActions) =
        HomeSectionOrderEditor(order) { actions.reorderSub(parentId, it) }
}

private class HomeMajorRow(
    val titleRes: Int,
    val icon: ImageVector,
    val checked: Boolean,
    val children: HomeSubSections? = null
)

private fun AppHomeCardsSettingsState.domesticHomeRows() = mapOf(
    HomeMajorSectionIds.CONTINUE to HomeMajorRow(
        CoreCommonR.string.player_continue, Icons.Outlined.History, showHomeContinueCard
    ),
    HomeMajorSectionIds.RADAR to HomeMajorRow(
        CoreCommonR.string.settings_home_card_netease_radar, Icons.Outlined.Radar, showHomeRadarCard,
        HomeSubSections(HomeRadarSubSectionIds.DEFAULT_ORDER, radarOrder, radarHidden)
    ),
    HomeMajorSectionIds.TRENDING to HomeMajorRow(
        CoreCommonR.string.settings_home_card_netease_trending, Icons.Outlined.Bolt, showHomeTrendingCard,
        HomeSubSections(HomeTrendingSubSectionIds.DEFAULT_ORDER, trendingOrder, trendingHidden)
    ),
    HomeMajorSectionIds.RECOMMENDED to HomeMajorRow(
        CoreCommonR.string.settings_home_card_netease_recommended, Icons.Outlined.Star, showHomeRecommendedCard,
        HomeSubSections(HomeRecommendedSubSectionIds.DEFAULT_ORDER, recommendedOrder, recommendedHidden)
    ),
    HomeMajorSectionIds.BILIBILI to HomeMajorRow(
        CoreCommonR.string.settings_home_card_bilibili, Icons.Outlined.LiveTv, showHomeBilibiliCard
    )
)

private fun AppHomeCardsSettingsState.internationalHomeRows() =
    internationalHomeSectionTitles.mapValues { (id, title) ->
        val icon = when (id) {
            InternationalHomeSectionIds.CONTINUE -> Icons.Outlined.History
            InternationalHomeSectionIds.SPOTLIGHT -> Icons.Outlined.Star
            else -> Icons.Outlined.Explore
        }
        HomeMajorRow(title, icon, id !in internationalLayout.hidden)
    }

@Composable
internal fun SettingsPersonalizationHomeCard(
    internationalEnabled: Boolean,
    homeStartAvailable: Boolean,
    state: AppHomeCardsSettingsState,
    actions: HomeSectionSettingsActions,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val rows = if (internationalEnabled) state.internationalHomeRows() else state.domesticHomeRows()
    val editor = if (internationalEnabled) {
        HomeSectionOrderEditor(state.internationalLayout.order, actions::reorderInternational)
    } else {
        HomeSectionOrderEditor(state.majorOrder, actions::reorderMajor)
    }
    val setVisible = if (internationalEnabled) actions::setInternationalVisible else actions::setMajorVisible
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_home_section),
            description = stringResource(
                if (internationalEnabled) CoreCommonR.string.international_home_sections_desc
                else CoreCommonR.string.settings_personalization_home_section_desc
            )
        )
        HomeReorderableColumn(items = editor.order, onReorder = editor.reorder) { index, id, _ ->
            val row = rows.getValue(id)
            val title = stringResource(row.titleRes)
            var expanded by remember(id) { mutableStateOf(false) }
            ReorderableItem {
                SettingsHomeCardSwitch(
                    title = title,
                    icon = row.icon,
                    checked = row.checked,
                    onCheckedChange = { setVisible(id, it) },
                    expanded = expanded,
                    onToggleExpanded = row.children?.let { { expanded = !expanded } },
                    targetId = if (internationalEnabled) "manual:international_home_$id" else "setting:home_card_$id",
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                    leadingExtra = { HomeReorderHandleIcon(title, editor.order, index, editor.reorder) }
                )
            }
            if (expanded) {
                row.children?.let { HomeSubSectionList(id, it, actions) }
            }
        }

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

private val homeSubSectionTitles = mapOf(
    HomeTrendingSubSectionIds.TOP_SOARING to CoreCommonR.string.recommend_trending,
    HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS to CoreCommonR.string.home_netease_new_songs,
    HomeTrendingSubSectionIds.TOP_HOT to CoreCommonR.string.home_netease_hot_rank,
    HomeTrendingSubSectionIds.TOP_NEW to CoreCommonR.string.home_netease_new_rank,
    HomeRadarSubSectionIds.PERSONAL_RADAR to CoreCommonR.string.recommend_radar,
    HomeRadarSubSectionIds.RADAR_PLAYLISTS to CoreCommonR.string.home_netease_radar_playlists,
    HomeRadarSubSectionIds.DAILY_RECOMMEND to CoreCommonR.string.home_netease_daily_songs,
    HomeRadarSubSectionIds.PRIVATE_FM to CoreCommonR.string.home_netease_private_fm,
    HomeRecommendedSubSectionIds.PERSONALIZED to CoreCommonR.string.recommend_for_you,
    HomeRecommendedSubSectionIds.DAILY_RESOURCE to CoreCommonR.string.home_netease_daily_playlists,
    HomeRecommendedSubSectionIds.HIGH_QUALITY to CoreCommonR.string.home_netease_high_quality_playlists,
    HomeRecommendedSubSectionIds.HOT_PLAYLISTS to CoreCommonR.string.home_netease_hot_playlists,
    HomeRecommendedSubSectionIds.ACG_PLAYLISTS to CoreCommonR.string.home_netease_acg_playlists
)

@Composable
private fun HomeSubSectionList(parentId: String, sections: HomeSubSections, actions: HomeSectionSettingsActions) {
    val editor = sections.editor(parentId, actions)
    HomeReorderableColumn(
        items = editor.order,
        onReorder = editor.reorder,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
    ) { index, id, _ ->
        val visible = id !in sections.hidden
        val title = stringResource(homeSubSectionTitles.getValue(id))
        ReorderableItem {
            ListItem(
                leadingContent = { HomeReorderHandleIcon(title, editor.order, index, editor.reorder) },
                headlineContent = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (visible) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    MiuixSettingsSwitch(
                        checked = visible,
                        onCheckedChange = { actions.setSubVisible(parentId, id, it) },
                        modifier = Modifier.semantics { contentDescription = title }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}

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
    val nowPlayingImmersiveMode by autoSettingsRepository.nowPlayingImmersiveModeFlow
        .collectAsState(initial = false)
    val coverLyricsState = resolveCoverLyricsSwitchState(
        lyricsEnabled = nowPlayingCoverLyricsEnabled,
        immersiveMode = nowPlayingImmersiveMode
    )
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
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOW_PLAYING_IMMERSIVE_MODE),
            checked = nowPlayingImmersiveMode,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingImmersiveMode(enabled) }
            },
            enabled = true,
            supportingContent = null,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        PersonalizationSwitchItem(
            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOW_PLAYING_COVER_LYRICS_ENABLED),
            checked = coverLyricsState.checked,
            onCheckedChange = { enabled ->
                scope.launch { autoSettingsRepository.setNowPlayingCoverLyricsEnabled(enabled) }
            },
            enabled = coverLyricsState.enabled,
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

internal data class CoverLyricsSwitchState(
    val checked: Boolean,
    val enabled: Boolean
)

internal fun resolveCoverLyricsSwitchState(
    lyricsEnabled: Boolean,
    immersiveMode: Boolean
): CoverLyricsSwitchState = CoverLyricsSwitchState(
    // 沉浸模式只锁定显示状态，退出后恢复保存的封面歌词偏好
    checked = lyricsEnabled && !immersiveMode,
    enabled = !immersiveMode
)

@Composable
internal fun SettingsPersonalizationControlsCard(
    autoSettingsRepository: AutoSettingsRepository,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
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
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    LyricFontScaleSettingsItem(
        setting = setting,
        currentScale = currentScale,
        onScaleCommit = lyricScaleCommitAction(target, onLyricFontScaleChange),
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
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        label = "personalization_switch_item_alpha"
    )
    AutoSettingsListItem(
        setting = setting,
        modifier = Modifier.graphicsLayer { alpha = contentAlpha },
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
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
    leadingExtra: @Composable () -> Unit
) {
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .then(if (onToggleExpanded == null) Modifier.settingsItemClickable { onCheckedChange(!checked) } else Modifier),
        leadingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                leadingExtra()
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        headlineContent = {
            if (onToggleExpanded == null) {
                Text(title)
            } else {
                Row(
                    modifier = Modifier.settingsItemClickable(onClick = onToggleExpanded).heightIn(min = 48.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(title, modifier = Modifier.weight(1f, fill = false))
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) CoreCommonR.string.settings_home_sub_section_collapse
                            else CoreCommonR.string.settings_home_sub_section_expand,
                            title
                        ),
                        modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f }
                    )
                }
            }
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = title }
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
