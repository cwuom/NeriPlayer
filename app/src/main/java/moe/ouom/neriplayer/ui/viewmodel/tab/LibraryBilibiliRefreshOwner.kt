package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal sealed interface LibraryBilibiliLoadResult {
    data class Loaded(val playlists: List<BiliPlaylist>) : LibraryBilibiliLoadResult
    data class Failed(val message: String?) : LibraryBilibiliLoadResult
    data object Unchanged : LibraryBilibiliLoadResult
}

internal class LibraryBilibiliRefreshOwner(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LibraryUiState>,
    private val currentCookies: () -> Map<String, String>,
    private val load: suspend (Map<String, String>) -> LibraryBilibiliLoadResult
) {
    private var refreshJob: Job? = null
    private var refreshCookies: Map<String, String>? = null

    fun refresh() {
        if (!scope.isActive) return
        val cookies = currentCookies().toMap()
        // 补齐 DedeUserID 也会触发新加载, 不能只用 SESSDATA 合并
        if (refreshJob?.isActive == true && refreshCookies == cookies) return

        cancelRefresh()
        val nextJob = scope.launch(start = CoroutineStart.LAZY) { loadAndPublish(cookies) }
        refreshCookies = cookies
        refreshJob = nextJob
        state.update { it.copy(biliRefreshing = true) }
        nextJob.start()
    }

    fun clear() {
        cancelRefresh()
        state.update {
            it.copy(biliPlaylists = emptyList(), biliError = null, biliRefreshing = false)
        }
    }

    private suspend fun loadAndPublish(cookies: Map<String, String>) {
        try {
            val result = load(cookies)
            currentCoroutineContext().ensureActive()
            publish(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state.update { it.copy(biliError = error.message) }
        } finally {
            if (refreshJob === currentCoroutineContext()[Job]) {
                refreshJob = null
                refreshCookies = null
                state.update { it.copy(biliRefreshing = false) }
            }
        }
    }

    private fun publish(result: LibraryBilibiliLoadResult) {
        when (result) {
            is LibraryBilibiliLoadResult.Loaded -> {
                state.update { it.copy(biliPlaylists = result.playlists, biliError = null) }
            }
            is LibraryBilibiliLoadResult.Failed -> {
                state.update { it.copy(biliError = result.message) }
            }
            LibraryBilibiliLoadResult.Unchanged -> Unit
        }
    }

    private fun cancelRefresh() {
        val previousJob = refreshJob
        // 先解除所有权, 避免旧任务的 finally 结束替代刷新
        refreshJob = null
        refreshCookies = null
        previousJob?.cancel()
    }
}
