package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScalePage
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLeadingControls
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLeadingProgress
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingTrailingControls
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

private val NowPlayingCompactLandscapeHeight = 520.dp
private val NowPlayingExpandedPlayerPaneMaxWidth = 500.dp
private val NowPlayingCompactPlayerPaneMaxWidth = 340.dp
private val NowPlayingPhoneLandscapeMinimumContentHeight = 240.dp
private val NowPlayingTabletPortraitFixedContentReserve = 480.dp
private val NowPlayingTabletPortraitCoverMaxSize = 336.dp
private const val NOW_PLAYING_EXPANDED_CONTROLS_SPACER_WEIGHT = 0.12f

internal fun isNowPlayingPhoneLandscape(
    isLandscape: Boolean,
    smallestScreenWidthDp: Int
): Boolean = isLandscape && smallestScreenWidthDp < PHONE_SMALLEST_SCREEN_WIDTH_DP

internal fun isNowPlayingTabletPortrait(
    isLandscape: Boolean,
    smallestScreenWidthDp: Int
): Boolean = !isLandscape && smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP

internal fun shouldUseStandaloneNowPlayingLyricsPage(
    showLyricsScreen: Boolean,
    wideLandscape: Boolean
): Boolean = showLyricsScreen && !wideLandscape

internal fun resolveNowPlayingLyricFontPage(showLyricsScreen: Boolean): LyricFontScalePage =
    if (showLyricsScreen) LyricFontScalePage.LYRICS else LyricFontScalePage.COVER

internal fun resolveNowPlayingPageControlSize(
    preferences: PlaybackControlLayoutPreferences,
    showLyricsScreen: Boolean
): PlaybackControlSize = if (showLyricsScreen) preferences.lyricsSize else preferences.nowPlayingSize

internal data class NowPlayingControlBaseSizes(
    val secondaryButtonSize: Dp,
    val primaryButtonSize: Dp,
    val spacing: Dp,
    val iconSize: Dp
)

internal fun resolveNowPlayingControlBaseSizes(
    phoneLandscape: Boolean,
    wideLandscape: Boolean,
    compactWideLayout: Boolean,
    compactPortrait: Boolean
): NowPlayingControlBaseSizes = when {
    phoneLandscape -> NowPlayingControlBaseSizes(36.dp, 40.dp, 12.dp, 20.dp)
    wideLandscape && compactWideLayout -> NowPlayingControlBaseSizes(42.dp, 46.dp, 18.dp, 24.dp)
    wideLandscape -> NowPlayingControlBaseSizes(46.dp, 50.dp, 22.dp, 24.dp)
    compactPortrait -> NowPlayingControlBaseSizes(42.dp, 42.dp, 12.dp, 24.dp)
    else -> NowPlayingControlBaseSizes(42.dp, 42.dp, 20.dp, 24.dp)
}

internal data class NowPlayingWideLayoutSpec(
    val compactHeight: Boolean,
    val contentHeight: Dp,
    val scrollEnabled: Boolean,
    val playerPaneWidth: Dp,
    val paneSpacing: Dp,
    val sectionSpacing: Dp,
    val coverVerticalPadding: Dp,
    val progressVerticalOffset: Dp,
    val controlAreaVerticalOffset: Dp
)

internal fun resolveNowPlayingWideLayoutSpec(
    availableWidth: Dp,
    availableHeight: Dp,
    phoneLandscape: Boolean = false
): NowPlayingWideLayoutSpec {
    val compactHeight = phoneLandscape || availableHeight < NowPlayingCompactLandscapeHeight
    val contentHeight = nowPlayingWideContentHeight(availableHeight, phoneLandscape)
    val controlAreaOffset = nowPlayingWideControlAreaOffset(phoneLandscape, compactHeight)
    return NowPlayingWideLayoutSpec(
        compactHeight = compactHeight,
        contentHeight = contentHeight,
        scrollEnabled = contentHeight > availableHeight,
        playerPaneWidth = if (compactHeight) {
            minOf(availableWidth * 0.40f, NowPlayingCompactPlayerPaneMaxWidth)
        } else {
            minOf(availableWidth * 0.44f, NowPlayingExpandedPlayerPaneMaxWidth)
        },
        paneSpacing = if (compactHeight) 16.dp else 40.dp,
        sectionSpacing = if (compactHeight) 4.dp else 16.dp,
        coverVerticalPadding = if (compactHeight) 8.dp else 12.dp,
        progressVerticalOffset = controlAreaOffset * 2,
        controlAreaVerticalOffset = controlAreaOffset
    )
}

private fun nowPlayingWideContentHeight(availableHeight: Dp, phoneLandscape: Boolean): Dp =
    if (phoneLandscape) maxOf(availableHeight, NowPlayingPhoneLandscapeMinimumContentHeight) else availableHeight

private fun nowPlayingWideControlAreaOffset(phoneLandscape: Boolean, compactHeight: Boolean): Dp = when {
    phoneLandscape -> 0.dp
    compactHeight -> (-4).dp
    else -> (-12).dp
}

internal enum class NowPlayingWideControlSlot { PROGRESS, CONTROLS }

internal data class NowPlayingWideControlPlacement(
    val leading: List<NowPlayingWideControlSlot>,
    val trailing: List<NowPlayingWideControlSlot>
)

internal fun resolveNowPlayingWideControlPlacement(
    controlsAtBottom: Boolean,
    progressAtBottom: Boolean,
    compactHeight: Boolean = false
): NowPlayingWideControlPlacement = NowPlayingWideControlPlacement(
    leading = buildList {
        if (!progressAtBottom) add(NowPlayingWideControlSlot.PROGRESS)
        if (!controlsAtBottom) add(NowPlayingWideControlSlot.CONTROLS)
    },
    trailing = buildList {
        if (!compactHeight || controlsAtBottom) {
            if (progressAtBottom) add(NowPlayingWideControlSlot.PROGRESS)
            if (controlsAtBottom) add(NowPlayingWideControlSlot.CONTROLS)
        }
    }
)

internal data class NowPlayingWideControlOffsets(
    val identity: Dp,
    val progress: Dp,
    val controls: Dp,
    val toolbar: Dp
)

internal fun resolveNowPlayingWideControlOffsets(
    spec: NowPlayingWideLayoutSpec,
    placement: NowPlayingWideControlPlacement
): NowPlayingWideControlOffsets {
    // 宽松横屏把信息和其下控件一起抬高，短横屏顶部没有同样的留白
    val leadingOffset = if (spec.compactHeight) 0.dp else spec.controlAreaVerticalOffset
    return NowPlayingWideControlOffsets(
        identity = leadingOffset,
        progress = if (NowPlayingWideControlSlot.PROGRESS in placement.trailing) {
            spec.progressVerticalOffset
        } else {
            spec.progressVerticalOffset - spec.controlAreaVerticalOffset + leadingOffset
        },
        controls = if (NowPlayingWideControlSlot.CONTROLS in placement.trailing) {
            spec.controlAreaVerticalOffset
        } else {
            leadingOffset
        },
        toolbar = spec.controlAreaVerticalOffset
    )
}

internal data class NowPlayingWideLyricViewport(
    val offset: Dp,
    val topFadeLength: Dp,
    val bottomFadeLength: Dp
)

internal fun resolveNowPlayingWideLyricViewport(availableHeight: Dp): NowPlayingWideLyricViewport =
    NowPlayingWideLyricViewport(
        offset = minOf(availableHeight * 0.18f, 72.dp),
        topFadeLength = minOf(availableHeight * 0.20f, 132.dp),
        bottomFadeLength = minOf(availableHeight * 0.28f, 220.dp)
    )

internal data class NowPlayingTabletPortraitLayoutSpec(
    val contentWidth: Dp,
    val coverSize: Dp,
    val controlsWidth: Dp,
    val toolbarWidth: Dp
)

internal fun resolveNowPlayingTabletPortraitLayoutSpec(
    availableWidth: Dp,
    availableHeight: Dp
): NowPlayingTabletPortraitLayoutSpec {
    val contentWidth = minOf(availableWidth, 560.dp)
    // 短竖屏优先为控件和歌词留空间，封面只占预留后高度的一半
    val coverHeightBudget = (availableHeight - NowPlayingTabletPortraitFixedContentReserve)
        .coerceAtLeast(0.dp) * 0.5f
    return NowPlayingTabletPortraitLayoutSpec(
        contentWidth = contentWidth,
        coverSize = minOf(
            contentWidth * 0.66f, availableHeight * 0.30f,
            NowPlayingTabletPortraitCoverMaxSize, coverHeightBudget
        ),
        controlsWidth = minOf(contentWidth, 440.dp),
        toolbarWidth = minOf(contentWidth, 400.dp)
    )
}

@Composable
internal fun NowPlayingTabletPortraitLayout(
    controlsAtBottom: Boolean,
    progressAtBottom: Boolean,
    lyricsVisible: Boolean,
    topBar: @Composable () -> Unit,
    cover: @Composable (Modifier, Dp) -> Unit,
    identity: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
    lyrics: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.testTag("nowPlayingTabletPortraitLayout"),
        contentAlignment = Alignment.TopCenter
    ) {
        val spec = resolveNowPlayingTabletPortraitLayoutSpec(maxWidth, maxHeight)
        val centeredProgress: @Composable () -> Unit = {
            Box(Modifier.width(spec.controlsWidth)) { progress() }
        }
        val centeredControls: @Composable () -> Unit = {
            Box(Modifier.width(spec.controlsWidth), contentAlignment = Alignment.Center) { controls() }
        }
        Column(
            modifier = Modifier.width(spec.contentWidth).fillMaxHeight()
                .testTag("nowPlayingTabletPortraitContent"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            topBar()
            Spacer(Modifier.height(12.dp))
            cover(Modifier.fillMaxWidth().height(spec.coverSize), spec.coverSize)
            Spacer(Modifier.height(16.dp))
            identity()
            NowPlayingLeadingProgress(progressAtBottom, false, centeredProgress)
            NowPlayingLeadingControls(controlsAtBottom, centeredControls)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp)
                    .testTag("nowPlayingTabletPortraitLyrics")
            ) {
                if (lyricsVisible) lyrics()
            }
            NowPlayingTrailingControls(
                controlsAtBottom, progressAtBottom, false, centeredProgress, centeredControls
            )
            Box(Modifier.width(spec.toolbarWidth)) { toolbar() }
        }
    }
}

@Composable
internal fun NowPlayingWideLayout(
    controlsAtBottom: Boolean,
    progressAtBottom: Boolean,
    topBar: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    identity: @Composable (Boolean) -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable (Boolean) -> Unit,
    lyrics: @Composable (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    phoneLandscape: Boolean,
    phoneTopActions: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.testTag("nowPlayingWideLayout")) {
        val spec = resolveNowPlayingWideLayoutSpec(maxWidth, maxHeight, phoneLandscape)
        if (phoneLandscape) {
            NowPlayingPhoneLandscape(
                spec, topBar, cover, identity, phoneTopActions,
                progress, controls, toolbar, lyrics
            )
        } else {
            NowPlayingTabletLandscape(
                spec, controlsAtBottom, progressAtBottom, topBar, cover,
                identity, progress, controls, toolbar, lyrics
            )
        }
    }
}

@Composable
private fun NowPlayingTabletLandscape(
    spec: NowPlayingWideLayoutSpec,
    controlsAtBottom: Boolean,
    progressAtBottom: Boolean,
    topBar: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    identity: @Composable (Boolean) -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable (Boolean) -> Unit,
    lyrics: @Composable (Boolean) -> Unit
) {
    val placement = resolveNowPlayingWideControlPlacement(
        controlsAtBottom, progressAtBottom, spec.compactHeight
    )
    val offsets = resolveNowPlayingWideControlOffsets(spec, placement)
    val tabletIdentity: @Composable (Boolean) -> Unit = { compact ->
        Box(Modifier.offset(y = offsets.identity)) { identity(compact) }
    }
    val tabletProgress: @Composable () -> Unit = {
        // 利用上方已有空白抬高进度区，不挤占封面和歌词的测量高度
        Box(Modifier.offset(y = offsets.progress)) { progress() }
    }
    val tabletControls: @Composable () -> Unit = {
        Box(Modifier.offset(y = offsets.controls)) { controls() }
    }
    val tabletToolbar: @Composable (Boolean) -> Unit = { compact ->
        Box(Modifier.offset(y = offsets.toolbar)) { toolbar(compact) }
    }
    if (spec.compactHeight) {
        NowPlayingCompactLandscape(
            spec, placement, topBar, cover, tabletIdentity, tabletProgress, tabletControls, tabletToolbar, lyrics
        )
    } else {
        NowPlayingExpandedLandscape(
            spec, placement, topBar, cover, tabletIdentity, tabletProgress, tabletControls, tabletToolbar, lyrics
        )
    }
}

@Composable
private fun NowPlayingExpandedLandscape(
    spec: NowPlayingWideLayoutSpec,
    placement: NowPlayingWideControlPlacement,
    topBar: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    identity: @Composable (Boolean) -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable (Boolean) -> Unit,
    lyrics: @Composable (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(spec.paneSpacing)
    ) {
        Column(
            modifier = Modifier.width(spec.playerPaneWidth).fillMaxHeight()
                .testTag("nowPlayingPlayerPane"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            topBar()
            Spacer(Modifier.height(spec.sectionSpacing))
            cover(Modifier.fillMaxWidth().weight(1f).padding(vertical = spec.coverVerticalPadding))
            Spacer(Modifier.height(spec.sectionSpacing))
            identity(false)
            Spacer(Modifier.height(spec.sectionSpacing))
            NowPlayingWideControlSection(placement.leading, progress, controls)
            // 封面多分到一些高度，同时保留 LOWER 与置底控件之间的空白差别
            Spacer(Modifier.weight(NOW_PLAYING_EXPANDED_CONTROLS_SPACER_WEIGHT))
            NowPlayingWideControlSection(placement.trailing, progress, controls)
            Spacer(Modifier.height(spec.sectionSpacing))
            toolbar(false)
        }
        NowPlayingWideLyricPane(
            compactHeight = false,
            lyrics = lyrics,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun NowPlayingWideControlSection(
    slots: List<NowPlayingWideControlSlot>,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    slots.forEach { slot -> NowPlayingWideControl(slot, progress, controls) }
}

@Composable
private fun NowPlayingWideControl(
    slot: NowPlayingWideControlSlot,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    if (slot == NowPlayingWideControlSlot.PROGRESS) progress() else controls()
}

@Composable
private fun NowPlayingPhoneLandscape(
    spec: NowPlayingWideLayoutSpec,
    backBar: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    identity: @Composable (Boolean) -> Unit,
    topActions: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable (Boolean) -> Unit,
    lyrics: @Composable (Boolean) -> Unit
) {
    // 极短横屏保留封面和操作入口的空间，内容可滚动到完整底栏
    val scrollState = rememberScrollState()
    val viewportModifier = Modifier.fillMaxSize().verticalScroll(scrollState, enabled = spec.scrollEnabled)
    Column(viewportModifier) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(spec.contentHeight),
            horizontalArrangement = Arrangement.spacedBy(spec.paneSpacing)
        ) {
            Column(
                modifier = Modifier.width(spec.playerPaneWidth).fillMaxHeight()
                    .testTag("nowPlayingPlayerPane"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                backBar()
                cover(Modifier.fillMaxWidth().weight(1f).padding(vertical = spec.coverVerticalPadding))
                Box(Modifier.fillMaxWidth().testTag("nowPlayingPhoneAuxiliaryActions")) { toolbar(true) }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("nowPlayingDetailsPane"),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("nowPlayingPhoneHeader"),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.weight(1f).padding(end = 8.dp)) { identity(true) }
                    Box(Modifier.testTag("nowPlayingTopActions")) { topActions() }
                }
                Spacer(Modifier.height(spec.sectionSpacing))
                NowPlayingWideLyricPane(
                    compactHeight = true,
                    lyrics = lyrics,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(spec.sectionSpacing))
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("nowPlayingBottomControls"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    progress()
                    controls()
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCompactLandscape(
    spec: NowPlayingWideLayoutSpec,
    placement: NowPlayingWideControlPlacement,
    topBar: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    identity: @Composable (Boolean) -> Unit,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    toolbar: @Composable (Boolean) -> Unit,
    lyrics: @Composable (Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spec.paneSpacing)
        ) {
            Column(
                modifier = Modifier.width(spec.playerPaneWidth).fillMaxHeight()
                    .testTag("nowPlayingPlayerPane"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                topBar()
                cover(Modifier.fillMaxWidth().weight(1f).padding(vertical = spec.coverVerticalPadding))
                toolbar(true)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.Start
            ) {
                identity(true)
                Spacer(Modifier.height(spec.sectionSpacing))
                NowPlayingWideControlSection(placement.leading, progress, controls)
                NowPlayingWideLyricPane(
                    compactHeight = true,
                    lyrics = lyrics,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
        if (placement.trailing.isEmpty()) return@Column
        Spacer(Modifier.height(spec.sectionSpacing))
        NowPlayingCompactControlFooter(spec, placement.trailing, progress, controls)
    }
}

@Composable
private fun NowPlayingCompactControlFooter(
    spec: NowPlayingWideLayoutSpec,
    slots: List<NowPlayingWideControlSlot>,
    progress: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("nowPlayingBottomControls"),
        horizontalArrangement = Arrangement.spacedBy(spec.paneSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slots.forEach { slot ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                NowPlayingWideControl(slot, progress, controls)
            }
        }
    }
}

@Composable
private fun NowPlayingWideLyricPane(
    compactHeight: Boolean,
    lyrics: @Composable (Boolean) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier.testTag("nowPlayingLyricPane")) {
        Box(Modifier.fillMaxSize().padding(horizontal = if (compactHeight) 8.dp else 20.dp)) {
            lyrics(compactHeight)
        }
    }
}
