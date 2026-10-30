@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

internal enum class NowPlayingLyricsSharedTransitionElement(
    val key: String
) {
    BACK("btn_back"),
    COVER("cover_image"),
    ARTIST("song_artist"),
    PROGRESS("progress_bar"),
    PREVIOUS("player_previous"),
    PLAY("play_button"),
    NEXT("player_next")
}

private val OrdinaryLyricsEnterTransition = fadeIn()
private val OrdinaryLyricsExitTransition = fadeOut()
private val OrdinaryLyricsSizeTransform = SizeTransform()

internal val LocalNowPlayingLyricsBoundsTransform = compositionLocalOf {
    SharedTransitionDefaults.BoundsTransform
}
internal val LocalNowPlayingLyricsEnterTransition = compositionLocalOf {
    OrdinaryLyricsEnterTransition
}
internal val LocalNowPlayingLyricsExitTransition = compositionLocalOf {
    OrdinaryLyricsExitTransition
}

// 预览直接定位普通切页的 spring 时间线，松手后继续同一条曲线
private val PredictiveLyricsBoundsTransform = SharedTransitionDefaults.BoundsTransform
private val PredictiveLyricsEnterTransition = OrdinaryLyricsEnterTransition
private val PredictiveLyricsExitTransition = OrdinaryLyricsExitTransition
private val PredictiveLyricsSizeTransform = OrdinaryLyricsSizeTransform

@Composable
internal fun NowPlayingLyricsSharedMotion(previewActive: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalNowPlayingLyricsBoundsTransform provides nowPlayingLyricsBoundsTransform(previewActive),
        LocalNowPlayingLyricsEnterTransition provides nowPlayingLyricsEnterTransition(previewActive),
        LocalNowPlayingLyricsExitTransition provides nowPlayingLyricsExitTransition(previewActive),
        content = content
    )
}

private fun nowPlayingLyricsBoundsTransform(previewActive: Boolean): BoundsTransform =
    if (previewActive) PredictiveLyricsBoundsTransform else SharedTransitionDefaults.BoundsTransform

private fun nowPlayingLyricsEnterTransition(previewActive: Boolean): EnterTransition =
    if (previewActive) PredictiveLyricsEnterTransition else OrdinaryLyricsEnterTransition

private fun nowPlayingLyricsExitTransition(previewActive: Boolean): ExitTransition =
    if (previewActive) PredictiveLyricsExitTransition else OrdinaryLyricsExitTransition

internal fun nowPlayingLyricsSizeTransform(previewActive: Boolean): SizeTransform =
    if (previewActive) PredictiveLyricsSizeTransform else OrdinaryLyricsSizeTransform
