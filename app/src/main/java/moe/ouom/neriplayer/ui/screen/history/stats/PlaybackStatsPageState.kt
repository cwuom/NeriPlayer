package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal data class StatsPageRequest(val cursor: PlaybackStatsCursor? = null, val before: Boolean = false, val offset: Int = 0, val retry: Int = 0)
internal data class StatsPageState(
    val summary: PlaybackStatsSummary = PlaybackStatsSummary(),
    val page: PlaybackStatsPage = PlaybackStatsPage(emptyList(), null),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val loadedQuery: PlaybackStatsQuery? = null,
    val loadedRequest: StatsPageRequest? = null
)

@Composable
internal fun rememberStatsPage(
    query: PlaybackStatsQuery,
    request: MutableState<StatsPageRequest>,
    repository: PlaybackStatsRepository = AppContainer.playbackStatsRepo
): State<StatsPageState> = produceState(StatsPageState(), query, request, repository) {
    snapshotFlow { request.value }.collectLatest { current ->
        // 刷新时保留上一份结果及其排名游标，避免页面和玻璃区域反复卸载
        value = value.copy(loading = true, failed = false)
        repository.revisionFlow.catch { error ->
            if (error is CancellationException) throw error
            value = value.copy(loading = false, failed = true)
        }.collectLatest {
            value = value.copy(loading = true, failed = false)
            value = try {
                val summary = repository.readSummary(query)
                val page = repository.readPage(query, current.cursor, 100, current.before)
                if (page.tracks.isEmpty() && current.cursor != null) {
                    // 排名变化会让旧游标落在末尾，重置请求后只读取一次首页
                    request.value = StatsPageRequest()
                    value.copy(loading = true, failed = false)
                } else {
                    StatsPageState(summary, page, loading = false, loadedQuery = query, loadedRequest = current)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                value.copy(loading = false, failed = true)
            }
        }
    }
}

@Composable
internal fun StatsPageNavigation(state: StatsPageState, request: StatsPageRequest, change: (StatsPageRequest) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HapticTextButton(enabled = !state.loading && !state.failed && (state.page.previousCursor != null || request.cursor != null), onClick = {
            change(if (state.page.tracks.isEmpty() || state.page.previousCursor == null) StatsPageRequest()
                else StatsPageRequest(state.page.previousCursor, before = true, offset = (request.offset - 100).coerceAtLeast(0)))
        }) { Text(stringResource(CoreCommonR.string.stats_previous_page)) }
        HapticTextButton(enabled = !state.loading && !state.failed && state.page.nextCursor != null, onClick = {
            change(StatsPageRequest(state.page.nextCursor, offset = request.offset + state.page.tracks.size))
        }) { Text(stringResource(CoreCommonR.string.stats_next_page)) }
    }
}
