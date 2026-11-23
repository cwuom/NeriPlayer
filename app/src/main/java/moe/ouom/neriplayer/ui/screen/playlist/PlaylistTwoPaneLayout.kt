package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/** 头部放在平板侧栏里时按手机尺寸排版, 不走宽屏横排 */
internal val LocalPlaylistHeroInPane = staticCompositionLocalOf { false }

/** 只验证单列头部行为的场景可以关掉两栏 */
internal val LocalPlaylistTwoPaneAllowed = staticCompositionLocalOf { true }

internal val PlaylistPaneWidth = 380.dp
internal const val PlaylistPaneCardTag = "playlist-pane-card"

/** 侧栏卡片内容宽度: 侧栏减去两侧外边距和头部左右内边距 */
internal val PlaylistPaneCoverSize = 316.dp
internal const val PlaylistPaneCoverMaxHeightFraction = 0.4f
private const val PlaylistTwoPaneMinWidthDp = 840

/** 横屏平板才拆成两栏; 竖屏平板宽度够但高度更富余, 仍用单列让列表多露几行 */
@Composable
internal fun rememberPlaylistTwoPaneLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return LocalPlaylistTwoPaneAllowed.current &&
        isPlaylistTwoPaneWindow(configuration.screenWidthDp, configuration.screenHeightDp)
}

internal fun isPlaylistTwoPaneWindow(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= PlaylistTwoPaneMinWidthDp && widthDp > heightDp

/**
 * 横屏平板: 左侧固定一张卡片放封面, 标题和播放操作, 右侧整列只滚歌曲;
 * 其余尺寸直接显示 [list], 头部和操作区仍作为列表首项
 */
@Composable
internal fun PlaylistTwoPaneBody(
    twoPane: Boolean,
    modifier: Modifier = Modifier,
    pane: @Composable ColumnScope.() -> Unit,
    list: @Composable BoxScope.() -> Unit
) {
    if (!twoPane) {
        Box(modifier) { list() }
        return
    }
    val shape = if (NeriDesign.isClean) NeriDesign.tokens.groupShape else neriRoundedShape(28.dp)
    Row(modifier) {
        Column(
            modifier = Modifier
                .width(PlaylistPaneWidth)
                .fillMaxHeight()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 16.dp + LocalMiniPlayerHeight.current)
                // 横屏高度有限, 大字号时侧栏自己滚动, 不挤压封面和按钮
                .verticalScroll(rememberScrollState())
        ) {
            // 卡片按内容高度裁剪, 底部圆角落在操作区下沿而不是侧栏底部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PlaylistPaneCardTag)
                    .clip(shape)
            ) {
                CompositionLocalProvider(LocalPlaylistHeroInPane provides true) {
                    pane()
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            list()
        }
    }
}

/** 两栏模式下列表首项的占位, 让第一首歌和侧栏卡片顶部对齐 */
@Composable
internal fun PlaylistTwoPaneListTopGap() {
    Spacer(Modifier.height(8.dp))
}
