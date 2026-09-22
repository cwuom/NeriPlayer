package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel
import moe.ouom.neriplayer.util.format.formatPlayCount

/**
 * 评论弹窗。
 *
 * 复用项目既有的 [DensityScaledModalBottomSheet] (与音量弹窗 / 播放队列弹窗同一个包装),
 * 主题、字体、圆角、颜色全部来自 MaterialTheme, 不新增任何独立的视觉体系 (§5/§6/§37)。
 *
 * 网络请求由 [CommentViewModel] 负责, UI 不直接发起任何 HTTP 调用 (§9/§11)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentSheet(
    source: CommentSource?,
    offlineMode: Boolean,
    onDismissRequest: () -> Unit
) {
    val viewModel: CommentViewModel = viewModel()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 只在「平台 + 资源 id」真正变化时请求一次; 重组不会重复请求 (§25)
    LaunchedEffect(source?.platform, source?.resourceId) {
        viewModel.onSourceChanged(source)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        CommentSheetContent(
            ui = ui,
            offlineMode = offlineMode,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            onLoadMore = viewModel::loadMore
        )
    }
}

/**
 * 弹层内部内容: 头部展示总数与刷新按钮, 再按 [ui] 的 status 分支渲染加载 / 空 / 错误 / 列表。
 * 列表项 key 取 "platform:id", 触底且成功态、还有下一页、无进行中请求时自动触发 [onLoadMore],
 * 页脚按需展示加载更多、翻页失败重试与「没有更多」。
 */
@Composable
private fun CommentSheetContent(
    ui: CommentUiState,
    offlineMode: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 触底自动翻页: 仅在成功态、还有下一页、且没有正在进行的请求时触发 (§29/§30)
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(reachedEnd, ui.hasMore, ui.isLoadingMore, ui.loadMoreError, ui.status) {
        if (reachedEnd &&
            ui.hasMore &&
            !ui.isLoadingMore &&
            ui.loadMoreError == null &&
            ui.status == CommentListStatus.SUCCESS
        ) {
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetScrollGuard()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.comment_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            ui.total?.takeIf { it > 0L }?.let { total ->
                Text(
                    text = stringResource(
                        R.string.comment_total_format,
                        formatPlayCount(context, total)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
            HapticIconButton(
                onClick = onRefresh,
                enabled = !ui.isRefreshing && ui.status != CommentListStatus.LOADING
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.comment_refresh)
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            when (ui.status) {
                CommentListStatus.IDLE, CommentListStatus.LOADING -> CommentLoadingBlock()

                CommentListStatus.EMPTY -> CommentEmptyBlock()

                CommentListStatus.ERROR -> CommentErrorBlock(
                    error = ui.error ?: CommentError.UNKNOWN,
                    onRetry = onRetry
                )

                CommentListStatus.SUCCESS -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bottomSheetScrollGuard { !listState.canScrollBackward }
                ) {
                    items(
                        items = ui.comments,
                        key = { comment -> "${comment.platform.name}:${comment.id}" },
                        contentType = { "comment" }
                    ) { comment ->
                        CommentItem(comment = comment, offlineMode = offlineMode)
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }

                    if (ui.isLoadingMore) {
                        item(key = "comment_loading_more", contentType = "footer") {
                            CommentLoadingMoreRow()
                        }
                    }

                    ui.loadMoreError?.let { error ->
                        item(key = "comment_load_more_error", contentType = "footer") {
                            CommentLoadMoreErrorRow(error = error, onRetry = onLoadMore)
                        }
                    }

                    if (!ui.hasMore) {
                        item(key = "comment_no_more", contentType = "footer") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.comment_no_more),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
