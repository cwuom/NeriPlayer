package moe.ouom.neriplayer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.COMMENT_PAGE_SIZE
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.SongComment
import moe.ouom.neriplayer.core.comment.repository.CommentRepository
import moe.ouom.neriplayer.core.comment.repository.commentRepositoryFor
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 评论列表的展示状态。
 */
internal enum class CommentListStatus {
    /** 未选择歌曲 (或不支持评论) */
    IDLE,

    /** 首屏加载中 */
    LOADING,

    /** 有评论 */
    SUCCESS,

    /** 加载成功但没有评论 (暂无评论) */
    EMPTY,

    /** 加载失败 (可重试) */
    ERROR
}

/**
 * 评论弹窗的 UI 状态。
 *
 * 注意这里只保存 [CommentError] 这类语义化错误 (不是文案)，
 * 具体提示文案由 UI 层用 stringResource 映射 (§38)。
 */
internal data class CommentUiState(
    val source: CommentSource? = null,
    val status: CommentListStatus = CommentListStatus.IDLE,
    val comments: List<SongComment> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val total: Long? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: CommentError? = null,
    val loadMoreError: CommentError? = null,
    val sort: CommentSort = CommentSort.HOT,
    val pendingSort: CommentSort? = null,
    val nextCursor: String? = null,
    val likingIds: Set<String> = emptySet(),
    val likeError: CommentError? = null,
    val likeErrorCode: Int? = null
)

/**
 * 身份线索补全后需要重新解析，避免继续使用历史打包 id 的旧结果
 */
internal fun isSameCommentSource(a: CommentSource?, b: CommentSource?): Boolean {
    return a == b
}

/**
 * 按 [SongComment.id] 去重合并 (保留既有评论, 新页追加)。
 */
internal fun mergeComments(
    existing: List<SongComment>,
    incoming: List<SongComment>
): List<SongComment> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.distinctBy { it.id }
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<SongComment>(existing.size + incoming.size)
    for (comment in existing) {
        if (seen.add(comment.id)) merged += comment
    }
    for (comment in incoming) {
        if (seen.add(comment.id)) merged += comment
    }
    return merged
}

/**
 * 异常 -> 语义化评论错误。
 */
internal fun toCommentError(error: Throwable): CommentError = when (error) {
    is CommentApiException -> error.reason
    is IOException -> CommentError.NETWORK
    else -> CommentError.UNKNOWN
}

/**
 * 评论弹窗的 ViewModel。
 *
 * 只依赖评论仓库接口, 完全不接触 PlayerManager / Media3 / 播放状态 (§10/§34)。
 * 所有请求都绑定 (平台 + 资源 id)，歌曲切换后旧结果一律丢弃 (§23/§24)。
 */
internal class CommentViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    /** 当前生效的评论来源, 用于判断异步结果是否过期 */
    private var activeSource: CommentSource? = null

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var generation = 0L
    private val likeJobs = mutableMapOf<String, Job>()

    /** 仓库工厂, 单元测试可替换 (生产环境即按平台分发) */
    internal var repositoryFactory: (CommentPlatform) -> CommentRepository = ::commentRepositoryFor

    /**
     * 歌曲切换 / 打开评论弹窗时调用。
     *
     * 同一首歌重复调用不会重复请求 (§25: 打开弹窗 1 次请求, 重组不重复请求)。
     */
    fun onSourceChanged(source: CommentSource?) {
        if (isSameCommentSource(source, activeSource) &&
            _uiState.value.status != CommentListStatus.IDLE
        ) {
            return
        }

        activeSource = source
        generation++
        loadJob?.cancel()
        loadMoreJob?.cancel()
        cancelLikes()

        if (source == null) {
            _uiState.value = CommentUiState()
            return
        }

        _uiState.value = CommentUiState(
            source = source,
            status = CommentListStatus.LOADING
        )
        startLoad(source = source, page = 1, forceRefresh = false)
    }

    fun selectSort(sort: CommentSort) {
        val source = activeSource ?: return
        val current = _uiState.value
        if (sort == (current.pendingSort ?: current.sort) || sort !in CommentSort.supportedBy(source.platform)) return
        if (current.likingIds.isNotEmpty()) return
        generation++
        loadJob?.cancel()
        loadMoreJob?.cancel()
        // 新排序成功前保留旧列表、游标和排序，失败时仍可继续浏览
        _uiState.value = current.copy(
            pendingSort = sort,
            isLoadingMore = false,
            isRefreshing = false,
            error = null,
            loadMoreError = null
        )
        startLoad(source, 1, forceRefresh = true)
    }

    fun toggleLike(commentId: String) {
        val source = activeSource ?: return
        val current = _uiState.value
        if (current.status != CommentListStatus.SUCCESS || current.isRefreshing ||
            current.isLoadingMore || current.pendingSort != null) return
        if (commentId in current.likingIds) return
        val comment = current.comments.find { it.id == commentId } ?: return
        val requestGeneration = generation
        val liked = !comment.isLiked
        _uiState.update { it.copy(likingIds = it.likingIds + commentId, likeError = null, likeErrorCode = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                repositoryFactory(source.platform).setLiked(source, commentId, liked)
                if (!isActive || requestGeneration != generation) return@launch
                _uiState.update { state ->
                    state.copy(comments = state.comments.map { item ->
                        if (item.id != commentId) item else item.copy(
                            isLiked = liked,
                            likeCount = (item.likeCount + if (liked) 1L else -1L).coerceAtLeast(0L)
                        )
                    })
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (isActive && requestGeneration == generation) {
                    val code = (error as? CommentApiException)?.code
                    NPLogger.w(TAG, "Comment like failed: platform=${source.platform}, code=$code, type=${error.javaClass.simpleName}")
                    _uiState.update { it.copy(likeError = toCommentError(error), likeErrorCode = code) }
                }
            } finally {
                if (requestGeneration == generation) {
                    likeJobs.remove(commentId)
                    _uiState.update { it.copy(likingIds = it.likingIds - commentId) }
                }
            }
        }
        likeJobs[commentId] = job
        job.start()
    }

    private fun cancelLikes() {
        likeJobs.values.forEach { it.cancel() }
        likeJobs.clear()
    }

    fun dismissLikeError() {
        _uiState.update { it.copy(likeError = null, likeErrorCode = null) }
    }

    fun dismissLoadError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 首屏加载失败后重试 */
    fun retry() {
        val source = activeSource ?: return
        _uiState.update { it.copy(status = CommentListStatus.LOADING, error = null) }
        startLoad(source = source, page = 1, forceRefresh = true)
    }

    /** 下拉刷新: 重新拉取第 1 页并替换旧数据 */
    fun refresh() {
        val source = activeSource ?: return
        val current = _uiState.value
        if (current.isRefreshing || current.isLoadingMore || current.likingIds.isNotEmpty() ||
            current.pendingSort != null) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        startLoad(source = source, page = 1, forceRefresh = true)
    }

    /** 触底加载下一页 */
    fun loadMore() {
        val source = activeSource ?: return
        val current = _uiState.value
        // 同一时刻只允许一个翻页请求 (§30/§67)
        if (current.isLoadingMore || current.isRefreshing || current.likingIds.isNotEmpty() ||
            current.pendingSort != null) return
        if (!current.hasMore) return
        if (current.status != CommentListStatus.SUCCESS) return
        if (current.page <= 0) return

        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        startLoad(
            source = source,
            page = current.page + 1,
            forceRefresh = false
        )
    }

    fun onSheetHidden() {
        generation++
        loadJob?.cancel()
        loadMoreJob?.cancel()
        cancelLikes()
        // 重新打开时读取当前账号的点赞状态，匿名内容仍可复用仓库缓存
        _uiState.update {
            it.copy(
                status = CommentListStatus.IDLE,
                isRefreshing = false,
                isLoadingMore = false,
                likingIds = emptySet(),
                pendingSort = null,
                likeError = null,
                likeErrorCode = null
            )
        }
    }

    /**
     * 按页码发起单次评论请求: 首页取消旧首屏任务并整体替换列表, 后续页取消旧翻页任务并按 id 去重合并, forceRefresh 透传给仓库。
     * 结果返回时若 source 已不是当前 activeSource 则整体丢弃过期结果; 首页失败置 ERROR, 后续页失败只置 loadMoreError。
     */
    private fun startLoad(
        source: CommentSource,
        page: Int,
        forceRefresh: Boolean
    ) {
        val repository = repositoryFactory(source.platform)
        val isFirstPage = page <= 1
        val sort = _uiState.value.pendingSort ?: _uiState.value.sort
        val cursor = if (isFirstPage) null else _uiState.value.nextCursor
        val requestGeneration = generation
        if (isFirstPage) {
            loadJob?.cancel()
        } else {
            loadMoreJob?.cancel()
        }

        val job = viewModelScope.launch {
            try {
                val result = repository.loadComments(
                    source = source,
                    page = page,
                    pageSize = COMMENT_PAGE_SIZE,
                    forceRefresh = forceRefresh,
                    sort = sort,
                    cursor = cursor
                )
                // 歌曲已经切换, 丢弃过期结果 (§23/§24)
                if (!isActive || requestGeneration != generation) return@launch

                _uiState.update { current ->
                    val comments = if (isFirstPage) {
                        result.comments.distinctBy { it.id }
                    } else {
                        mergeComments(current.comments, result.comments)
                    }
                    current.copy(
                        status = if (comments.isEmpty()) {
                            CommentListStatus.EMPTY
                        } else {
                            CommentListStatus.SUCCESS
                        },
                        comments = comments,
                        sort = sort,
                        pendingSort = null,
                        page = result.page,
                        hasMore = result.hasMore,
                        nextCursor = result.nextCursor,
                        // 后续页可能拿到服务端的降级空载荷 (例如 B 站匿名请求第 2 页返回 page.count=0),
                        // 不能让它把首页拿到的总数覆盖成 0, 否则头部会从「共 N 条」掉到「共 0 条」(§32/§33)
                        total = if (isFirstPage) result.total else current.total ?: result.total,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = null,
                        loadMoreError = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 协程已被取消 (切歌 / 刷新 / 面板关闭) 时绝不发布错误:
                // 一次正常的取消不能被渲染成「加载失败」
                if (!isActive) return@launch
                if (requestGeneration != generation) return@launch
                val reason = toCommentError(error)
                // 只记录非敏感上下文 (§36)
                NPLogger.e(
                    TAG,
                    "加载评论失败: platform=${source.platform}, " +
                        "resourceId=${source.resourceId}, page=$page",
                    error
                )
                _uiState.update { current ->
                    if (isFirstPage) {
                        current.copy(
                            status = if (current.comments.isNotEmpty()) CommentListStatus.SUCCESS
                                else CommentListStatus.ERROR,
                            pendingSort = null,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = reason
                        )
                    } else {
                        current.copy(
                            isLoadingMore = false,
                            loadMoreError = reason
                        )
                    }
                }
            }
        }

        if (isFirstPage) {
            loadJob = job
        } else {
            loadMoreJob = job
        }
    }

    private companion object {
        const val TAG = "NERI-CommentVM"
    }
}
