package moe.ouom.neriplayer.ui.component.comment

import android.content.res.Configuration
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlin.math.absoluteValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
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
    val tablet = configuration.smallestScreenWidthDp >= 600
    val sortLoadingDescription = stringResource(CoreCommonR.string.comment_sort_loading)
    val listState = rememberLazyListState()
    var replyFocusRequest by remember(ui.source) { mutableIntStateOf(0) }
    val requestReply: (CommentReplyTarget?) -> Unit = { target ->
        onReply(target)
        if (target != null) replyFocusRequest++
    }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // 面板三态档位: 半屏 (默认 72% / 620dp) <-> 全屏 <-> 关闭 (真机反馈 #1)。
    // 高度是连续值: 拖横条时直接跟手改值, 松手 / 点标题栏按钮时再吸附到某一档。
    // 档位切换只由「横条拖拽」与「全屏-退出全屏按钮」负责; 评论列表的下拉手势一律让给刷新,
    // 否则全屏档位下列表下拉会被降档吃掉, 刷新永远触发不了 (真机反馈 #1)。
    // 关闭档由 [onDismiss] (sheet 自身的收起动画) 承担, 这里只维护「半屏 / 全屏」两档之间的连续高度。
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    // 平板评论面板与状态栏之间留一点空间，短手机横屏仍优先保留列表高度
    val availableHeight = if (tablet) {
        (windowHeight - with(density) {
            (safeDrawing.getTop(this) + safeDrawing.getBottom(this)).toDp()
        } - 24.dp).coerceAtLeast(0.dp)
    } else windowHeight
    // 横屏直接使用可用高度，避免标题和输入框挤掉评论列表
    val halfHeightPx = with(density) {
        (if (landscape) availableHeight else (availableHeight * 0.72f).coerceAtMost(620.dp)).toPx()
    }
    val fullHeightPx = with(density) { availableHeight.toPx() }
    // 半屏档位再下拉这么多才进入「关闭」档: 48dp 太容易误关, 按真机反馈加大到 96dp
    val dismissDistancePx = with(density) { 96.dp.toPx() }
    // 不能用 Animatable: awaitEachGesture 的 block 是受限挂起作用域, 里面调不了 snapTo / animateTo。
    // 所以跟手直接写状态值, 吸附用 animate() 在普通协程里逐帧回写同一个状态。
    var panelHeightPx by remember { mutableFloatStateOf(halfHeightPx) }
    var panelAnimJob by remember { mutableStateOf<Job?>(null) }
    // 记录「吸附目标档位」而不是只看瞬间像素高度: 窗口尺寸变化 (旋转 / 分屏) 后,
    // 旧的全屏像素高度可能低于新中线, 用 isFullScreen 判断会把全屏错误降成半屏 (CodeRabbit #476)
    var targetFull by remember { mutableStateOf(false) }
    // 档位判定必须用 derivedStateOf: 面板高度每帧都在变, 直接读普通变量在组合期之外拿不到新值
    val isFullScreen by remember(halfHeightPx, fullHeightPx) {
        derivedStateOf { panelHeightPx >= (halfHeightPx + fullHeightPx) / 2f }
    }
    val scope = rememberCoroutineScope()
    // 档位吸附复用项目既有规格 (tween + LinearOutSlowInEasing, 参考 MiuixSettingsControls.kt / SettingsFloatingLyricsPreview.kt),
    // 不新增独立动画体系; 上滑进全屏与下滑回半屏走同一条吸附动画, 保证两个方向的动作一致
    val panelSnapAnimation = tween<Float>(durationMillis = 800, easing = LinearOutSlowInEasing)
    val settlePanel: (Float) -> Unit = { target ->
        targetFull = target == fullHeightPx
        panelAnimJob?.cancel()
        panelAnimJob = scope.launch {
            animate(panelHeightPx, target, animationSpec = panelSnapAnimation) { value, _ ->
                panelHeightPx = value
            }
        }
    }
    // 屏幕尺寸变化 (旋转 / 分屏) 时把面板重新落到「用户选定档位」对应的新高度
    LaunchedEffect(halfHeightPx, fullHeightPx) {
        panelAnimJob?.cancel()
        panelHeightPx = if (targetFull) fullHeightPx else halfHeightPx
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { panelHeightPx.toDp() })
            .testTag("comment-sheet-content")
            .bottomSheetScrollGuard { false }
    ) {
        // 勾柄 + 标题条整条都支持上下拖拽: 拖动时面板高度跟手变化, 松手吸附到 全屏 / 半屏 / 关闭 三档之一。
        // 手势在 Initial pass 抢下纵向位移: 外层 ModalBottomSheet 的拖拽走 Main pass,
        // 若留到 Main pass 竞争会先被 sheet 消费, 表现为「按住上方拖不动」(真机反馈 #7)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("comment-header")
                .pointerInput(fullHeightPx, halfHeightPx, landscape) {
                    if (landscape) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startHeight = panelHeightPx
                        var totalDy = 0f
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            // 只接管纵向拖动, 横向位移留给其它手势
                            if (delta.y == 0f || delta.y.absoluteValue < delta.x.absoluteValue) continue
                            if (!dragging) {
                                // 起手拖动时先打断上一次吸附动画, 否则动画会继续覆盖手指位置
                                dragging = true
                                panelAnimJob?.cancel()
                            }
                            totalDy += delta.y
                            change.consume()
                            // 跟手: 往上拖面板变高, 往下拖面板变矮 (手指位移 1:1 映射到高度)
                            panelHeightPx = (startHeight - totalDy).coerceIn(0f, fullHeightPx)
                        }
                        if (!dragging) return@awaitEachGesture
                        // 松手吸附: 比半屏再低 96dp 就是「关闭」档; 越过半屏与全屏的中线就是「全屏」档; 其余回半屏。
                        // 一次手势只结算一次, 所以不会出现「下拉横条直接从全屏关掉」(真机反馈: 下滑应有与上滑一致的动画)
                        when {
                            panelHeightPx <= halfHeightPx - dismissDistancePx -> onDismiss()
                            panelHeightPx >= (halfHeightPx + fullHeightPx) / 2f -> settlePanel(fullHeightPx)
                            else -> settlePanel(halfHeightPx)
                        }
                    }
                }
        ) {
            // 勾柄: 外观与原来一致 (BottomSheetDefaults.DragHandle), 点击仍然关闭面板
            if (!landscape) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                BottomSheetDefaults.DragHandle()
            }
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
                } else HapticIconButton(onClick = {
                    settlePanel(if (isFullScreen) halfHeightPx else fullHeightPx)
                }) {
                    Icon(
                        imageVector = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(
                            if (isFullScreen) CoreCommonR.string.comment_action_collapse else CoreCommonR.string.comment_action_expand
                        )
                    )
                }
            }
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
