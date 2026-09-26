package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
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

    // 身份线索变化时重新解析，普通重组不会重复请求
    LaunchedEffect(source) {
        viewModel.onSourceChanged(source)
    }

    // 面板离开组合 (关闭面板 / 歌曲切到不支持评论的音源) 时取消在途请求,
    // 界面已经不需要的结果不该继续占用请求与状态
    DisposableEffect(viewModel) {
        onDispose { viewModel.onSheetHidden() }
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
            onLoadMore = viewModel::loadMore,
            onSort = viewModel::selectSort,
            onLike = viewModel::toggleLike,
            onDismissLikeError = viewModel::dismissLikeError,
            onDismissLoadError = viewModel::dismissLoadError,
            onDraft = viewModel::updateDraft,
            onReply = viewModel::replyTo,
            onSend = viewModel::sendComment,
            onToggleReplies = viewModel::toggleReplies,
            onLoadReplies = viewModel::loadReplies
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentSheetContent(
    ui: CommentUiState,
    offlineMode: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSort: (CommentSort) -> Unit,
    onLike: (String) -> Unit,
    onDismissLikeError: () -> Unit,
    onDismissLoadError: () -> Unit = {},
    onDraft: (String) -> Unit = {},
    onReply: (CommentReplyTarget?) -> Unit = {},
    onSend: () -> Unit = {},
    onToggleReplies: (String) -> Unit = {},
    onLoadReplies: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sortLoadingDescription = stringResource(R.string.comment_sort_loading)
    val listState = rememberLazyListState()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val panelHeight = (windowHeight * 0.72f).coerceAtMost(620.dp)

    // 切换评论来源（换歌 / 自动切歌）时把列表位置重置到顶部: 数据已整体替换, 旧的滚动位置会让新来源停在中间 (§23/§24)
    LaunchedEffect(ui.source, ui.sort) {
        listState.scrollToItem(0)
    }

    // 触底自动翻页: 仅在成功态、还有下一页、且没有正在进行的请求时触发 (§29/§30)
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(reachedEnd, ui.hasMore, ui.isLoadingMore, ui.loadMoreError, ui.status, ui.likingIds, ui.pendingSort, ui.isSending) {
        if (reachedEnd &&
            ui.hasMore &&
            !ui.isLoadingMore &&
            !ui.isSending &&
            ui.pendingSort == null &&
            ui.likingIds.isEmpty() &&
            ui.loadMoreError == null &&
            ui.status == CommentListStatus.SUCCESS
        ) {
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .testTag("comment-sheet-content")
            .bottomSheetScrollGuard()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.comment_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = ui.total?.let { stringResource(R.string.comment_total_format, formatPlayCount(context, it)) }
                        ?: stringResource(R.string.comment_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CommentSortMenu(ui, onSort)
        }

        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (ui.pendingSort != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = sortLoadingDescription
                    }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                        .fillMaxSize()
                        .bottomSheetScrollGuard { !listState.canScrollBackward },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ui.comments.forEachIndexed { index, comment ->
                        val thread = ui.replyThreads[comment.id]
                        val replyEnabled = !offlineMode && !ui.isSending
                        item(key = "${comment.platform.name}:${comment.id}", contentType = "comment") {
                            CommentItem(
                                comment = comment,
                                floor = index + 1,
                                offlineMode = offlineMode,
                                isLiking = comment.id in ui.likingIds,
                                likeEnabled = !ui.isRefreshing && !ui.isLoadingMore && !ui.isSending && ui.pendingSort == null,
                                onLike = { onLike(comment.id) },
                                onReply = onReply,
                                onToggleReplies = { onToggleReplies(comment.id) },
                                repliesExpanded = thread?.expanded == true,
                                hasReplyThread = thread != null,
                                replyEnabled = replyEnabled
                            )
                        }
                        if (thread?.expanded == true) {
                            items(thread.comments, key = { "reply:${comment.id}:${it.id}" }, contentType = { "reply" }) { reply ->
                                CommentReplyItem(reply, comment.id, replyEnabled, onReply, Modifier.padding(start = 24.dp))
                            }
                            item(key = "reply-footer:${comment.id}", contentType = "reply-footer") {
                                when {
                                    thread.loading -> CommentLoadingMoreRow()
                                    thread.error != null -> CommentLoadMoreErrorRow(thread.error) { onLoadReplies(comment.id) }
                                    thread.hasMore -> HapticTextButton(
                                        onClick = { onLoadReplies(comment.id) }, enabled = !offlineMode,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(stringResource(R.string.comment_more_replies)) }
                                    thread.comments.isEmpty() -> Text(
                                        stringResource(R.string.comment_empty_replies), Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
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
            val failureText = when {
                ui.likeError != null -> {
                    val message = stringResource(
                        when {
                            ui.likeError == CommentError.PERMISSION -> R.string.comment_like_login_required
                            ui.source?.platform == moe.ouom.neriplayer.core.comment.model.CommentPlatform.NETEASE &&
                                ui.likeErrorCode == 250 -> R.string.comment_like_verification
                            else -> R.string.comment_like_failed
                        }
                    )
                    ui.likeErrorCode?.let { stringResource(R.string.comment_error_code_format, message, it) } ?: message
                }
                ui.error != null && ui.comments.isNotEmpty() -> stringResource(R.string.comment_reload_failed)
                else -> null
            }
            if (failureText != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(failureText, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        HapticIconButton(onClick = if (ui.likeError != null) onDismissLikeError else onDismissLoadError) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.comment_dismiss_error))
                        }
                    }
                }
            }
        }

        CommentComposer(ui, offlineMode, onDraft, onReply, onSend)
    }
}

@Composable
private fun CommentSortMenu(ui: CommentUiState, onSort: (CommentSort) -> Unit) {
    var expanded by remember(ui.source) { mutableStateOf(false) }
    val description = stringResource(R.string.comment_sort)
    Box {
        HapticTextButton(
            onClick = { expanded = true },
            enabled = ui.source != null && ui.likingIds.isEmpty() && !ui.isSending,
            modifier = Modifier.semantics { contentDescription = description },
            colors = ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            if (ui.pendingSort != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(commentSortTextRes(ui.pendingSort ?: ui.sort)))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ui.source?.platform?.let { platform ->
                CommentSort.supportedBy(platform).forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(commentSortTextRes(sort))) },
                        trailingIcon = {
                            if (sort == ui.sort) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            expanded = false
                            onSort(sort)
                        }
                    )
                }
            }
        }
    }
}

private fun commentSortTextRes(sort: CommentSort): Int = when (sort) {
    CommentSort.HOT -> R.string.comment_sort_hot
    CommentSort.NEWEST -> R.string.comment_sort_newest
    CommentSort.RECOMMENDED -> R.string.comment_sort_recommended
}
