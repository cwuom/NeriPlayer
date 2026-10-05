package moe.ouom.neriplayer.ui.screen.nowplaying

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.ui.component.playback.NowPlayingSongTitle

internal data class NowPlayingTrackDisplay(
    val name: String?,
    val artist: String?
)

internal fun resolveNowPlayingTrackDisplay(song: SongItem?): NowPlayingTrackDisplay =
    NowPlayingTrackDisplay(song?.displayName(), song?.displayArtist())

internal data class NowPlayingTrackIdentityPresentation(
    val titleStyle: TextStyle,
    val artistStyle: TextStyle,
    val horizontalAlignment: Alignment.Horizontal
)

internal fun resolveNowPlayingTrackIdentityPresentation(
    compact: Boolean,
    typography: Typography
): NowPlayingTrackIdentityPresentation = if (compact) {
    NowPlayingTrackIdentityPresentation(typography.titleLarge, typography.bodyMedium, Alignment.Start)
} else {
    NowPlayingTrackIdentityPresentation(typography.headlineSmall, typography.bodyLarge, Alignment.CenterHorizontally)
}

internal class NowPlayingTrackIdentityOwner {
    private data class CopyContext(val clipboard: Clipboard, val scope: CoroutineScope)

    private var copyContext: CopyContext? = null
    var showNameMenu by mutableStateOf(false)
        private set
    var showArtistMenu by mutableStateOf(false)
        private set

    val openArtistMenuAction: () -> Unit = { openArtistMenu() }
    val closeArtistMenuAction: () -> Unit = { closeArtistMenu() }

    fun bindCopyContext(clipboard: Clipboard, scope: CoroutineScope) {
        copyContext = CopyContext(clipboard, scope)
    }

    fun openNameMenu() { showNameMenu = true }
    fun closeNameMenu() { showNameMenu = false }
    fun openArtistMenu() { showArtistMenu = true }
    fun closeArtistMenu() { showArtistMenu = false }

    fun copyName(text: String?) {
        copyContext?.let { copyNowPlayingText(text, it.clipboard, it.scope) }
        closeNameMenu()
    }

    fun copyArtist(text: String?) {
        copyContext?.let { copyNowPlayingText(text, it.clipboard, it.scope) }
        closeArtistMenu()
    }

    fun artistCopyAction(text: String?): () -> Unit = { copyArtist(text) }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingTrackIdentity(
    display: NowPlayingTrackDisplay,
    visible: Boolean,
    marqueeEnabled: Boolean,
    titleColor: Color,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onArtistClick: () -> Unit,
    compact: Boolean = false
) {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val owner = remember { NowPlayingTrackIdentityOwner() }
    owner.bindCopyContext(clipboard, clipboardScope)
    val presentation = resolveNowPlayingTrackIdentityPresentation(compact, MaterialTheme.typography)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 400, delayMillis = 150),
            initialOffsetY = { it / 4 }
        ) + fadeIn(animationSpec = tween(durationMillis = 400, delayMillis = 150))
    ) {
        Column(horizontalAlignment = presentation.horizontalAlignment) {
            NowPlayingTrackTitle(
                display.name, marqueeEnabled, titleColor, owner, presentation.titleStyle
            )
            NowPlayingTrackArtist(
                display.artist, sharedTransitionScope, animatedVisibilityScope,
                onArtistClick, owner, presentation.artistStyle
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingTrackTitle(
    displayName: String?,
    marqueeEnabled: Boolean,
    titleColor: Color,
    owner: NowPlayingTrackIdentityOwner,
    titleStyle: TextStyle
) {
    BoxWithConstraints {
        NowPlayingSongTitle(
            text = displayName.orEmpty(),
            marqueeEnabled = marqueeEnabled,
            style = titleStyle,
            color = titleColor,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = {}, onLongClick = owner::openNameMenu)
        )
        DropdownMenu(
            expanded = owner.showNameMenu,
            onDismissRequest = owner::closeNameMenu
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.action_copy_song_name)) },
                onClick = { owner.copyName(displayName) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingTrackArtist(
    displayArtist: String?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onArtistClick: () -> Unit,
    owner: NowPlayingTrackIdentityOwner,
    artistStyle: TextStyle
) {
    Box {
        Text(
            text = displayArtist.orEmpty(),
            style = artistStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .nowPlayingArtistSharedModifier(sharedTransitionScope, animatedVisibilityScope)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onArtistClick, onLongClick = owner.openArtistMenuAction)
        )
        NowPlayingArtistCopyMenu(displayArtist, owner)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.nowPlayingArtistSharedModifier(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = with(sharedTransitionScope) {
    this@nowPlayingArtistSharedModifier.sharedElement(
        rememberSharedContentState(key = NowPlayingLyricsSharedTransitionElement.ARTIST.key),
        animatedVisibilityScope = animatedVisibilityScope
    )
}

@Composable
private fun NowPlayingArtistCopyMenu(
    displayArtist: String?,
    owner: NowPlayingTrackIdentityOwner
) {
    DropdownMenu(
        expanded = owner.showArtistMenu,
        onDismissRequest = owner.closeArtistMenuAction
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(CoreCommonR.string.action_copy_artist)) },
            onClick = owner.artistCopyAction(displayArtist)
        )
    }
}

private fun copyNowPlayingText(text: String?, clipboard: Clipboard, scope: CoroutineScope) {
    if (text == null) return
    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text))) }
}
