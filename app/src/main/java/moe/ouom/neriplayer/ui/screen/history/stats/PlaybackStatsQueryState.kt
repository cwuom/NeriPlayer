package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.hotPlaybackStatsQuery

@Composable
internal fun rememberPlaybackStatsQuery(
    period: PlaybackStatsPeriod,
    sort: PlaybackStatsSort,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): PlaybackStatsQuery {
    val dayKey = statsQueryDay(nowMillis, timeZone).key.takeUnless { period == PlaybackStatsPeriod.ALL }
    return remember(period, sort, dayKey) { PlaybackStatsQuery(period, sort, nowMillis = nowMillis) }
}

@Composable
internal fun rememberHotPlaybackStatsQuery(
    period: PlaybackStatsPeriod,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): PlaybackStatsQuery {
    val dayKey = statsQueryDay(nowMillis, timeZone).key.takeUnless { period == PlaybackStatsPeriod.ALL }
    return remember(period, dayKey) { hotPlaybackStatsQuery(period, nowMillis) }
}

internal data class StatsQueryDayKey(val startMillis: Long, val endMillis: Long, val timeZoneId: String)
internal data class StatsQueryDay(val nowMillis: Long, val key: StatsQueryDayKey)

internal interface StatsQueryClock {
    fun nowMillis(): Long
    fun timeZone(): TimeZone
}

private object SystemStatsQueryClock : StatsQueryClock {
    override fun nowMillis() = System.currentTimeMillis()
    override fun timeZone(): TimeZone = TimeZone.getDefault()
}

internal fun statsQueryDay(nowMillis: Long, timeZone: TimeZone): StatsQueryDay {
    val calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, 1)
    return StatsQueryDay(nowMillis, StatsQueryDayKey(start, calendar.timeInMillis, timeZone.id))
}

@Composable
internal fun rememberStatsQueryDay(
    clock: StatsQueryClock,
    resumed: StateFlow<Boolean>,
    timeChanges: Flow<Unit>
): State<StatsQueryDay> {
    val initial = remember(clock) { statsQueryDay(clock.nowMillis(), clock.timeZone()) }
    return produceState(initial, clock, resumed, timeChanges) {
        resumed.collectLatest { active ->
            if (active) {
                timeChanges.onStart { emit(Unit) }.collectLatest {
                    while (currentCoroutineContext().isActive) {
                        val day = statsQueryDay(clock.nowMillis(), clock.timeZone())
                        if (day.key != value.key) value = day
                        // 用本地午夜计算下一次检查，系统改时会取消并重新安排
                        delay((day.key.endMillis - clock.nowMillis()).coerceAtLeast(1L))
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberStatsQueryDay(): State<StatsQueryDay> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = remember(lifecycle) { MutableStateFlow(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val timeChanges = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                timeChanges.tryEmit(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return rememberStatsQueryDay(SystemStatsQueryClock, resumed, timeChanges)
}
