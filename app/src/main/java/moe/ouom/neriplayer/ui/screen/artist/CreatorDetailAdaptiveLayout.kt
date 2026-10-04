package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun useCreatorDetailSplitLayout(tabletDevice: Boolean, availableWidthDp: Float): Boolean =
    tabletDevice && availableWidthDp >= 600f

@Composable
internal fun CreatorDetailAdaptiveLayout(
    tabletDevice: Boolean,
    miniPlayerHeight: Dp,
    modifier: Modifier = Modifier,
    profile: @Composable () -> Unit,
    content: @Composable (Boolean) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        ),
        contentAlignment = Alignment.TopCenter
    ) {
        if (useCreatorDetailSplitLayout(tabletDevice, maxWidth.value)) {
            val contentWidth = maxWidth.coerceAtMost(1280.dp)
            val gutter = if (contentWidth >= 840.dp) 24.dp else 20.dp
            val profileWidth = ((contentWidth - gutter * 3) * 0.3f).coerceIn(180.dp, 320.dp)
            Row(
                modifier = Modifier.widthIn(max = 1280.dp).fillMaxSize()
                    .padding(start = gutter, end = gutter, top = 8.dp, bottom = 8.dp + miniPlayerHeight)
                    .testTag("creatorDetailSplitLayout"),
                horizontalArrangement = Arrangement.spacedBy(gutter)
            ) {
                Column(
                    modifier = Modifier.width(profileWidth).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).testTag("creatorDetailProfile")
                ) {
                    profile()
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("creatorDetailWorks"),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    content(true)
                }
            }
        } else {
            content(false)
        }
    }
}
