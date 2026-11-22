package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.home.mergeVisibleHomeSectionOrder
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeSongCardColumns
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionDisplayMode
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.homeSongSectionDisplayModeOf
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
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.data.model.settings.home.applyHomeSectionOrder
import moe.ouom.neriplayer.ui.screen.tab.settings.home.SettingsHomeCardCopy
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

    /** 该分组下可见的子模块 id。 */
    fun visibleSubSectionIds(): List<String> = order

    fun editor(parentId: String, actions: HomeSectionSettingsActions) =
        HomeSectionOrderEditor(order) { actions.reorderSub(parentId, it) }
}

private class HomeMajorRow(
    val titleRes: Int,
    val icon: ImageVector,
    val checked: Boolean,
    val children: HomeSubSections? = null
)

/**
 * 哪些大模块分组需要展开，才能让高亮目标出现在屏幕上。
 *
 * 歌曲小模块的形态选择器挂在子模块行里，而子模块行只在大模块展开后才渲染。
 * 搜索跳到设置页时分组默认是折叠的，控件根本不存在，也就无从高亮，
 * 用户还得自己再展开一次。这里按目标 id 反查需要展开的分组。
 */
internal fun homeSectionGroupsToExpand(
    highlightTargetId: String?,
    songGroupIds: Set<String>
): Set<String> = when (highlightTargetId) {
    // 形态选择器在每个歌曲类子模块行上都有，展开所有含歌曲子模块的分组即可。
    "setting:home_song_section_mode" -> songGroupIds
    else -> emptySet()
}

private fun AppHomeCardsSettingsState.homeRows(copy: SettingsHomeCardCopy, international: Boolean) = mapOf(
    HomeMajorSectionIds.CONTINUE to HomeMajorRow(
        CoreCommonR.string.player_continue, Icons.Outlined.History, showHomeContinueCard
    ),
    HomeMajorSectionIds.RADAR to HomeMajorRow(
        copy.radarLabelRes, homeRadarIcon(international), showHomeRadarCard,
        HomeSubSections(HomeRadarSubSectionIds.DEFAULT_ORDER, radarOrder, radarHidden)
    ),
    HomeMajorSectionIds.TRENDING to HomeMajorRow(
        copy.trendingLabelRes, Icons.Outlined.Bolt, showHomeTrendingCard,
        HomeSubSections(HomeTrendingSubSectionIds.DEFAULT_ORDER, trendingOrder, trendingHidden)
    ),
    HomeMajorSectionIds.RECOMMENDED to HomeMajorRow(
        copy.recommendedLabelRes, Icons.Outlined.Star, showHomeRecommendedCard,
        HomeSubSections(HomeRecommendedSubSectionIds.DEFAULT_ORDER, recommendedOrder, recommendedHidden)
    ),
    HomeMajorSectionIds.BILIBILI to HomeMajorRow(
        CoreCommonR.string.settings_home_card_bilibili, Icons.Outlined.LiveTv, showHomeBilibiliCard
    )
)

private fun AppHomeCardsSettingsState.majorOrderEditor(
    international: Boolean,
    actions: HomeSectionSettingsActions
): HomeSectionOrderEditor {
    val hidden = if (international) setOf(HomeMajorSectionIds.BILIBILI) else emptySet()
    val visible = applyHomeSectionOrder(HomeMajorSectionIds.DEFAULT_ORDER, majorOrder, hidden)
    return HomeSectionOrderEditor(visible) { actions.reorderMajor(mergeVisibleHomeSectionOrder(majorOrder, it)) }
}

@Composable
internal fun SettingsPersonalizationHomeCard(
    internationalEnabled: Boolean,
    copy: SettingsHomeCardCopy,
    homeStartAvailable: Boolean,
    state: AppHomeCardsSettingsState,
    actions: HomeSectionSettingsActions,
    songSectionModes: Map<String, HomeSongSectionDisplayMode> = emptyMap(),
    onSetSongSectionMode: (String, HomeSongSectionDisplayMode) -> Unit = { _, _ -> },
    songCardColumns: Int = HomeSongCardColumns.DEFAULT,
    onSetSongCardColumns: (Int) -> Unit = {},
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val rows = state.homeRows(copy, internationalEnabled)
    val editor = state.majorOrderEditor(internationalEnabled, actions)
    // 榜单卡片是网易云首页小模块的能力，国际模式下这些小模块走 YouTube Music，
    // 相关控件整体隐藏。
    val showSongCardControls = !internationalEnabled
    // 歌曲类小模块分属「私人雷达」与「热力与榜单」两个大模块分组。
    val songGroupIds = remember(rows) {
        rows.filterValues { row ->
            row.children?.visibleSubSectionIds()?.any { it in HomeSongSectionIds.ALL } == true
        }.keys
    }
    // 搜索定位到形态选择器时，先把包含歌曲子模块的分组展开，
    // 否则控件不在组合里，高亮不会生效。
    val autoExpandedGroups = remember(highlightTargetId, songGroupIds) {
        homeSectionGroupsToExpand(highlightTargetId, songGroupIds)
    }
    PersonalizationCardContainer {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_personalization_home_section),
            description = stringResource(CoreCommonR.string.settings_personalization_home_section_desc)
        )
        HomeReorderableColumn(items = editor.order, onReorder = editor.reorder) { index, id, _ ->
            val row = rows.getValue(id)
            val title = stringResource(row.titleRes)
            var expanded by remember(id) { mutableStateOf(false) }
            // 高亮目标在折叠的分组里时自动展开，用户不必再手动点一次。
            LaunchedEffect(autoExpandedGroups, id) {
                if (id in autoExpandedGroups) expanded = true
            }
            ReorderableItem {
                SettingsHomeCardSwitch(
                    title = title,
                    icon = row.icon,
                    checked = row.checked,
                    onCheckedChange = { actions.setMajorVisible(id, it) },
                    expanded = expanded,
                    onToggleExpanded = row.children?.let { { expanded = !expanded } },
                    targetId = "setting:home_card_$id",
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                    leadingExtra = { HomeReorderHandleIcon(title, editor.order, index, editor.reorder) }
                )
            }
            if (expanded) {
                row.children?.let {
                    HomeSubSectionList(
                        parentId = id,
                        sections = it,
                        actions = actions,
                        songSectionModes = songSectionModes,
                        onSetSongSectionMode = onSetSongSectionMode,
                        showSongSectionModeSelector = showSongCardControls,
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                }
            }
        }

        // 国际模式下首页由 YouTube Music 渲染，不读展示模式与卡片列数，
        // 这两个控件留着只会让人以为改了有用。
        if (showSongCardControls) {
            HomeSongCardColumnsSetting(
                columns = songCardColumns,
                onColumnsChange = onSetSongCardColumns,
                targetId = "setting:home_song_card_columns",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
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
private fun HomeSubSectionList(
    parentId: String,
    sections: HomeSubSections,
    actions: HomeSectionSettingsActions,
    songSectionModes: Map<String, HomeSongSectionDisplayMode> = emptyMap(),
    onSetSongSectionMode: (String, HomeSongSectionDisplayMode) -> Unit = { _, _ -> },
    showSongSectionModeSelector: Boolean = true,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val editor = sections.editor(parentId, actions)
    HomeReorderableColumn(
        items = editor.order,
        onReorder = editor.reorder,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
    ) { index, id, _ ->
        val visible = id !in sections.hidden
        val title = stringResource(homeSubSectionTitles.getValue(id))
        val isSongSection = id in HomeSongSectionIds.ALL
        ReorderableItem {
            ListItem(
                leadingContent = {
                    Box(
                        // ListItem 的 leadingContent 槽位自带 16dp 左边距，
                        // 子模块手柄只有 32dp 宽，这段留白会让手柄与标题离得过远，
                        // 这里左移半个槽位宽度把间距收回来。
                        modifier = Modifier.offset(x = (-8).dp)
                    ) {
                        HomeReorderHandleIcon(
                            title = title,
                            items = editor.order,
                            index = index,
                            onReorder = editor.reorder,
                            nested = true
                        )
                    }
                },
                headlineContent = {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            // 标题必须限制在单行：给定权重后空间不足时中文会被逐字拆行，
                            // 「私人雷达」会竖着堆叠。这里单行显示，空间不够就省略号。
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (visible) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 歌曲类小模块额外支持「列表 / 卡片」两种展示形态。
                        if (isSongSection && showSongSectionModeSelector) {
                            HomeSongSectionDisplayModeSelector(
                                mode = homeSongSectionDisplayModeOf(songSectionModes, id),
                                onModeChange = { onSetSongSectionMode(id, it) },
                                targetId = "setting:home_song_section_mode",
                                highlightTargetId = highlightTargetId,
                                highlightPulse = highlightPulse,
                                onHighlightFinished = onHighlightFinished
                            )
                        }
                    }
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

/**
 * 歌曲小模块的展示形态选择器。
 */
@Composable
private fun HomeSongSectionDisplayModeSelector(
    mode: HomeSongSectionDisplayMode,
    onModeChange: (HomeSongSectionDisplayMode) -> Unit,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val labels = listOf(
        stringResource(CoreCommonR.string.settings_home_song_section_mode_default),
        stringResource(CoreCommonR.string.settings_home_song_section_mode_card)
    )
    val selectedIndex = if (mode == HomeSongSectionDisplayMode.CARD) 1 else 0
    MiuixSettingsSegmentedTabs(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            onModeChange(
                if (index == 1) {
                    HomeSongSectionDisplayMode.CARD
                } else {
                    HomeSongSectionDisplayMode.DEFAULT
                }
            )
        },
        // 固定宽度而不是自适应：分段控件内部按权重平分标签，
        // 宽度不确定时标签会被压缩成一个圆角小块。
        // 宽度压到刚好放下「列表 / 卡片」两个标签，行内空间才够标题单行显示。
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .width(88.dp),
        fillWidth = false,
        unselectedContentColor = MaterialTheme.colorScheme.primary,
        height = 28.dp,
        contentPadding = 2.dp,
        labelStyle = MaterialTheme.typography.labelMedium
    )
}

/**
 * 榜单卡片的并排数量设置。
 */
@Composable
private fun HomeSongCardColumnsSetting(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val safeColumns = HomeSongCardColumns.normalize(columns)
    val labels = (HomeSongCardColumns.MIN..HomeSongCardColumns.MAX).map { value ->
        stringResource(CoreCommonR.string.settings_home_song_card_columns_option, value)
    }
    Column(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.settings_home_song_card_columns),
            style = MaterialTheme.typography.bodyMedium
        )
        MiuixSettingsSegmentedTabs(
            labels = labels,
            selectedIndex = safeColumns - HomeSongCardColumns.MIN,
            onSelectedIndexChange = { index ->
                onColumnsChange(index + HomeSongCardColumns.MIN)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

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
