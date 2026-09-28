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

[`MeasureUnits`](../app/src/main/java/moe/ouom/neriplayer/util/units/MeasureUnits.kt) 提供单数形式的时间扩展，普通表达式优先使用这种写法：

```kotlin
val timeoutMs = 5.minute
val retentionMs = 2.hour
val refreshDelayMs = 30.second
val maxAgeMs = 7.day
val totalMillis = minutes.coerceAtLeast(0).minute
```

扩展接收 `Int` 数量，返回以毫秒为单位的 `Long`。乘法前先转为 `Long`，不会发生 `Int` 乘法溢出。负数按带符号的时间差转换；是否允许负值仍由调用点决定。`day` 固定为 24 小时，不代表日历中的一天。

`const val` 和注解默认值需要编译期常量，继续使用单位常量：

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

## JSON 对象数组映射

[`mapObjectsNotNull`](../app/src/main/java/moe/ouom/neriplayer/util/json/JsonArrayExtensions.kt) 用于允许跳过非对象成员的 JSON 数组，统一索引遍历和 `optJSONObject` 判断。

```kotlin
val artists = array.mapObjectsNotNull { item ->
    val id = item.optLong("id", 0L)
    val name = item.optString("name", "").trim()
    if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
    NeteaseArtistSummary(id = id, name = name)
}
```

JSON null、字符串、数字和嵌套数组会被跳过。对象按原顺序交给映射函数，映射返回 null 时省略该条记录；重复对象仍然保留。映射抛出的异常直接传播，不会把接口错误伪装成空列表。

字段必填校验、默认值、去重和分页规则仍在调用点。数组本身缺失时是否使用空列表，也由调用方决定。要求每条记录都有效的协议或持久化格式，继续使用严格解析。

## 按身份合并列表

[`mergeDistinctBy`](../app/src/main/java/moe/ouom/neriplayer/util/collections/CollectionMerge.kt) 先读取已有列表，再读取新列表，按调用方提供的身份键保留首次出现的条目。

```kotlin
val merged = existing.mergeDistinctBy(incoming, limit = 100) { it.id }
```

- 同一身份保留首次出现的内容，已有列表内部的重复也会合并。函数不修改输入列表。
- `limit` 限制去重后的结果条数，达到上限后立即停止读取。零表示空结果，负数会抛出 `IllegalArgumentException`。
- 是否还有下一页、服务器总数、空页的处理方式和身份键的选择，仍由调用点维护。
- 需要保留重复项或用新数据更新旧数据时，继续使用对应业务的合并策略。播放器队列中的重复歌曲有独立含义，不适用这个工具。

评论、网易云首页歌曲和 Bilibili 收藏夹分页已接入；探索搜索保留其原有的已有条目处理规则。
