# Kotlin 辅助工具使用约定

辅助工具负责重复的机械动作。请求何时失效、错误如何回退、何时切换播放路径，继续由业务调用点明确决定。

## 请求代次

[`RequestGeneration`](../app/src/main/java/moe/ouom/neriplayer/util/concurrent/RequestGeneration.kt) 用于判断异步结果是否仍属于当前一轮请求。

```kotlin
private val requests = RequestGeneration()

fun refresh() {
    val request = requests.advance()
    loadJob?.cancel()
    loadJob = scope.launch {
        val result = load()
        if (!request.isCurrent) return@launch
        state.value = result
    }
}
```

- `advance()` 开启新一轮，使该实例已经发出的票据失效。需要同时取消旧任务时，先推进代次，再取消任务，防止旧任务的清理逻辑修改新状态。
- `capture()` 获取当前轮次的票据，不使其他请求失效。评论页的分页、点赞和回复使用同一轮次。
- 不同业务流使用独立实例。票据按对象身份区分，不复用数字，不序列化到磁盘或网络。
- 票据读取支持跨线程可见性，但 `isCurrent` 检查与后续状态写入不构成原子操作。状态提交仍须遵守原有的主线程约束或锁。
- 它不取消协程、不判断请求重复、不负责状态提交。需要按歌曲分组和手动优先级时，继续使用 `SongMetadataRequestCoordinator`；搜索去重继续使用 `ManualSearchRequestCoordinator`。

实际调用见 [`CommentViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/CommentViewModel.kt) 和 [`NowPlayingViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/NowPlayingViewModel.kt)。

## 时间和容量

[`MeasureUnits`](../app/src/main/java/moe/ouom/neriplayer/util/units/MeasureUnits.kt) 提供单位常量，保持 Kotlin 常量表达式能力，可用于 `const val` 和注解默认值。

```kotlin
private const val RETRY_WINDOW_MS = 2 * MINUTE_MS
private const val CHUNK_SIZE_BYTES = 4 * MEBIBYTE_BYTES
```

变量和参数仍保留 `Ms`、`Nanos`、`Bytes` 等单位后缀。业务默认值由对应策略统一维护，例如缓存默认容量使用 `CacheSizePolicy.DEFAULT_CACHE_SIZE_BYTES`。

[`elapsedMillisSince`](../app/src/main/java/moe/ouom/neriplayer/util/time/MonotonicTime.kt) 的输入必须来自 `System.nanoTime()`。

```kotlin
val startedAtNanos = System.nanoTime()
performOperation()
val elapsedMs = elapsedMillisSince(startedAtNanos)
```

不要传入 `currentTimeMillis()`、`elapsedRealtime()` 或已经转换成毫秒的数值。是否将负值限制为零由调用点决定。

## 播放器读取

[`PlayerReadExtensions`](../app/src/main/java/moe/ouom/neriplayer/core/player/PlayerReadExtensions.kt) 提供 `currentPositionMsOr(fallbackMs)`、`durationMsOr(fallbackMs)` 和默认回退到零的属性。

```kotlin
val positionMs = player.currentPositionMsOr(-1L)
val durationMs = player.durationMsOr(previousDurationMs)
```

正常读取的负值限制为零；只有读取抛异常才返回调用方指定的值。需要保留播放器原始负值的诊断代码继续直接读取。扩展不切换线程，调用方仍需遵守 Player 的应用线程要求。

## 协程结果

[`runCatchingNonCancellation`](../app/src/main/java/moe/ouom/neriplayer/util/coroutines/RunCatchingNonCancellation.kt) 将普通 `Exception` 转为 `Result`，`CancellationException` 继续向上传播，`Error` 不被捕获。

```kotlin
return runCatchingNonCancellation {
    repository.read()
}.getOrElse { error ->
    reportFailure(error)
    fallback
}
```

日志、回退、重试及 dispatcher 由调用点提供。带有专属恢复顺序、资源清理或不同异常分类的代码，应保留显式的 `try/catch/finally`。

## PCM 要求

[`pcmAudioRequirements`](../app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt) 将现有音源兼容规则与音频处理条件映射为 `Set<PcmAudioRequirement>`。调用方用 `isNotEmpty()` 判断是否需要禁用卸载，并可记录全部原因。

新增条件时，在这一个函数里更新判断，并为对应原因补测试。空集合只表示当前策略没有提出 PCM 要求，实际卸载能力仍由 Media3 和设备决定。音频路径的切换顺序继续保留在播放器调用点。
