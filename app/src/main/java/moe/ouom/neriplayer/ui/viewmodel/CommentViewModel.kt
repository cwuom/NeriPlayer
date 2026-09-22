package moe.ouom.neriplayer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.COMMENT_PAGE_SIZE
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
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
    val loadMoreError: CommentError? = null
)

/**
 * 判断两个评论来源是否指向同一份评论 (平台 + 资源 id)。
 *
 * 刻意只比较平台与资源 id, 忽略 secondaryId (bvid), 避免同一首歌因为
 * album 标记差异而被判定为「换了歌」从而重复请求。
 */
internal fun isSameCommentSource(a: CommentSource?, b: CommentSource?): Boolean {
    if (a == null || b == null) return a == null && b == null
    return a.platform == b.platform && a.resourceId == b.resourceId
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
        loadJob?.cancel()
        loadMoreJob?.cancel()

        if (source == null) {
            _uiState.value = CommentUiState()
            return
        }

        _uiState.value = CommentUiState(
            source = source,
            status = CommentListStatus.LOADING
        )
        startLoad(source = source, page = 1, forceRefresh = false, isRefresh = false)
    }

    /** 首屏加载失败后重试 */
    fun retry() {
        val source = activeSource ?: return
        _uiState.update { it.copy(status = CommentListStatus.LOADING, error = null) }
        startLoad(source = source, page = 1, forceRefresh = true, isRefresh = false)
    }

    /** 下拉刷新: 重新拉取第 1 页并替换旧数据 */
    fun refresh() {
        val source = activeSource ?: return
        val current = _uiState.value
        if (current.isRefreshing || current.isLoadingMore) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        startLoad(source = source, page = 1, forceRefresh = true, isRefresh = true)
    }

    /** 触底加载下一页 */
    fun loadMore() {
        val source = activeSource ?: return
        val current = _uiState.value
        // 同一时刻只允许一个翻页请求 (§30/§67)
        if (current.isLoadingMore || current.isRefreshing) return
        if (!current.hasMore) return
        if (current.status != CommentListStatus.SUCCESS) return
        if (current.page <= 0) return

        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        startLoad(
            source = source,
            page = current.page + 1,
            forceRefresh = false,
            isRefresh = false
        )
    }

    private fun startLoad(
        source: CommentSource,
        page: Int,
        forceRefresh: Boolean,
        isRefresh: Boolean
    ) {
        val repository = repositoryFactory(source.platform)
        val isFirstPage = page <= 1
        if (isFirstPage) {
            loadJob?.cancel()
        } else {
            loadMoreJob?.cancel()
        }

        val job = viewModelScope.launch {
            try {
                val result = repository.loadComments(
                    resourceId = source.resourceId,
                    secondaryId = source.secondaryId,
                    page = page,
                    pageSize = COMMENT_PAGE_SIZE,
                    forceRefresh = forceRefresh
                )
                // 歌曲已经切换, 丢弃过期结果 (§23/§24)
                if (!isSameCommentSource(source, activeSource)) return@launch

                _uiState.update { current ->
                    val comments = if (isFirstPage) {
                        result.comments
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
                        page = result.page,
                        hasMore = result.hasMore,
                        total = result.total,
                        isRefreshing = false,
                        isLoadingMore = false,
                        error = null,
                        loadMoreError = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isSameCommentSource(source, activeSource)) return@launch
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
                            status = CommentListStatus.ERROR,
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
