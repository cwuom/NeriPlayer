package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingFavoriteIconColor
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingLyricsSharedTransitionElement

internal data class NowPlayingFavoritePresentation(
    val icon: ImageVector,
    val labelRes: Int,
    val tint: Color
)

internal fun nowPlayingFavoritePresentation(
    isFavorite: Boolean,
    defaultTint: Color
): NowPlayingFavoritePresentation = if (isFavorite) {
    NowPlayingFavoritePresentation(
        icon = Icons.Filled.Favorite,
        labelRes = CoreCommonR.string.nowplaying_favorited,
        tint = NowPlayingFavoriteIconColor
    )
} else {
    NowPlayingFavoritePresentation(
        icon = Icons.Outlined.FavoriteBorder,
        labelRes = CoreCommonR.string.nowplaying_favorite,
        tint = defaultTint
    )
}

internal fun resolveNowPlayingTopBarButtonSize(
    availableWidth: Dp,
    preferredSize: Dp,
    commentAvailable: Boolean
): Dp = minOf(preferredSize, availableWidth / if (commentAvailable) 4 else 3)

internal fun resolveNowPlayingPhoneTopActionButtonSize(
    availableWidth: Dp,
    preferredSize: Dp,
    commentAvailable: Boolean
): Dp = minOf(preferredSize, availableWidth * 0.5f / if (commentAvailable) 3 else 2)

internal fun nowPlayingBackIcon(phoneLandscape: Boolean): ImageVector =
    if (phoneLandscape) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.KeyboardArrowDown

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingCoverTopBar(
    isFavorite: Boolean,
    favoriteEnabled: Boolean,
    commentAvailable: Boolean,
    height: Dp,
    buttonSize: Dp,
    iconSize: Dp,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateUp: () -> Unit,
    onFavorite: () -> Unit,
    onComment: () -> Unit,
    onMoreOptions: () -> Unit,
    phoneLandscape: Boolean = false
) {
    with(sharedTransitionScope) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(height)) {
            val resolvedButtonSize = resolveNowPlayingTopBarButtonSize(maxWidth, buttonSize, commentAvailable)
            val resolvedIconSize = (iconSize * (resolvedButtonSize.value / buttonSize.value)).coerceAtLeast(18.dp)
            NowPlayingCoverBackButton(
                buttonSize = resolvedButtonSize,
                iconSize = resolvedIconSize,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onNavigateUp = onNavigateUp,
                phoneLandscape = phoneLandscape,
                modifier = Modifier.align(Alignment.CenterStart)
            )

            NowPlayingCoverTopActions(
                isFavorite = isFavorite,
                favoriteEnabled = favoriteEnabled,
                commentAvailable = commentAvailable,
                buttonSize = resolvedButtonSize,
                iconSize = resolvedIconSize,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onFavorite = onFavorite,
                onComment = onComment,
                onMoreOptions = onMoreOptions,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingCoverBackButton(
    buttonSize: Dp,
    iconSize: Dp,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateUp: () -> Unit,
    phoneLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    with(sharedTransitionScope) {
        HapticIconButton(
            onClick = onNavigateUp,
            modifier = modifier.testTag("nowPlayingBackButton").size(buttonSize)
                .sharedBounds(
                    rememberSharedContentState(key = NowPlayingLyricsSharedTransitionElement.BACK.key),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None
                ).zIndex(1f)
        ) {
            Icon(
                nowPlayingBackIcon(phoneLandscape),
                contentDescription = stringResource(CoreCommonR.string.action_back),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingCoverTopActions(
    isFavorite: Boolean,
    favoriteEnabled: Boolean,
    commentAvailable: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onFavorite: () -> Unit,
    onComment: () -> Unit,
    onMoreOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    with(sharedTransitionScope) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            NowPlayingFavoriteButton(
                isFavorite = isFavorite,
                enabled = favoriteEnabled,
                buttonSize = buttonSize,
                iconSize = iconSize,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onClick = onFavorite
            )
            NowPlayingCommentButton(
                available = commentAvailable,
                buttonSize = buttonSize,
                iconSize = iconSize,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onClick = onComment
            )
            HapticIconButton(
                onClick = onMoreOptions,
                modifier = Modifier.size(buttonSize)
                    .sharedBounds(
                        rememberSharedContentState(key = "btn_more"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = EnterTransition.None,
                        exit = ExitTransition.None
                    ).zIndex(1f)
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(CoreCommonR.string.nowplaying_more_options),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingFavoriteButton(
    isFavorite: Boolean,
    enabled: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit
) {
    val presentation = nowPlayingFavoritePresentation(
        isFavorite = isFavorite,
        defaultTint = MaterialTheme.colorScheme.onSurface
    )
    with(sharedTransitionScope) {
        HapticIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(buttonSize)
                .sharedElement(
                    rememberSharedContentState(key = "btn_favorite"),
                    animatedVisibilityScope = animatedVisibilityScope
                ).zIndex(1f)
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = stringResource(presentation.labelRes),
                modifier = Modifier.size(iconSize),
                tint = presentation.tint
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingCommentButton(
    available: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit
) {
    if (!available) return
    with(sharedTransitionScope) {
        HapticIconButton(
            onClick = onClick,
            modifier = Modifier.size(buttonSize)
                .sharedBounds(
                    rememberSharedContentState(key = "btn_comment"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None
                ).zIndex(1f)
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Comment,
                contentDescription = stringResource(CoreCommonR.string.comment_entry),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
