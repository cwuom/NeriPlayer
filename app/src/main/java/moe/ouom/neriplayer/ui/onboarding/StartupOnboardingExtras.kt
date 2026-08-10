package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.playback.WaveformSlider
import moe.ouom.neriplayer.ui.component.playback.scaleButtonSize
import moe.ouom.neriplayer.ui.component.playback.scaleIconSize
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.resolveNowPlayingMainControlsLayout
import moe.ouom.neriplayer.ui.screen.resolvePlaybackActionToolbarLayout
import kotlin.math.roundToInt

internal val OnboardingCardShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
internal val OnboardingControlShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

@Composable
internal fun OnboardingGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = OnboardingCardShape,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable BoxScope.() -> Unit
) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier,
        shape = shape,
        fallbackColor = color,
        tintColor = color,
        suppressInactiveNavigationSurface = true,
        content = content
    )
}

internal fun shouldConfirmStartupPlaybackSourceFallback(
    requestedEnabled: Boolean,
    currentlyEnabled: Boolean
): Boolean = requestedEnabled && !currentlyEnabled

internal data class OnboardingCoverPreviewWindow(
    val startIndex: Int,
    val endExclusive: Int,
    val activeIndex: Int
)

internal fun resolveOnboardingCoverPreviewWindow(
    totalLineCount: Int,
    lineCount: Int,
    currentLineIndex: Int = lineCount.coerceAtLeast(1) / 2
): OnboardingCoverPreviewWindow {
    if (totalLineCount <= 0) {
        return OnboardingCoverPreviewWindow(0, 0, 0)
    }
    val visibleLineCount = lineCount.coerceIn(1, totalLineCount)
    val currentIndex = currentLineIndex.coerceIn(0, totalLineCount - 1)
    val maxStartIndex = (totalLineCount - visibleLineCount).coerceAtLeast(0)
    val startIndex = (currentIndex - visibleLineCount / 2)
        .coerceIn(0, maxStartIndex)
    return OnboardingCoverPreviewWindow(
        startIndex = startIndex,
        endExclusive = startIndex + visibleLineCount,
        activeIndex = currentIndex - startIndex
    )
}

internal fun resolveOnboardingLyricActiveCenterOffset(
    viewportHeightPx: Int,
    lineHeightsPx: List<Int>,
    lineSpacingPx: Int,
    activeIndex: Int
): Int {
    if (viewportHeightPx <= 0 || lineHeightsPx.isEmpty()) return 0
    val safeActiveIndex = activeIndex.coerceIn(0, lineHeightsPx.lastIndex)
    val spacing = lineSpacingPx.coerceAtLeast(0)
    val totalHeight = lineHeightsPx.sum() + spacing * (lineHeightsPx.size - 1)
    val firstTop = ((viewportHeightPx - totalHeight) / 2).coerceAtLeast(0)
    val activeTop = firstTop + lineHeightsPx.take(safeActiveIndex).sum() + spacing * safeActiveIndex
    val activeCenter = activeTop + lineHeightsPx[safeActiveIndex] / 2
    return viewportHeightPx / 2 - activeCenter
}

@Composable
internal fun StartupPermissionContent(
    notificationPermissionSupported: Boolean,
    notificationPermissionGranted: Boolean,
    localMediaPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocalMediaPermission: () -> Unit
) {
    OnboardingSectionHeader(
        icon = Icons.Outlined.Info,
        title = stringResource(R.string.onboarding_permissions_title),
        description = stringResource(R.string.onboarding_permissions_desc)
    )
    Spacer(Modifier.height(18.dp))
    PermissionGuidanceItem(
        icon = Icons.Outlined.Info,
        title = stringResource(R.string.onboarding_permission_notification_title),
        description = stringResource(
            if (notificationPermissionSupported) {
                R.string.onboarding_permission_notification_desc
            } else {
                R.string.onboarding_permission_notification_legacy_desc
            }
        ),
        granted = notificationPermissionGranted || !notificationPermissionSupported,
        onRequest = onRequestNotificationPermission
    )
    Spacer(Modifier.height(12.dp))
    PermissionGuidanceItem(
        icon = Icons.Outlined.LibraryMusic,
        title = stringResource(R.string.onboarding_permission_media_title),
        description = stringResource(R.string.onboarding_permission_media_desc),
        granted = localMediaPermissionGranted,
        onRequest = onRequestLocalMediaPermission
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.onboarding_permissions_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun StartupPlaybackSourceContent(
    autoSourceSwitchEnabled: Boolean,
    localSourceFallbackEnabled: Boolean,
    onSetFallbackEnabled: (Boolean) -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }
    val fallbackEnabled = autoSourceSwitchEnabled && localSourceFallbackEnabled
    val partiallyEnabled = autoSourceSwitchEnabled.xor(localSourceFallbackEnabled)

    OnboardingSectionHeader(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.onboarding_playback_sources_title),
        description = stringResource(R.string.onboarding_playback_sources_desc)
    )
    Spacer(Modifier.height(18.dp))
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onboarding_playback_sources_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_playback_sources_switch_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (partiallyEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_playback_sources_partial_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Switch(
                checked = fallbackEnabled,
                onCheckedChange = { enabled ->
                    if (shouldConfirmStartupPlaybackSourceFallback(enabled, fallbackEnabled)) {
                        showConfirmation = true
                    } else {
                        onSetFallbackEnabled(enabled)
                    }
                }
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_playback_sources_detail_title),
        description = stringResource(R.string.onboarding_playback_sources_detail_desc)
    ) {
        Text(
            text = stringResource(R.string.onboarding_playback_sources_detail_bullets),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = {
                Text(stringResource(R.string.onboarding_playback_sources_confirm_title))
            },
            text = {
                Text(stringResource(R.string.onboarding_playback_sources_confirm_desc))
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showConfirmation = false
                        onSetFallbackEnabled(true)
                    }
                ) {
                    Text(stringResource(R.string.onboarding_playback_sources_confirm_enable))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun StartupLearningGuideContent() {
    OnboardingSectionHeader(
        icon = Icons.Outlined.Info,
        title = stringResource(R.string.onboarding_learning_title),
        description = stringResource(R.string.onboarding_learning_desc)
    )
    Spacer(Modifier.height(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningGuideItem(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.onboarding_learning_now_playing_title),
            body = stringResource(R.string.onboarding_learning_now_playing_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.LibraryMusic,
            title = stringResource(R.string.onboarding_learning_lyrics_title),
            body = stringResource(R.string.onboarding_learning_lyrics_desc)
        )
        LearningGuideItem(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            title = stringResource(R.string.onboarding_learning_playlist_selection_title),
            body = stringResource(R.string.onboarding_learning_playlist_selection_desc)
        )
        LearningGuideItem(
            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            title = stringResource(R.string.onboarding_learning_download_title),
            body = stringResource(R.string.onboarding_learning_download_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.CloudSync,
            title = stringResource(R.string.onboarding_learning_backup_title),
            body = stringResource(R.string.onboarding_learning_backup_desc)
        )
        LearningGuideItem(
            icon = Icons.Filled.SpeakerGroup,
            title = stringResource(R.string.onboarding_learning_listen_together_title),
            body = stringResource(R.string.onboarding_learning_listen_together_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.onboarding_learning_queue_title),
            body = stringResource(R.string.onboarding_learning_queue_desc)
        )
    }
}

@Composable
private fun LearningGuideItem(
    icon: ImageVector,
    title: String,
    body: String
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = OnboardingControlShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun StartupPlaybackControlsContent(
    preferences: PlaybackControlLayoutPreferences,
    coverLyricFontScale: Float,
    onCoverLyricFontScaleChange: (Float) -> Unit,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit
) {
    var previewPreferences by remember { mutableStateOf(preferences) }
    var pendingCoverLyricFontScale by remember {
        mutableFloatStateOf(coverLyricFontScale)
    }
    LaunchedEffect(preferences) {
        previewPreferences = preferences
    }
    LaunchedEffect(coverLyricFontScale) {
        pendingCoverLyricFontScale = coverLyricFontScale
    }
    fun applyPreferences(updated: PlaybackControlLayoutPreferences) {
        val shouldFollowLyricsSize =
            previewPreferences.lyricsSize == previewPreferences.nowPlayingSize
        val linkedPreferences = if (
            shouldFollowLyricsSize && updated.nowPlayingSize != previewPreferences.nowPlayingSize
        ) {
            updated.copy(lyricsSize = updated.nowPlayingSize)
        } else {
            updated
        }
        previewPreferences = linkedPreferences
        onPreferencesChange(linkedPreferences)
    }

    OnboardingSectionHeader(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.onboarding_controls_title),
        description = stringResource(R.string.onboarding_controls_desc)
    )
    Spacer(Modifier.height(18.dp))
    PlaybackLayoutPreview(
        preferences = previewPreferences,
        coverLyricFontScale = pendingCoverLyricFontScale
    )
    Spacer(Modifier.height(18.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_controls_size_title),
        description = stringResource(R.string.onboarding_controls_size_desc)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PlaybackControlSize.entries.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = size == previewPreferences.nowPlayingSize,
                    onClick = {
                        applyPreferences(previewPreferences.copy(nowPlayingSize = size))
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PlaybackControlSize.entries.size
                    ),
                    label = {
                        Text(
                            text = playbackControlSizeLabel(size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_controls_position_title),
        description = stringResource(R.string.onboarding_controls_position_desc)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NowPlayingControlPlacement.entries.forEach { placement ->
                PlaybackPlacementChoice(
                    label = placementLabel(placement),
                    selected = placement == previewPreferences.nowPlayingPlacement,
                    onClick = {
                        applyPreferences(previewPreferences.copy(nowPlayingPlacement = placement))
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_controls_cover_lyrics_size_title),
        description = stringResource(R.string.onboarding_controls_cover_lyrics_size_desc)
    ) {
        Text(
            text = stringResource(
                R.string.onboarding_lyrics_size_value,
                (pendingCoverLyricFontScale * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = pendingCoverLyricFontScale,
            onValueChange = { pendingCoverLyricFontScale = it },
            onValueChangeFinished = {
                onCoverLyricFontScaleChange(pendingCoverLyricFontScale)
            },
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )
    }
}

@Composable
internal fun StartupLyricsContent(
    preferences: PlaybackControlLayoutPreferences,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit
) {
    var previewPreferences by remember { mutableStateOf(preferences) }
    var pendingLyricFontScale by remember { mutableFloatStateOf(lyricFontScale) }
    var pendingControlSize by remember { mutableStateOf(preferences.lyricsSize) }
    var showControlSizeWarning by remember { mutableStateOf(false) }
    LaunchedEffect(lyricFontScale) {
        pendingLyricFontScale = lyricFontScale
    }
    LaunchedEffect(preferences) {
        previewPreferences = preferences
        pendingControlSize = preferences.lyricsSize
    }

    fun applyControlSize(size: PlaybackControlSize) {
        previewPreferences = previewPreferences.copy(lyricsSize = size)
        pendingControlSize = size
        onPreferencesChange(previewPreferences)
    }

    OnboardingSectionHeader(
        icon = Icons.Outlined.LibraryMusic,
        title = stringResource(R.string.onboarding_lyrics_title),
        description = stringResource(R.string.onboarding_lyrics_desc)
    )
    Spacer(Modifier.height(18.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_lyrics_page_preview_title),
        description = stringResource(R.string.onboarding_lyrics_page_preview_desc)
    ) {
        LyricsPagePreview(
            preferences = previewPreferences,
            lyricFontScale = pendingLyricFontScale
        )
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_lyrics_control_size_title),
        description = stringResource(R.string.onboarding_lyrics_control_size_desc)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PlaybackControlSize.entries.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = size == previewPreferences.lyricsSize,
                    onClick = {
                        if (size != previewPreferences.lyricsSize) {
                            if (size != previewPreferences.nowPlayingSize) {
                                pendingControlSize = size
                                showControlSizeWarning = true
                            } else {
                                applyControlSize(size)
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PlaybackControlSize.entries.size
                    ),
                    label = {
                        Text(
                            text = playbackControlSizeLabel(size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(R.string.onboarding_lyrics_page_size_title),
        description = stringResource(R.string.onboarding_lyrics_page_size_desc)
    ) {
        Text(
            text = stringResource(
                R.string.onboarding_lyrics_size_value,
                (pendingLyricFontScale * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = pendingLyricFontScale,
            onValueChange = { pendingLyricFontScale = it },
            onValueChangeFinished = {
                onLyricFontScaleChange(pendingLyricFontScale)
            },
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )
    }
    if (showControlSizeWarning) {
        AlertDialog(
            onDismissRequest = { showControlSizeWarning = false },
            title = {
                Text(stringResource(R.string.onboarding_lyrics_control_size_warning_title))
            },
            text = {
                Text(stringResource(R.string.onboarding_lyrics_control_size_warning_desc))
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showControlSizeWarning = false
                        applyControlSize(pendingControlSize)
                    }
                ) {
                    Text(stringResource(R.string.onboarding_lyrics_control_size_warning_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showControlSizeWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LyricsPagePreview(
    preferences: PlaybackControlLayoutPreferences,
    lyricFontScale: Float
) {
    val colors = MaterialTheme.colorScheme
    val controlSize = preferences.lyricsSize
    val secondaryControlSize = controlSize.scaleButtonSize(42.dp)
    val primaryControlSize = controlSize.scaleButtonSize(42.dp)
    val preferredControlSpacing = 20.dp * controlSize.scale
    val controlIconSize = controlSize.scaleIconSize(24.dp)
    val previewLyricTexts = listOf(
        stringResource(R.string.onboarding_controls_preview_lyric_1),
        stringResource(R.string.onboarding_controls_preview_lyric_2),
        stringResource(R.string.onboarding_controls_preview_lyric_3),
        stringResource(R.string.onboarding_controls_preview_lyric_4),
        stringResource(R.string.onboarding_controls_preview_lyric_5),
        stringResource(R.string.onboarding_controls_preview_lyric_6),
        stringResource(R.string.onboarding_controls_preview_lyric_7),
        stringResource(R.string.onboarding_controls_preview_lyric_8),
        stringResource(R.string.onboarding_controls_preview_lyric_9),
        stringResource(R.string.onboarding_controls_preview_lyric_10),
        stringResource(R.string.onboarding_controls_preview_lyric_11),
        stringResource(R.string.onboarding_controls_preview_lyric_12)
    )
    val previewLyrics = remember(previewLyricTexts) {
        previewLyricTexts.mapIndexed { index, text ->
            LyricEntry(
                text = text,
                startTimeMs = index * 4_000L,
                endTimeMs = (index + 1) * 4_000L
            )
        }
    }

    OnboardingGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp),
        shape = OnboardingCardShape,
        color = colors.surface
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val controlsLayout = resolveNowPlayingMainControlsLayout(
                availableWidth = maxWidth,
                secondaryButtonSize = secondaryControlSize,
                primaryButtonSize = primaryControlSize,
                preferredSpacing = preferredControlSpacing
            )
            val toolbarHeight = resolveOnboardingPlaybackToolbarHeight(
                controlSize = controlSize,
                docked = false
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = toolbarHeight + 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LyricsPagePreviewTopBar(preferences = preferences)
                    Spacer(Modifier.height(8.dp))
                    AdvancedLyricsView(
                        lyrics = previewLyrics,
                        currentTimeMs = 14_000L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = 148.dp),
                        textColor = colors.onSurface,
                        lyricFontScale = lyricFontScale,
                        translationFontScale = lyricFontScale,
                        baseFontSizeSp = 20f,
                        offset = 48.dp,
                        keepAliveZone = 108.dp,
                        playedLyricViewportFraction = 0.30f,
                        topFadeLength = 48.dp,
                        bottomFadeLength = 64.dp,
                        bottomContentInset = 12.dp,
                        showLyricTranslation = false,
                        lyricBlurEnabled = true,
                        isPlaying = false,
                        userScrollEnabled = false,
                        useAdditiveBlend = false
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaybackPreviewProgress()
                    Spacer(Modifier.height(8.dp))
                    PlaybackPreviewControls(
                        controlsLayout = controlsLayout,
                        baseSecondaryControlSize = secondaryControlSize,
                        basePrimaryControlSize = primaryControlSize,
                        baseIconSize = controlIconSize
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    PlaybackPreviewToolbar(
                        preferences = preferences,
                        controlSize = controlSize,
                        docked = false
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsPagePreviewTopBar(preferences: PlaybackControlLayoutPreferences) {
    val colors = MaterialTheme.colorScheme
    val actionButtonSize = preferences.lyricsSize.scaleButtonSize(48.dp)
    val actionIconSize = preferences.lyricsSize.scaleIconSize(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxOf(56.dp, actionButtonSize)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewToolbarIcon(
            icon = Icons.Outlined.KeyboardArrowDown,
            modifier = Modifier.size(actionButtonSize),
            iconSize = actionIconSize
        )
        Spacer(Modifier.width(6.dp))
        PlaybackPreviewCover(
            size = 42.dp,
            cornerRadius = 10.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.onboarding_controls_preview_song),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.onboarding_controls_preview_artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        PreviewToolbarIcon(
            icon = Icons.Outlined.FavoriteBorder,
            modifier = Modifier.size(actionButtonSize),
            iconSize = actionIconSize
        )
        PreviewToolbarIcon(
            icon = Icons.Filled.MoreVert,
            modifier = Modifier.size(actionButtonSize),
            iconSize = actionIconSize
        )
    }
}

@Composable
private fun PlaybackPlacementChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OnboardingControlShape)
            .clickable(onClick = onClick),
        shape = OnboardingControlShape,
        color = if (selected) colors.secondaryContainer else colors.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface
            )
        }
    }
}

@Composable
private fun OnboardingSectionHeader(
    icon: ImageVector,
    title: String,
    description: String
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = OnboardingControlShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionGuidanceItem(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            HapticOutlinedButton(
                onClick = onRequest,
                enabled = !granted,
                shape = OnboardingControlShape,
                modifier = Modifier.widthIn(min = 72.dp)
            ) {
                Text(
                    text = if (granted) {
                        stringResource(R.string.onboarding_permission_granted)
                    } else {
                        stringResource(R.string.onboarding_permission_allow)
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ControlSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
            content()
        }
    }
}

@Composable
private fun PlaybackLayoutPreview(
    preferences: PlaybackControlLayoutPreferences,
    coverLyricFontScale: Float
) {
    val colors = MaterialTheme.colorScheme
    val controlsAtBottom = preferences.nowPlayingPlacement.placesControlsAtBottom
    val progressAtBottom = preferences.nowPlayingPlacement.placesProgressAtBottom
    val secondaryControlSize = preferences.nowPlayingSize.scaleButtonSize(42.dp)
    val primaryControlSize = preferences.nowPlayingSize.scaleButtonSize(42.dp)
    val preferredControlSpacing = 20.dp * preferences.nowPlayingSize.scale
    val controlIconSize = preferences.nowPlayingSize.scaleIconSize(24.dp)
    val lyricPreviewHeight = resolveOnboardingPlaybackPreviewLyricHeight(coverLyricFontScale)
    val previewHeight = resolveOnboardingPlaybackPreviewHeight(coverLyricFontScale)

    OnboardingGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight),
        shape = OnboardingCardShape,
        color = colors.surface
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val controlsLayout = resolveNowPlayingMainControlsLayout(
                availableWidth = maxWidth,
                secondaryButtonSize = secondaryControlSize,
                primaryButtonSize = primaryControlSize,
                preferredSpacing = preferredControlSpacing
            )
            // 预览需要在引导的首屏同时露出底部操作栏, 尺寸要随视口收敛
            val normalizedLyricScale = normalizeLyricFontScale(coverLyricFontScale)
            val coverSize = minOf(
                maxWidth * 0.36f,
                if (normalizedLyricScale <= 0.74f) 64.dp else 72.dp
            )
            val dockedToolbar = !controlsAtBottom
            val toolbarHeight = resolveOnboardingPlaybackToolbarHeight(
                controlSize = preferences.nowPlayingSize,
                docked = dockedToolbar
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = toolbarHeight + 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PlaybackPreviewTopBar(preferences = preferences)
                    Spacer(Modifier.height(4.dp))
                    PlaybackPreviewCover(size = coverSize)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_controls_preview_song),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.onboarding_controls_preview_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!progressAtBottom) {
                        Spacer(Modifier.height(6.dp))
                        PlaybackPreviewProgress()
                    }
                    if (!controlsAtBottom) {
                        Spacer(Modifier.height(6.dp))
                        PlaybackPreviewControls(
                            controlsLayout = controlsLayout,
                            baseSecondaryControlSize = secondaryControlSize,
                            basePrimaryControlSize = primaryControlSize,
                            baseIconSize = controlIconSize
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PlaybackPreviewLyrics(
                        modifier = Modifier
                            .height(lyricPreviewHeight),
                        lyricFontScale = coverLyricFontScale,
                        lineCount = resolveOnboardingCoverPreviewLineCount(coverLyricFontScale)
                    )
                    if (controlsAtBottom) {
                        if (progressAtBottom) {
                            PlaybackPreviewProgress()
                            Spacer(Modifier.height(8.dp))
                        }
                        PlaybackPreviewControls(
                            controlsLayout = controlsLayout,
                            baseSecondaryControlSize = secondaryControlSize,
                            basePrimaryControlSize = primaryControlSize,
                            baseIconSize = controlIconSize
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    PlaybackPreviewToolbar(
                        preferences = preferences,
                        docked = dockedToolbar
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackPreviewTopBar(preferences: PlaybackControlLayoutPreferences) {
    val actionButtonSize = preferences.nowPlayingSize.scaleButtonSize(48.dp)
    val actionIconSize = preferences.nowPlayingSize.scaleIconSize(24.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxOf(48.dp, actionButtonSize))
    ) {
        PreviewToolbarIcon(
            icon = Icons.Outlined.KeyboardArrowDown,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(actionButtonSize),
            iconSize = actionIconSize
        )
        Text(
            text = stringResource(R.string.player_now_playing),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewToolbarIcon(
                icon = Icons.Outlined.FavoriteBorder,
                modifier = Modifier.size(actionButtonSize),
                iconSize = actionIconSize
            )
            PreviewToolbarIcon(
                icon = Icons.Filled.MoreVert,
                modifier = Modifier.size(actionButtonSize),
                iconSize = actionIconSize
            )
        }
    }
}

@Composable
private fun PlaybackPreviewCover(
    size: Dp,
    cornerRadius: Dp = 24.dp
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(size),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(size * 0.36f)
            )
        }
    }
}

@Composable
private fun PlaybackPreviewProgress() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("1:18", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        WaveformSlider(
            modifier = Modifier.weight(1f),
            value = 0.42f,
            onValueChange = {},
            onValueChangeFinished = {},
            isPlaying = true,
            activeTint = colors.primary
        )
        Text("3:42", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackPreviewControls(
    controlsLayout: moe.ouom.neriplayer.ui.screen.NowPlayingMainControlsLayout,
    baseSecondaryControlSize: Dp,
    basePrimaryControlSize: Dp,
    baseIconSize: Dp
) {
    val colors = MaterialTheme.colorScheme
    val secondaryIconSize = (
        baseIconSize * (controlsLayout.secondaryButtonSize.value / baseSecondaryControlSize.value)
        ).coerceAtLeast(16.dp)
    val primaryIconSize = (
        baseIconSize * (controlsLayout.primaryButtonSize.value / basePrimaryControlSize.value)
        ).coerceAtLeast(16.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(controlsLayout.spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewControl(Icons.Outlined.Shuffle, controlsLayout.secondaryButtonSize, secondaryIconSize)
        PreviewControl(Icons.Outlined.SkipPrevious, controlsLayout.secondaryButtonSize, secondaryIconSize)
        Surface(
            modifier = Modifier.size(controlsLayout.primaryButtonSize),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = colors.primary,
            contentColor = colors.onPrimary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(primaryIconSize)
                )
            }
        }
        PreviewControl(Icons.Outlined.SkipNext, controlsLayout.secondaryButtonSize, secondaryIconSize)
        PreviewControl(Icons.Outlined.Repeat, controlsLayout.secondaryButtonSize, secondaryIconSize)
    }
}

@Composable
private fun PlaybackPreviewLyrics(
    modifier: Modifier = Modifier,
    lyricFontScale: Float = 1f,
    lineCount: Int = 3
) {
    val colors = MaterialTheme.colorScheme
    val allLyrics = listOf(
        stringResource(R.string.onboarding_controls_preview_lyric_1),
        stringResource(R.string.onboarding_controls_preview_lyric_2),
        stringResource(R.string.onboarding_controls_preview_lyric_3),
        stringResource(R.string.onboarding_controls_preview_lyric_4),
        stringResource(R.string.onboarding_controls_preview_lyric_5),
        stringResource(R.string.onboarding_controls_preview_lyric_6),
        stringResource(R.string.onboarding_controls_preview_lyric_7),
        stringResource(R.string.onboarding_controls_preview_lyric_8),
        stringResource(R.string.onboarding_controls_preview_lyric_9),
        stringResource(R.string.onboarding_controls_preview_lyric_10),
        stringResource(R.string.onboarding_controls_preview_lyric_11),
        stringResource(R.string.onboarding_controls_preview_lyric_12)
    )
    val previewWindow = resolveOnboardingCoverPreviewWindow(
        totalLineCount = allLyrics.size,
        lineCount = lineCount.coerceIn(3, 8)
    )
    val lyrics = allLyrics.subList(
        previewWindow.startIndex,
        previewWindow.endExclusive
    )
    val activeIndex = previewWindow.activeIndex
    val activeFontSize = scaledLyricFontSize(18f, lyricFontScale).sp
    val inactiveFontSize = scaledLyricFontSize(14f, lyricFontScale).sp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onboardingLyricEdgeFade(
                topFadeLength = 24.dp,
                bottomFadeLength = 30.dp
            )
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            content = {
                lyrics.forEachIndexed { index, lyric ->
                    Text(
                        text = lyric,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = if (index == activeIndex) {
                            activeFontSize
                        } else {
                            inactiveFontSize
                        },
                        lineHeight = if (index == activeIndex) {
                            (activeFontSize.value * 1.25f).sp
                        } else {
                            (inactiveFontSize.value * 1.2f).sp
                        },
                        fontWeight = if (index == activeIndex) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (index == activeIndex) {
                            colors.onSurface
                        } else {
                            colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        ) { measurables, constraints ->
            val placeables = measurables.map { measurable ->
                measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }
            val spacingPx = 6.dp.roundToPx()
            val offsetY = resolveOnboardingLyricActiveCenterOffset(
                viewportHeightPx = constraints.maxHeight,
                lineHeightsPx = placeables.map { it.height },
                lineSpacingPx = spacingPx,
                activeIndex = activeIndex
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                var y = offsetY + (
                    (constraints.maxHeight -
                        placeables.sumOf { it.height } -
                        spacingPx * (placeables.size - 1)) / 2
                    ).coerceAtLeast(0)
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        }
    }
}

private fun Modifier.onboardingLyricEdgeFade(
    topFadeLength: Dp,
    bottomFadeLength: Dp
): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    onDrawWithContent {
        drawContent()
        if (size.height <= 0f) return@onDrawWithContent
        val topFade = (topFadeLength.toPx() / size.height).coerceIn(0f, 0.48f)
        val bottomFade = (bottomFadeLength.toPx() / size.height).coerceIn(0f, 0.48f)
        if (topFade <= 0f && bottomFade <= 0f) return@onDrawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                topFade to Color.Black,
                (1f - bottomFade).coerceIn(0f, 1f) to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
}

internal fun resolveOnboardingCoverPreviewLineCount(scale: Float): Int {
    return when (normalizeLyricFontScale(scale)) {
        in MIN_LYRIC_FONT_SCALE..0.74f -> 8
        in 0.74f..0.94f -> 6
        in 0.94f..1.16f -> 3
        in 1.16f..1.38f -> 3
        else -> 3
    }
}

internal fun resolveOnboardingPlaybackPreviewLyricHeight(scale: Float): Dp {
    return when (normalizeLyricFontScale(scale)) {
        in MIN_LYRIC_FONT_SCALE..0.74f -> 136.dp
        in 0.74f..0.94f -> 124.dp
        in 0.94f..1.16f -> 112.dp
        else -> 120.dp
    }
}

internal fun resolveOnboardingPlaybackPreviewHeight(scale: Float): Dp {
    return 440.dp + (
        resolveOnboardingPlaybackPreviewLyricHeight(scale) - 80.dp
        )
}

internal fun resolveOnboardingPlaybackToolbarHeight(
    controlSize: PlaybackControlSize,
    docked: Boolean
): Dp = controlSize.scaleButtonSize(48.dp) + if (docked) 16.dp else 12.dp

@Composable
private fun PlaybackPreviewToolbar(
    preferences: PlaybackControlLayoutPreferences,
    modifier: Modifier = Modifier,
    controlSize: PlaybackControlSize = preferences.nowPlayingSize,
    docked: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val toolbarHeight = resolveOnboardingPlaybackToolbarHeight(
        controlSize = controlSize,
        docked = docked
    )
    if (docked) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(toolbarHeight),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            color = colors.surfaceContainerHigh
        ) {
            PlaybackPreviewToolbarContent(
                controlSize = controlSize,
                docked = docked,
                toolbarHeight = toolbarHeight
            )
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(toolbarHeight),
            contentAlignment = Alignment.Center
        ) {
            PlaybackPreviewToolbarContent(
                controlSize = controlSize,
                docked = docked,
                toolbarHeight = toolbarHeight
            )
        }
    }
}

@Composable
private fun PlaybackPreviewToolbarContent(
    controlSize: PlaybackControlSize,
    docked: Boolean,
    toolbarHeight: Dp
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(toolbarHeight)
    ) {
        val toolbarLayout = resolvePlaybackActionToolbarLayout(
            availableWidth = maxWidth,
            preferredHorizontalPadding = if (docked) 18.dp else 6.dp,
            defaultIconSize = controlSize.scaleIconSize(20.dp),
            preferredMinimumTouchTarget = controlSize.scaleButtonSize(48.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = toolbarLayout.horizontalPadding),
            horizontalArrangement = when {
                toolbarLayout.useEqualWidthSlots -> Arrangement.Start
                docked -> Arrangement.SpaceEvenly
                else -> Arrangement.SpaceBetween
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val toolbarActionModifier = if (toolbarLayout.useEqualWidthSlots) {
                Modifier.weight(1f)
            } else {
                Modifier
            }
            listOf(
                Icons.AutoMirrored.Outlined.QueueMusic,
                Icons.Outlined.Timer,
                Icons.Filled.SpeakerGroup,
                Icons.Outlined.LibraryMusic,
                Icons.AutoMirrored.Outlined.PlaylistAdd
            ).forEach { icon ->
                PreviewToolbarIcon(
                    icon = icon,
                    modifier = toolbarActionModifier.size(
                        toolbarLayout.minimumInteractiveComponentSize
                    ),
                    iconSize = toolbarLayout.iconSize
                )
            }
        }
    }
}

@Composable
private fun PreviewToolbarIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun PreviewControl(icon: ImageVector, buttonSize: Dp, iconSize: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(buttonSize),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun playbackControlSizeLabel(size: PlaybackControlSize): String = when (size) {
    PlaybackControlSize.SMALL -> stringResource(R.string.settings_playback_control_size_small)
    PlaybackControlSize.MEDIUM -> stringResource(R.string.settings_playback_control_size_medium)
    PlaybackControlSize.LARGE -> stringResource(R.string.settings_playback_control_size_large)
}

@Composable
private fun placementLabel(placement: NowPlayingControlPlacement): String = when (placement) {
    NowPlayingControlPlacement.LOWER ->
        stringResource(R.string.settings_nowplaying_control_placement_lower)
    NowPlayingControlPlacement.BOTTOM ->
        stringResource(R.string.settings_nowplaying_control_placement_bottom)
    NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS ->
        stringResource(R.string.settings_nowplaying_control_placement_bottom_with_progress)
}
