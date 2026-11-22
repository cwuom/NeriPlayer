package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.lyrics.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.lyrics.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.ui.component.overlay.ApplyOverlayWindowNavigationBarPolicy
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StartupPlaybackControlsContent(
    preferences: PlaybackControlLayoutPreferences,
    coverLyricFontScale: Float,
    onCoverLyricFontScaleChange: (Float) -> Unit,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit,
    onOpenPreview: () -> Unit = {},
    immersiveMode: Boolean = false,
    onImmersiveModeChange: (Boolean) -> Unit = {}
) {
    var pendingImmersiveMode by remember { mutableStateOf(immersiveMode) }
    LaunchedEffect(immersiveMode) { pendingImmersiveMode = immersiveMode }
    var previewPreferences by remember { mutableStateOf(preferences) }
    var pendingCoverLyricFontScale by remember { mutableFloatStateOf(coverLyricFontScale) }
    LaunchedEffect(preferences) { previewPreferences = preferences }
    LaunchedEffect(coverLyricFontScale) { pendingCoverLyricFontScale = coverLyricFontScale }
    fun applyPreferences(updated: PlaybackControlLayoutPreferences) {
        val linkedPreferences = linkStartupLyricsControlSize(previewPreferences, updated)
        previewPreferences = linkedPreferences
        onPreferencesChange(linkedPreferences)
    }

    StartupOptionColumn {
        StartupPreviewHintCard(
            description = stringResource(AppR.string.onboarding_preview_hint_controls_desc),
            onOpenPreview = onOpenPreview
        )
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_controls_size_title),
            description = stringResource(AppR.string.onboarding_controls_size_desc)
        ) {
            StartupControlSizeSelector(
                selected = previewPreferences.nowPlayingSize,
                onSelect = { size -> applyPreferences(previewPreferences.copy(nowPlayingSize = size)) }
            )
        }
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_controls_position_title),
            description = stringResource(AppR.string.onboarding_controls_position_desc)
        ) {
            StartupPlacementSelector(
                selected = previewPreferences.nowPlayingPlacement,
                onSelect = { placement ->
                    applyPreferences(previewPreferences.copy(nowPlayingPlacement = placement))
                }
            )
        }
        StartupSwitchOptionGroup(
            title = stringResource(CoreCommonR.string.settings_nowplaying_immersive_mode),
            description = stringResource(CoreCommonR.string.settings_nowplaying_immersive_mode_desc),
            checked = pendingImmersiveMode,
            onCheckedChange = { enabled ->
                pendingImmersiveMode = enabled
                onImmersiveModeChange(enabled)
            }
        )
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_controls_cover_lyrics_size_title),
            description = stringResource(AppR.string.onboarding_controls_cover_lyrics_size_desc)
        ) {
            StartupLyricScaleControl(
                value = pendingCoverLyricFontScale,
                onValueChange = { pendingCoverLyricFontScale = it },
                onValueChangeFinished = { onCoverLyricFontScaleChange(pendingCoverLyricFontScale) }
            )
        }
    }
}

@Composable
internal fun StartupLyricsContent(
    preferences: PlaybackControlLayoutPreferences,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit,
    onOpenPreview: () -> Unit = {}
) {
    var previewPreferences by remember { mutableStateOf(preferences) }
    var pendingLyricFontScale by remember { mutableFloatStateOf(lyricFontScale) }
    var pendingControlSize by remember { mutableStateOf(preferences.lyricsSize) }
    var showControlSizeWarning by remember { mutableStateOf(false) }
    LaunchedEffect(lyricFontScale) { pendingLyricFontScale = lyricFontScale }
    LaunchedEffect(preferences) { previewPreferences = preferences }
    fun applyControlSize(size: PlaybackControlSize) {
        previewPreferences = previewPreferences.copy(lyricsSize = size)
        pendingControlSize = size
        onPreferencesChange(previewPreferences)
    }

    StartupOptionColumn {
        StartupPreviewHintCard(
            description = stringResource(AppR.string.onboarding_preview_hint_lyrics_desc),
            onOpenPreview = onOpenPreview
        )
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_lyrics_page_size_title),
            description = stringResource(AppR.string.onboarding_lyrics_page_size_desc)
        ) {
            StartupLyricScaleControl(
                value = pendingLyricFontScale,
                onValueChange = { pendingLyricFontScale = it },
                onValueChangeFinished = { onLyricFontScaleChange(pendingLyricFontScale) }
            )
        }
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_lyrics_control_size_title),
            description = stringResource(AppR.string.onboarding_lyrics_control_size_desc)
        ) {
            StartupControlSizeSelector(
                selected = previewPreferences.lyricsSize,
                onSelect = { size ->
                    if (size == previewPreferences.lyricsSize) return@StartupControlSizeSelector
                    // 与播放页不一致的尺寸会让两页切换时控件跳变, 先确认
                    if (size != previewPreferences.nowPlayingSize) {
                        pendingControlSize = size
                        showControlSizeWarning = true
                    } else {
                        applyControlSize(size)
                    }
                }
            )
        }
    }
    if (showControlSizeWarning) {
        AlertDialog(
            onDismissRequest = { showControlSizeWarning = false },
            title = { Text(stringResource(AppR.string.onboarding_lyrics_control_size_warning_title)) },
            text = { Text(stringResource(AppR.string.onboarding_lyrics_control_size_warning_desc)) },
            confirmButton = {
                ApplyOverlayWindowNavigationBarPolicy()
                HapticTextButton(
                    onClick = {
                        showControlSizeWarning = false
                        applyControlSize(pendingControlSize)
                    }
                ) {
                    Text(stringResource(AppR.string.onboarding_lyrics_control_size_warning_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showControlSizeWarning = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

/** 歌词页尺寸原本跟随播放页时, 改播放页尺寸要一起带过去 */
internal fun linkStartupLyricsControlSize(
    previous: PlaybackControlLayoutPreferences,
    updated: PlaybackControlLayoutPreferences
): PlaybackControlLayoutPreferences {
    val followsNowPlaying = previous.lyricsSize == previous.nowPlayingSize
    return if (followsNowPlaying && updated.nowPlayingSize != previous.nowPlayingSize) {
        updated.copy(lyricsSize = updated.nowPlayingSize)
    } else {
        updated
    }
}

/**
 * 平板横屏的播放页封面和歌词并排, 这里只调控件大小和歌词大小
 * 控件位置、沉浸模式、封面页歌词和歌词页控件都放到竖屏调, 用提示卡告诉用户转回竖屏
 */
@Composable
internal fun StartupNowPlayingLandscapeContent(
    preferences: PlaybackControlLayoutPreferences,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit,
    onOpenPreview: () -> Unit = {}
) {
    var previewPreferences by remember { mutableStateOf(preferences) }
    var pendingLyricFontScale by remember { mutableFloatStateOf(lyricFontScale) }
    LaunchedEffect(preferences) { previewPreferences = preferences }
    LaunchedEffect(lyricFontScale) { pendingLyricFontScale = lyricFontScale }
    fun applyPreferences(updated: PlaybackControlLayoutPreferences) {
        val linkedPreferences = linkStartupLyricsControlSize(previewPreferences, updated)
        previewPreferences = linkedPreferences
        onPreferencesChange(linkedPreferences)
    }

    StartupOptionColumn {
        StartupPreviewHintCard(
            description = stringResource(AppR.string.onboarding_preview_hint_landscape_desc),
            onOpenPreview = onOpenPreview
        )
        StartupInfoCard(
            icon = Icons.Rounded.ScreenRotation,
            title = stringResource(AppR.string.onboarding_landscape_portrait_hint_title),
            description = stringResource(AppR.string.onboarding_landscape_portrait_hint_desc),
            modifier = Modifier.testTag(StartupLandscapePortraitHintTag)
        )
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_controls_size_title),
            description = stringResource(AppR.string.onboarding_controls_size_desc)
        ) {
            StartupControlSizeSelector(
                selected = previewPreferences.nowPlayingSize,
                onSelect = { size -> applyPreferences(previewPreferences.copy(nowPlayingSize = size)) }
            )
        }
        StartupOptionGroup(
            title = stringResource(AppR.string.onboarding_landscape_lyrics_size_title),
            description = stringResource(AppR.string.onboarding_landscape_lyrics_size_desc)
        ) {
            StartupLyricScaleControl(
                value = pendingLyricFontScale,
                onValueChange = { pendingLyricFontScale = it },
                onValueChangeFinished = { onLyricFontScaleChange(pendingLyricFontScale) }
            )
        }
    }
}

internal const val StartupLandscapePortraitHintTag = "startupLandscapePortraitHint"

/** 选项在横屏双栏里够宽时排成两列, 单栏布局和手机一样一列 */
@Composable
private fun StartupOptionColumn(content: @Composable () -> Unit) {
    val twoPane = LocalStartupOnboardingTwoPane.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (twoPane && maxWidth >= StartupOptionTwoColumnMinWidth) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CompositionLocalProvider(LocalStartupOptionItemModifier provides Modifier.weight(1f)) {
                    content()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

private val StartupOptionTwoColumnMinWidth = 600.dp

private val LocalStartupOptionItemModifier = staticCompositionLocalOf<Modifier> { Modifier }

/** 提示可以打开实机预览, 点按钮和点下方迷你播放器效果相同 */
@Composable
private fun StartupPreviewHintCard(description: String, onOpenPreview: () -> Unit) {
    StartupInfoCard(
        icon = Icons.Rounded.PlayCircle,
        title = stringResource(AppR.string.onboarding_preview_hint_title),
        description = description
    ) {
        HapticTextButton(onClick = onOpenPreview) {
            Text(stringResource(AppR.string.onboarding_preview_hint_open))
        }
    }
}

@Composable
private fun StartupInfoCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    OnboardingGlassSurface(
        modifier = LocalStartupOptionItemModifier.current.fillMaxWidth().then(modifier),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = if (action != null) 8.dp else 16.dp,
                top = 14.dp,
                bottom = 14.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(if (clean) CircleShape else RoundedCornerShape(12.dp))
                    .background(if (clean) colors.primary.copy(alpha = 0.1f) else colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (clean) colors.primary else colors.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            action?.invoke()
        }
    }
}

@Composable
private fun StartupOptionGroup(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = LocalStartupOptionItemModifier.current.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 整张卡片都能点, 读屏按开关朗读 */
@Composable
private fun StartupSwitchOptionGroup(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = LocalStartupOptionItemModifier.current.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** 分段选择器: 指示块随选中项滑动, 简洁风格是白色浮层, Material 是色调胶囊 */
@Composable
private fun StartupControlSizeSelector(
    selected: PlaybackControlSize,
    onSelect: (PlaybackControlSize) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val sizes = PlaybackControlSize.entries
    val trackShape = if (clean) CircleShape else RoundedCornerShape(16.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(trackShape)
            .background(if (clean) colors.surfaceContainerHighest.copy(alpha = 0.7f) else colors.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        val segment = maxWidth / sizes.size
        val indicatorOffset by animateDpAsState(
            targetValue = segment * sizes.indexOf(selected),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
            label = "startup_size_indicator"
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .then(
                    if (clean) {
                        Modifier.shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f))
                    } else {
                        Modifier
                    }
                )
                .clip(if (clean) CircleShape else RoundedCornerShape(12.dp))
                .background(if (clean) colors.surfaceContainerLowest else colors.secondaryContainer)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            sizes.forEach { size ->
                val isSelected = size == selected
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected && clean -> colors.onSurface
                        isSelected -> colors.onSecondaryContainer
                        else -> colors.onSurfaceVariant
                    },
                    animationSpec = tween(200),
                    label = "startup_size_label"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(if (clean) CircleShape else RoundedCornerShape(12.dp))
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(size) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = playbackControlSizeLabel(size),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupPlacementSelector(
    selected: NowPlayingControlPlacement,
    onSelect: (NowPlayingControlPlacement) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NowPlayingControlPlacement.entries.forEach { placement ->
            StartupPlacementTile(
                placement = placement,
                selected = placement == selected,
                onClick = { onSelect(placement) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun StartupPlacementTile(
    placement: NowPlayingControlPlacement,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val shape = if (clean) ContinuousRoundedCornerShape(16.dp) else RoundedCornerShape(16.dp)
    val container by animateColorAsState(
        targetValue = when {
            selected && clean -> colors.primary.copy(alpha = 0.1f)
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainerLow
        },
        animationSpec = tween(220),
        label = "startup_placement_container"
    )
    val ink by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f),
        animationSpec = tween(220),
        label = "startup_placement_ink"
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) colors.primary else colors.outlineVariant), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StartupPlacementSchematic(
            placement = placement,
            ink = ink,
            modifier = Modifier
                .width(44.dp)
                .height(72.dp)
        )
        Text(
            text = placementLabel(placement),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 迷你示意图: 圆角机身里画出封面、歌词、进度和控件的相对位置 */
@Composable
private fun StartupPlacementSchematic(
    placement: NowPlayingControlPlacement,
    ink: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val corner = CornerRadius(7.dp.toPx())
        drawRoundRect(color = ink.copy(alpha = 0.55f), cornerRadius = corner, style = Stroke(1.2.dp.toPx()))
        val pad = 5.dp.toPx()
        val inner = size.width - pad * 2
        val cover = inner * 0.62f
        drawRoundRect(
            color = ink.copy(alpha = 0.32f),
            topLeft = Offset((size.width - cover) / 2f, pad + 2.dp.toPx()),
            size = Size(cover, cover),
            cornerRadius = CornerRadius(3.dp.toPx())
        )
        val lineHeight = 1.8.dp.toPx()
        fun lyricLines(top: Float) {
            listOf(0.8f, 1f, 0.7f).forEachIndexed { index, width ->
                val lineWidth = inner * width
                drawRoundRect(
                    color = ink.copy(alpha = if (index == 1) 0.75f else 0.35f),
                    topLeft = Offset((size.width - lineWidth) / 2f, top + index * 4.dp.toPx()),
                    size = Size(lineWidth, lineHeight),
                    cornerRadius = CornerRadius(lineHeight)
                )
            }
        }
        fun progress(top: Float) {
            drawRoundRect(
                color = ink.copy(alpha = 0.5f),
                topLeft = Offset(pad, top),
                size = Size(inner, lineHeight),
                cornerRadius = CornerRadius(lineHeight)
            )
        }
        fun controls(top: Float) {
            val dot = 2.4.dp.toPx()
            listOf(-2, -1, 1, 2).forEach { slot ->
                drawCircle(ink.copy(alpha = 0.6f), dot, Offset(size.width / 2f + slot * 7.dp.toPx(), top))
            }
            drawCircle(ink, dot * 1.8f, Offset(size.width / 2f, top))
        }
        val afterCover = pad + cover + 6.dp.toPx()
        val bottom = size.height - pad - 3.dp.toPx()
        when (placement) {
            NowPlayingControlPlacement.LOWER -> {
                progress(afterCover)
                controls(afterCover + 7.dp.toPx())
                lyricLines(afterCover + 14.dp.toPx())
            }
            NowPlayingControlPlacement.BOTTOM -> {
                progress(afterCover)
                lyricLines(afterCover + 6.dp.toPx())
                controls(bottom)
            }
            NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS -> {
                lyricLines(afterCover)
                progress(bottom - 7.dp.toPx())
                controls(bottom)
            }
        }
    }
}

@Composable
private fun StartupLyricScaleControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(AppR.string.onboarding_lyrics_size_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(AppR.string.onboarding_lyrics_scale_glyph),
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { }
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
                steps = 10,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            Text(
                text = stringResource(AppR.string.onboarding_lyrics_scale_glyph),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

@Composable
private fun playbackControlSizeLabel(size: PlaybackControlSize): String = when (size) {
    PlaybackControlSize.SMALL -> stringResource(CoreCommonR.string.settings_playback_control_size_small)
    PlaybackControlSize.MEDIUM -> stringResource(CoreCommonR.string.settings_playback_control_size_medium)
    PlaybackControlSize.LARGE -> stringResource(CoreCommonR.string.settings_playback_control_size_large)
}

@Composable
private fun placementLabel(placement: NowPlayingControlPlacement): String = when (placement) {
    NowPlayingControlPlacement.LOWER ->
        stringResource(CoreCommonR.string.settings_nowplaying_control_placement_lower)
    NowPlayingControlPlacement.BOTTOM ->
        stringResource(CoreCommonR.string.settings_nowplaying_control_placement_bottom)
    NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS ->
        stringResource(CoreCommonR.string.settings_nowplaying_control_placement_bottom_with_progress)
}
