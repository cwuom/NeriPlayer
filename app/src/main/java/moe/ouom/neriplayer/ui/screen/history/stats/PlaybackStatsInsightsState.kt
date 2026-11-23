package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository

internal data class ListeningInsightsState(
    val insights: PlaybackStatsInsights? = null,
    val hours: List<Long> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

@Composable
internal fun rememberListeningInsights(
    query: PlaybackStatsQuery,
    retry: Int,
    repository: PlaybackStatsRepository = AppContainer.playbackStatsRepo
): State<ListeningInsightsState> = key(query, repository) {
    produceState(ListeningInsightsState(), retry) {
        try {
            repository.revisionFlow.collectLatest {
                value = value.copy(loading = true, failed = false)
                try {
                    value = coroutineScope {
                        val hours = async { repository.readHourlyListening(query) }
                        ListeningInsightsState(repository.readInsights(query), hours.await(), loading = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    value = value.copy(loading = false, failed = true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = value.copy(loading = false, failed = true)
        }
    }
}
