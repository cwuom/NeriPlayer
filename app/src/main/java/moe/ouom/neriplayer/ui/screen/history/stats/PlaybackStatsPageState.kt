package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
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
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal data class StatsPageRequest(val cursor: PlaybackStatsCursor? = null, val before: Boolean = false, val offset: Int = 0, val retry: Int = 0)
internal data class StatsPageState(val summary: PlaybackStatsSummary = PlaybackStatsSummary(), val page: PlaybackStatsPage = PlaybackStatsPage(emptyList(), null), val loading: Boolean = true, val failed: Boolean = false)

@Composable
internal fun rememberStatsPage(query: PlaybackStatsQuery, request: StatsPageRequest): State<StatsPageState> = produceState(StatsPageState(), query, request) {
    value = StatsPageState()
    val repository = AppContainer.playbackStatsRepo
    repository.revisionFlow.catch { error ->
        if (error is CancellationException) throw error
        value = StatsPageState(loading = false, failed = true)
    }.collectLatest {
        value = try {
            StatsPageState(repository.readSummary(query), repository.readPage(query, request.cursor, 100, request.before), loading = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            StatsPageState(loading = false, failed = true)
        }
    }
}

@Composable
internal fun StatsPageNavigation(state: StatsPageState, request: StatsPageRequest, change: (StatsPageRequest) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HapticTextButton(enabled = !state.loading && state.page.previousCursor != null, onClick = {
            change(StatsPageRequest(state.page.previousCursor, before = true, offset = (request.offset - 100).coerceAtLeast(0)))
        }) { Text(stringResource(CoreCommonR.string.stats_previous_page)) }
        HapticTextButton(enabled = !state.loading && state.page.nextCursor != null, onClick = {
            change(StatsPageRequest(state.page.nextCursor, offset = request.offset + state.page.tracks.size))
        }) { Text(stringResource(CoreCommonR.string.stats_next_page)) }
    }
}
