package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import moe.ouom.neriplayer.core.player.audio.icon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.nowplaying.PlaybackActionToolbarLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.rememberAudioDeviceInfo
import moe.ouom.neriplayer.ui.screen.nowplaying.resolvePlaybackActionToolbarLayout

internal data class NowPlayingCoverToolbarLayoutSpec(
    val wideLandscape: Boolean,
    val compactPortrait: Boolean,
    val docked: Boolean,
    val iconSize: Dp,
    val minimumTouchTarget: Dp,
    val compactHeight: Boolean = false,
    val lyricsAdjustBehavior: Boolean = wideLandscape
)

internal fun resolveNowPlayingCoverToolbarLayoutSpec(
    wideLandscape: Boolean,
    compactPortrait: Boolean,
    dockEnabled: Boolean,
    iconSize: Dp,
    minimumTouchTarget: Dp,
    compactHeight: Boolean,
    lyricsAdjustBehavior: Boolean = wideLandscape
): NowPlayingCoverToolbarLayoutSpec = NowPlayingCoverToolbarLayoutSpec(
    wideLandscape = wideLandscape,
    compactPortrait = compactPortrait,
    // 短横屏把高度留给封面和歌词，不使用增加留白的装饰底座
    docked = dockEnabled && !compactHeight,
    iconSize = iconSize,
    minimumTouchTarget = minimumTouchTarget,
    compactHeight = compactHeight,
    lyricsAdjustBehavior = lyricsAdjustBehavior
)

internal data class NowPlayingCoverToolbarStatus(
    val sleepTimerActive: Boolean,
    val lyricsAvailable: Boolean,
    val lyricsShowing: Boolean,
    val activeColor: Color
)

internal data class NowPlayingCoverToolbarActions(
    val onQueue: () -> Unit,
    val onSleepTimer: () -> Unit,
    val onVolume: () -> Unit,
    val onLyrics: () -> Unit,
    val onAddToPlaylist: () -> Unit
)

internal fun resolveNowPlayingLyricsToolbarAction(
    lyricsAdjustBehavior: Boolean,
    onAdjust: () -> Unit,
    onSwitchPage: () -> Unit
): () -> Unit = if (lyricsAdjustBehavior) onAdjust else onSwitchPage

internal fun shouldAdjustNowPlayingLyricsBehavior(
    isLandscape: Boolean,
    phoneLandscape: Boolean
): Boolean = isLandscape && !phoneLandscape

internal fun nowPlayingLyricsToolbarIcon(lyricsAdjustBehavior: Boolean): ImageVector =
    if (lyricsAdjustBehavior) Icons.Outlined.Tune else Icons.Outlined.LibraryMusic

internal fun nowPlayingLyricsToolbarDescription(
    lyricsAdjustBehavior: Boolean,
    pageSwitchDescription: Int = CoreCommonR.string.lyrics_title
): Int = if (lyricsAdjustBehavior) CoreCommonR.string.lyrics_adjust_behavior else pageSwitchDescription

internal fun toolbarWidthFraction(spec: NowPlayingCoverToolbarLayoutSpec): Float =
    if (spec.wideLandscape && !spec.compactHeight) 0.9f else 1f

private fun toolbarHorizontalInset(spec: NowPlayingCoverToolbarLayoutSpec): Dp =
    if (spec.wideLandscape) 0.dp else toolbarPortraitHorizontalInset(spec.compactPortrait)

private fun toolbarPortraitHorizontalInset(compactPortrait: Boolean): Dp =
    if (compactPortrait) 4.dp else 16.dp

private fun toolbarVerticalInset(wideLandscape: Boolean): Dp = if (wideLandscape) 0.dp else 8.dp

private fun toolbarBottomInset(spec: NowPlayingCoverToolbarLayoutSpec): Dp =
    if (spec.wideLandscape) 0.dp else toolbarPortraitBottomInset(spec.docked)

private fun toolbarPortraitBottomInset(docked: Boolean): Dp = if (docked) 2.dp else 0.dp

internal fun toolbarPreferredPadding(spec: NowPlayingCoverToolbarLayoutSpec): Dp =
    if (spec.compactPortrait || spec.compactHeight) 0.dp else toolbarExpandedPreferredPadding(spec)

private fun toolbarExpandedPreferredPadding(spec: NowPlayingCoverToolbarLayoutSpec): Dp =
    if (spec.docked || spec.wideLandscape) 18.dp else 6.dp

internal fun toolbarRowVerticalPadding(spec: NowPlayingCoverToolbarLayoutSpec): Dp =
    if (spec.compactHeight) 0.dp else if (spec.docked || spec.wideLandscape) 12.dp else 8.dp

internal fun toolbarRowArrangement(
    layout: PlaybackActionToolbarLayout,
    spec: NowPlayingCoverToolbarLayoutSpec
): Arrangement.Horizontal = if (layout.useEqualWidthSlots) Arrangement.Start
else toolbarSpacedArrangement(spec)

private fun toolbarSpacedArrangement(spec: NowPlayingCoverToolbarLayoutSpec): Arrangement.Horizontal =
    if (spec.wideLandscape || spec.docked) Arrangement.SpaceEvenly else Arrangement.SpaceBetween

private fun Modifier.toolbarActionModifier(
    equalWidthSlots: Boolean,
    rowScope: RowScope
): Modifier = with(rowScope) {
    if (equalWidthSlots) this@toolbarActionModifier.weight(1f) else this@toolbarActionModifier
}

private fun toolbarActiveTint(active: Boolean, activeColor: Color, normalColor: Color): Color =
    if (active) activeColor else normalColor

private fun toolbarLyricsTint(
    available: Boolean,
    showing: Boolean,
    activeColor: Color,
    normalColor: Color
): Color = if (available) toolbarActiveTint(showing, activeColor, normalColor)
else normalColor.copy(alpha = 0.38f)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingCoverActionToolbar(
    spec: NowPlayingCoverToolbarLayoutSpec,
    status: NowPlayingCoverToolbarStatus,
    actions: NowPlayingCoverToolbarActions,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(toolbarWidthFraction(spec))
            .then(
                if (spec.wideLandscape) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            )
            .padding(
                horizontal = toolbarHorizontalInset(spec),
                vertical = toolbarVerticalInset(spec.wideLandscape)
            )
            .padding(bottom = toolbarBottomInset(spec)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NowPlayingCoverToolbarDock(spec, status, actions, sharedTransitionScope, animatedVisibilityScope)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCoverToolbarDock(
    spec: NowPlayingCoverToolbarLayoutSpec,
    status: NowPlayingCoverToolbarStatus,
    actions: NowPlayingCoverToolbarActions,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    if (spec.docked) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f)
            )
        ) {
            NowPlayingCoverToolbarContent(spec, status, actions, sharedTransitionScope, animatedVisibilityScope)
        }
    } else {
        NowPlayingCoverToolbarContent(spec, status, actions, sharedTransitionScope, animatedVisibilityScope)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCoverToolbarContent(
    spec: NowPlayingCoverToolbarLayoutSpec,
    status: NowPlayingCoverToolbarStatus,
    actions: NowPlayingCoverToolbarActions,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val layout =
            resolvePlaybackActionToolbarLayout(
                availableWidth = maxWidth,
                preferredHorizontalPadding = toolbarPreferredPadding(spec),
                defaultIconSize = spec.iconSize,
                preferredMinimumTouchTarget = spec.minimumTouchTarget
            )
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides layout.minimumInteractiveComponentSize
        ) {
            NowPlayingCoverToolbarRow(spec, layout, status, actions, sharedTransitionScope, animatedVisibilityScope)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCoverToolbarRow(
    spec: NowPlayingCoverToolbarLayoutSpec,
    layout: PlaybackActionToolbarLayout,
    status: NowPlayingCoverToolbarStatus,
    actions: NowPlayingCoverToolbarActions,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = layout.horizontalPadding,
            vertical = toolbarRowVerticalPadding(spec)
        ),
        horizontalArrangement = toolbarRowArrangement(layout, spec),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val buttonModifier = Modifier.toolbarActionModifier(layout.useEqualWidthSlots, this)
        NowPlayingCoverToolbarButton(
            "btn_queue", Icons.AutoMirrored.Outlined.QueueMusic, stringResource(CoreCommonR.string.playlist_queue),
            layout.iconSize, buttonModifier, sharedTransitionScope, animatedVisibilityScope,
            actions.onQueue, LocalContentColor.current
        )
        NowPlayingCoverToolbarButton(
            "btn_timer", Icons.Outlined.Timer, stringResource(CoreCommonR.string.sleep_timer_short),
            layout.iconSize, buttonModifier, sharedTransitionScope, animatedVisibilityScope,
            actions.onSleepTimer,
            toolbarActiveTint(
                status.sleepTimerActive, status.activeColor, LocalContentColor.current
            )
        )
        val audioDevice = rememberAudioDeviceInfo()
        NowPlayingCoverToolbarButton(
            "btn_volume", audioDevice.second, audioDevice.first,
            layout.iconSize, buttonModifier, sharedTransitionScope, animatedVisibilityScope,
            actions.onVolume, LocalContentColor.current
        )
        NowPlayingCoverLyricsButton(
            status, layout.iconSize, buttonModifier, sharedTransitionScope,
            animatedVisibilityScope, actions.onLyrics, spec.lyricsAdjustBehavior
        )
        NowPlayingCoverToolbarButton(
            "btn_add", Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(CoreCommonR.string.playlist_add_to),
            layout.iconSize, buttonModifier, sharedTransitionScope, animatedVisibilityScope,
            actions.onAddToPlaylist, LocalContentColor.current
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCoverToolbarButton(
    sharedKey: String,
    icon: ImageVector,
    contentDescription: String,
    iconSize: Dp,
    modifier: Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    tint: Color
) {
    with(sharedTransitionScope) {
        HapticIconButton(
            onClick = onClick,
            modifier = modifier.sharedBounds(
                rememberSharedContentState(key = sharedKey),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = EnterTransition.None,
                exit = ExitTransition.None
            ).zIndex(1f)
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCoverLyricsButton(
    status: NowPlayingCoverToolbarStatus,
    iconSize: Dp,
    modifier: Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    lyricsAdjustBehavior: Boolean
) {
    with(sharedTransitionScope) {
        HapticIconButton(
            onClick = onClick,
            enabled = status.lyricsAvailable,
            modifier = modifier.testTag("nowPlayingLyricsAction").sharedBounds(
                rememberSharedContentState(key = "btn_lyrics"),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = EnterTransition.None,
                exit = ExitTransition.None
            ).zIndex(1f)
        ) {
            AnimatedContent(targetState = status.lyricsShowing, label = "lyrics_icon") { showing ->
                Icon(
                    imageVector = nowPlayingLyricsToolbarIcon(lyricsAdjustBehavior),
                    contentDescription = stringResource(nowPlayingLyricsToolbarDescription(lyricsAdjustBehavior)),
                    tint = toolbarLyricsTint(
                        status.lyricsAvailable, showing, status.activeColor, LocalContentColor.current
                    ),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}
