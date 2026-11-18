[English](./kotlin-helpers_EN.md) | [中文](./kotlin-helpers.md)

# Kotlin 辅助工具使用约定

辅助工具只负责重复的机械动作。请求何时失效、错误如何回退、何时切换播放路径，由业务调用点决定。

## 请求代次 / Request generations

[`RequestGeneration`](../modules/common/src/main/java/moe/ouom/neriplayer/common/concurrent/RequestGeneration.kt) 判断异步结果是否仍属于当前一轮请求。

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

- `advance()` 开启新一轮，使该实例已发出的票据全部失效。
- 同时取消旧任务时，先 `advance()` 再取消，旧任务的清理逻辑就不会改写新状态。
- `capture()` 获取当前轮次的票据，不使其他请求失效。评论页的分页、点赞和回复共用一个轮次。
- 不同业务流使用独立实例。
- 票据按对象身份区分，不复用数字，不序列化到磁盘或网络。
- 票据读取跨线程可见，但 `isCurrent` 检查与状态写入不是原子操作，状态提交须遵守主线程约束或加锁。
- 它只做代次判断，不取消协程、不去重、不提交状态。
- 按歌曲分组和手动优先级用 `SongMetadataRequestCoordinator`，搜索去重用 `ManualSearchRequestCoordinator`。

调用示例见 [`CommentViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/CommentViewModel.kt) 和 [`NowPlayingViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/NowPlayingViewModel.kt)。

## 时间和容量 / Time and capacity

[`MeasureUnits`](../modules/common/src/main/java/moe/ouom/neriplayer/common/units/MeasureUnits.kt) 提供单数形式的时间扩展，普通表达式使用这种写法：

```kotlin
val timeoutMs = 5.minute
val retentionMs = 2.hour
val refreshDelayMs = 30.second
val maxAgeMs = 7.day
val totalMillis = minutes.coerceAtLeast(0).minute
```

- 扩展接收 `Int` 数量，返回以毫秒为单位的 `Long`。
- 乘法前先转为 `Long`，避免 `Int` 溢出。
- 负数按带符号的时间差转换，是否允许由调用点决定。
- `day` 固定为 24 小时，不是日历日。

`const val` 和注解默认值需要编译期常量，使用单位常量：

```kotlin
private const val RETRY_WINDOW_MS = 2 * MINUTE_MS
private const val CHUNK_SIZE_BYTES = 4 * MEBIBYTE_BYTES
```

- 变量和参数名带 `Ms`、`Nanos`、`Bytes` 等单位后缀。
- 业务默认值由对应策略统一维护，如缓存默认容量为 `CacheSizePolicy.DEFAULT_CACHE_SIZE_BYTES`。

[`elapsedMillisSince`](../modules/common/src/main/java/moe/ouom/neriplayer/common/time/MonotonicTime.kt) 的输入必须来自 `System.nanoTime()`。

```kotlin
val startedAtNanos = System.nanoTime()
performOperation()
val elapsedMs = elapsedMillisSince(startedAtNanos)
```

- 不能传入 `currentTimeMillis()`、`elapsedRealtime()` 或已转换成毫秒的数值。
- 负值是否限制为零，由调用点决定。

## 播放器读取 / Player reads

[`PlayerReadExtensions`](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/PlayerReadExtensions.kt) 提供：

- `currentPositionMsOr(fallbackMs)`
- `durationMsOr(fallbackMs)`
- 回退值为 0 的同名属性

这些扩展是 `internal` 的，仅限 `:playback:runtime` 使用；app 和其他库通过播放器宿主接口读取状态。

```kotlin
val positionMs = player.currentPositionMsOr(-1L)
val durationMs = player.durationMsOr(previousDurationMs)
```

- 正常读取的负值限制为零。
- 读取抛异常时返回调用方指定的回退值。
- 需要原始负值的诊断代码直接读取 Player。
- 扩展不切换线程，调用方须在 Player 要求的应用线程中调用。

## 协程结果 / Coroutine results

[`runCatchingNonCancellation`](../modules/common/src/main/java/moe/ouom/neriplayer/common/coroutines/RunCatchingNonCancellation.kt) 的异常处理规则：

- 普通 `Exception` 转为 `Result`。
- `CancellationException` 向上传播。
- `Error` 不被捕获。

它和 block 都是 `suspend`，须在协程或挂起函数中调用，不切换 dispatcher。

```kotlin
return runCatchingNonCancellation {
    repository.read()
}.getOrElse { error ->
    reportFailure(error)
    fallback
}
```

- 日志、回退、重试和 dispatcher 由调用点提供。
- 有专属恢复顺序、资源清理或异常分类的代码，使用显式 `try/catch/finally`。

## PCM 要求 / PCM requirements

[`pcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt) 将音源兼容规则与音频处理条件映射为 `Set<PcmAudioRequirement>`。

- 调用方用 `isNotEmpty()` 判断是否禁用卸载，并可记录全部原因。
- 空集合表示当前策略没有 PCM 要求，实际卸载能力由 Media3 和设备决定。
- 音频路径的切换顺序由播放器调用点决定。

新增条件的步骤：

1. 在 [`PcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PcmAudioRequirements.kt) 的对应规则中更新判断。
2. 通过 `pcmAudioRequirements` 统一返回原因。
3. 为该原因补测试。

现有原因包括 `AUDIO_EFFECTS` 和 `OFFLOAD_STALL_FALLBACK`：

- `AUDIO_EFFECTS`：由调用方传入的 `audioEffectsActive` 表示音效是否真正生效。
- `OFFLOAD_STALL_FALLBACK`：本次播放会话发生卸载无声恢复后保持 PCM，不能只按用户设置重算来清除。

## JSON 对象数组映射 / JSON object array mapping

[`mapObjectsNotNull`](../modules/common/src/main/java/moe/ouom/neriplayer/common/json/JsonArrayExtensions.kt) 用于可以跳过非对象成员的 JSON 数组，统一索引遍历和 `optJSONObject` 判断。

```kotlin
val artists = array.mapObjectsNotNull { item ->
    val id = item.optLong("id", 0L)
    val name = item.optString("name", "").trim()
    if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
    NeteaseArtistSummary(id = id, name = name)
}
```

- JSON null、字符串、数字和嵌套数组被跳过。
- 对象按原顺序交给映射函数，返回 null 的条目被省略，重复对象保留。
- 映射抛出的异常直接传播，接口错误不会被伪装成空列表。
- 必填校验、默认值、去重和分页规则由调用点处理。
- 数组本身缺失时是否使用空列表，由调用方决定。
- 要求每条记录都有效的协议或持久化格式，使用严格解析。

## 按身份合并列表 / Merging lists by identity

[`mergeDistinctBy`](../modules/common/src/main/java/moe/ouom/neriplayer/common/collections/CollectionMerge.kt) 先读已有列表再读新列表，按调用方提供的身份键保留首次出现的条目。

```kotlin
val merged = existing.mergeDistinctBy(incoming, limit = 100) { it.id }
```

- 同一身份保留首次出现的内容，已有列表内部的重复也会合并。
- 函数不修改输入列表。
- `limit` 限制去重后的条数，达到上限立即停止读取。
- `limit` 为 0 返回空结果，负数抛出 `IllegalArgumentException`。
- 是否有下一页、服务器总数、空页处理和身份键选择，由调用点维护。
- 需要保留重复项或用新数据更新旧数据时，使用对应业务的合并策略。
- 播放器队列中的重复歌曲有独立含义，不使用本工具。

评论、网易云首页歌曲和 Bilibili 收藏夹分页已接入；探索搜索使用自己的已有条目处理规则。
