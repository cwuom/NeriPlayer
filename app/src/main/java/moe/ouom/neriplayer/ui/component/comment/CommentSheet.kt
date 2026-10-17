package moe.ouom.neriplayer.ui.component.comment

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.material3.SheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.HalfExpandableSheetPanel
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
    val scope = rememberCoroutineScope()

    // 关闭是三态手势的最后一档: 先播放下滑动画, 收起后再通知外层 (与 M3 自己处理遮罩点击的方式一致)
    val hideSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }

    // 身份线索变化时重新解析，普通重组不会重复请求
    LaunchedEffect(source) {
        viewModel.onSourceChanged(source)
    }

    // 面板离开组合 (关闭面板 / 歌曲切到不支持评论的音源) 时取消在途请求,
    // 界面已经不需要的结果不该继续占用请求与状态
    DisposableEffect(viewModel) {
        onDispose { viewModel.onSheetHidden() }
    }

    CommentSheetSurface(
        onDismissRequest = hideSheet,
        sheetState = sheetState
    ) {
        CommentSheetContent(
            ui = ui,
            offlineMode = offlineMode,
            onDismiss = hideSheet,
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
internal fun CommentSheetSurface(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("comment-sheet-surface"),
        sheetState = sheetState,
        sheetMaxWidth = if (landscape) (windowWidth - 32.dp).coerceIn(0.dp, 960.dp)
            else BottomSheetDefaults.SheetMaxWidth,
        // 竖屏把手仍由内容负责，横屏省下把手高度并保留系统遮罩关闭手势
        dragHandle = null,
        contentWindowInsets = {
            if (landscape) WindowInsets.safeDrawing
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
        },
        content = content
    )
}

/**
 * 评论面板的完整内容: 可拖拽的勾柄与标题条 (总数 / 排序入口 / 全屏按钮)、排序加载进度条、
 * 评论列表 (含展开的楼中楼与底部状态行)、底部错误提示条、底部输入框。
 *
 * 竖屏面板高度由内部手势维护, 在半屏 (72% / 上限 620dp) 与全屏之间连续变化: 横条上滑进全屏、
 * 下滑回半屏、比半屏再低 96dp 触发 [onDismiss]; 列表触底且没有任何在途请求时自动调 [onLoadMore]。
 * 横屏直接铺满可用高度, 使用单行标题和紧凑输入框给列表保留空间。
 * 所有数据与状态都来自 [ui], 本组件不直接发起请求。
 *
 * @param ui 面板的完整 UI 状态
 * @param offlineMode 离线模式: 禁用回复 / 发送 / 点赞
 * @param onRefresh 下拉刷新第 1 页
 * @param onRetry 首屏失败后重试
 * @param onLoadMore 触底加载下一页
 * @param onSort 切换排序
 * @param onLike 点赞 / 取消点赞, 参数为评论 id (一级评论与楼中楼共用)
 * @param onDismissLikeError 关闭点赞失败提示
 * @param onDismissLoadError 关闭列表加载失败提示
 * @param onDraft 输入框内容变化
 * @param onReply 选择 / 取消回复目标
 * @param onSend 发送评论
 * @param onToggleReplies 展开 / 收起某条评论的楼中楼
 * @param onLoadReplies 加载某条评论楼中楼的下一批回复
 * @param onDismiss 关闭面板
 */
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
    onLoadReplies: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sortLoadingDescription = stringResource(CoreCommonR.string.comment_sort_loading)
    val listState = rememberLazyListState()
    var replyFocusRequest by remember(ui.source) { mutableIntStateOf(0) }
    val requestReply: (CommentReplyTarget?) -> Unit = { target ->
        onReply(target)
        if (target != null) replyFocusRequest++
    }

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
    LaunchedEffect(reachedEnd, ui.hasMore, ui.isLoadingMore, ui.isRefreshing, ui.isCheckingCache, ui.loadMoreError, ui.status, ui.likingIds, ui.pendingSort, ui.isSending) {
        if (reachedEnd &&
            ui.hasMore &&
            !ui.isLoadingMore &&
            !ui.isRefreshing &&
            !ui.isCheckingCache &&
            !ui.isSending &&
            ui.pendingSort == null &&
            ui.likingIds.isEmpty() &&
            ui.loadMoreError == null &&
            ui.status == CommentListStatus.SUCCESS
        ) {
            onLoadMore()
        }
    }

    // 半屏 / 全屏 / 关闭三档由公共组件 HalfExpandableSheetPanel 提供，
    // 与歌曲百科面板共用同一套拖拽与吸附实现。
    HalfExpandableSheetPanel(
        onDismiss = onDismiss,
        panelTestTag = "comment-sheet-content",
        headerTestTag = "comment-header",
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (landscape) 16.dp else 20.dp,
                        vertical = if (landscape) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalText = ui.total?.let {
                    stringResource(CoreCommonR.string.comment_total_format, formatPlayCount(context, it))
                } ?: stringResource(CoreCommonR.string.comment_loading)
                if (landscape) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(CoreCommonR.string.comment_title),
                            style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(totalText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(CoreCommonR.string.comment_title),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(totalText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                CommentSortMenu(ui, onSort)
                if (landscape) HapticIconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(CoreCommonR.string.action_close))
                } else HapticIconButton(onClick = toggleFullScreen) {
                    Icon(
                        imageVector = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(
                            if (isFullScreen) CoreCommonR.string.comment_action_collapse else CoreCommonR.string.comment_action_expand
                        )
                    )
                }
            }
        }
    ) {
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
                .testTag("comment-list-viewport")
            // 这里不再挂「列表下拉降档」手势: 全屏 / 半屏档位下列表下拉都要能刷新评论 (真机反馈 #1),
            // 档位切换交给横条拖拽与标题栏的全屏按钮
        ) {
            when (ui.status) {
                CommentListStatus.IDLE -> Unit
                CommentListStatus.LOADING -> CommentLoadingBlock()

                CommentListStatus.EMPTY -> CommentEmptyBlock()

                CommentListStatus.ERROR -> CommentErrorBlock(
                    error = ui.error ?: CommentError.UNKNOWN,
                    onRetry = onRetry
                )

                CommentListStatus.SUCCESS -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("comment-list")
                        // 半屏 / 全屏都放行「列表滑到顶继续下拉」→ PullToRefreshBox 刷新 (真机反馈 #1)。
                        // 显式传 lambda 而不是默认值: 启用玻璃过滚动时默认的 null 会让 guard 整个不安装,
                        // 下拉会继续上传给 sheet → 直接关闭面板; 传 lambda 保证拦截一定装上
                        .bottomSheetScrollGuard { true },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ui.comments.forEachIndexed { index, comment ->
                        val thread = ui.replyThreads[comment.id]
                        val replyEnabled = !offlineMode && !ui.isSending
                        // 一级评论与楼中楼共用同一个点赞可用性判断 (真机反馈 #3)
                        val likeEnabled = !ui.isRefreshing && !ui.isCheckingCache && !ui.isLoadingMore && !ui.isSending && ui.pendingSort == null
                        item(key = "${comment.platform.name}:${comment.id}", contentType = "comment") {
                            CommentItem(
                                comment = comment,
                                floor = index + 1,
                                offlineMode = offlineMode,
                                isLiking = comment.id in ui.likingIds,
                                likeEnabled = likeEnabled,
                                onLike = { onLike(comment.id) },
                                onReply = requestReply,
                                onToggleReplies = { onToggleReplies(comment.id) },
                                repliesExpanded = thread?.expanded == true,
                                hasReplyThread = thread != null,
                                replyEnabled = replyEnabled,
                                likingIds = ui.likingIds,
                                onLikeReply = onLike
                            )
                        }
                        if (thread?.expanded == true) {
                            items(thread.comments, key = { "reply:${comment.id}:${it.id}" }, contentType = { "reply" }) { reply ->
                                CommentReplyItem(
                                    comment = reply,
                                    rootId = comment.id,
                                    replyEnabled = replyEnabled,
                                    onReply = requestReply,
                                    modifier = Modifier.padding(start = 24.dp),
                                    offlineMode = offlineMode,
                                    isLiking = reply.id in ui.likingIds,
                                    likeEnabled = likeEnabled,
                                    onLike = { onLike(reply.id) }
                                )
                            }
                            item(key = "reply-footer:${comment.id}", contentType = "reply-footer") {
                                when {
                                    thread.loading -> CommentLoadingMoreRow()
                                    thread.error != null -> CommentLoadMoreErrorRow(thread.error) { onLoadReplies(comment.id) }
                                    thread.hasMore -> HapticTextButton(
                                        onClick = { onLoadReplies(comment.id) }, enabled = !offlineMode,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(stringResource(CoreCommonR.string.comment_more_replies)) }
                                    thread.comments.isEmpty() -> Text(
                                        stringResource(CoreCommonR.string.comment_empty_replies), Modifier.padding(16.dp),
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
                                    text = stringResource(CoreCommonR.string.comment_no_more),
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
                            ui.likeError == CommentError.PERMISSION -> CoreCommonR.string.comment_like_login_required
                            ui.source?.platform == moe.ouom.neriplayer.data.model.comments.CommentPlatform.NETEASE &&
                                ui.likeErrorCode == 250 -> CoreCommonR.string.comment_like_verification
                            else -> CoreCommonR.string.comment_like_failed
                        }
                    )
                    ui.likeErrorCode?.let { stringResource(CoreCommonR.string.comment_error_code_format, message, it) } ?: message
                }
                ui.error != null && ui.comments.isNotEmpty() -> stringResource(CoreCommonR.string.comment_reload_failed)
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
                            Icon(Icons.Outlined.Close, stringResource(CoreCommonR.string.comment_dismiss_error))
                        }
                    }
                }
            }
        }

        CommentComposer(ui, offlineMode, onDraft, requestReply, onSend, replyFocusRequest,
            compact = landscape)
    }
}

/**
 * 标题栏右侧的排序入口: 按钮显示当前排序 (有正在切换的目标排序时优先显示目标), 切换期间图标换成进度圈。
 *
 * 下拉列表只列出当前平台 [CommentSort.supportedBy] 支持的排序, 当前生效项带勾;
 * 来源为空、有评论正在点赞或正在发送时按钮不可点。
 *
 * @param ui 面板状态, 提供来源平台、当前 / 目标排序与忙碌标志
 * @param onSort 选中某个排序时回调
 */
@Composable
private fun CommentSortMenu(ui: CommentUiState, onSort: (CommentSort) -> Unit) {
    var expanded by remember(ui.source) { mutableStateOf(false) }
    val description = stringResource(CoreCommonR.string.comment_sort)
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

/** 评论排序 [sort] 对应的文案资源 id (排序按钮与下拉菜单共用)。 */
private fun commentSortTextRes(sort: CommentSort): Int = when (sort) {
    CommentSort.HOT -> CoreCommonR.string.comment_sort_hot
    CommentSort.NEWEST -> CoreCommonR.string.comment_sort_newest
    CommentSort.RECOMMENDED -> CoreCommonR.string.comment_sort_recommended
}
