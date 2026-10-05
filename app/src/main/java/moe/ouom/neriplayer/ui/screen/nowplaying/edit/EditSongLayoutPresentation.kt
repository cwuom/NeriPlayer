package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class EditSongLayoutItem { HEADER, COVER_URL, COVER, FIELDS, ACTIONS }

internal data class EditSongLayoutChrome(
    val spacing: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val heightFraction: Float
)

internal data class EditSongLayoutPresentation(
    val compact: Boolean,
    val chrome: EditSongLayoutChrome,
    val fixedHeader: List<EditSongLayoutItem>,
    val scrollingItems: List<EditSongLayoutItem>,
    val fixedActions: List<EditSongLayoutItem>
) {
    fun allowsParentDrag(scrollPosition: Int): Boolean = !compact && scrollPosition == 0
}

private val compactEditSongChrome = EditSongLayoutChrome(8.dp, 20.dp, 8.dp, 1f)
private val regularEditSongChrome = EditSongLayoutChrome(12.dp, 24.dp, 16.dp, 0.9f)

internal fun resolveEditSongLayoutChrome(compact: Boolean): EditSongLayoutChrome =
    if (compact) compactEditSongChrome else regularEditSongChrome

private val regularEditSongLayout = EditSongLayoutPresentation(
    compact = false,
    chrome = regularEditSongChrome,
    fixedHeader = listOf(EditSongLayoutItem.HEADER),
    scrollingItems = listOf(EditSongLayoutItem.COVER_URL, EditSongLayoutItem.COVER, EditSongLayoutItem.FIELDS),
    fixedActions = listOf(EditSongLayoutItem.ACTIONS)
)

private val compactFixedEditSongLayout = EditSongLayoutPresentation(
    compact = true,
    chrome = compactEditSongChrome,
    fixedHeader = listOf(EditSongLayoutItem.HEADER),
    scrollingItems = listOf(EditSongLayoutItem.COVER_URL, EditSongLayoutItem.FIELDS),
    fixedActions = listOf(EditSongLayoutItem.ACTIONS)
)

private val compactScrollingHeaderEditSongLayout = EditSongLayoutPresentation(
    compact = true,
    chrome = compactEditSongChrome,
    fixedHeader = emptyList(),
    scrollingItems = listOf(EditSongLayoutItem.HEADER, EditSongLayoutItem.COVER_URL, EditSongLayoutItem.FIELDS),
    fixedActions = listOf(EditSongLayoutItem.ACTIONS)
)

private val compactScrollingChromeEditSongLayout = EditSongLayoutPresentation(
    compact = true,
    chrome = compactEditSongChrome,
    fixedHeader = emptyList(),
    scrollingItems = listOf(
        EditSongLayoutItem.HEADER, EditSongLayoutItem.COVER_URL, EditSongLayoutItem.FIELDS, EditSongLayoutItem.ACTIONS
    ),
    fixedActions = emptyList()
)

internal fun resolveEditSongLayoutPresentation(compact: Boolean, availableHeight: Dp): EditSongLayoutPresentation = when {
    !compact -> regularEditSongLayout
    // 先选完整的短视窗布局，避免标题和操作的位置分别计算后失去一致性
    availableHeight < 160.dp -> compactScrollingChromeEditSongLayout
    availableHeight < 240.dp -> compactScrollingHeaderEditSongLayout
    else -> compactFixedEditSongLayout
}

internal fun resolveCompactEditSongCoverSize(bodyHeight: Dp): Dp = bodyHeight.coerceIn(0.dp, 96.dp)
