# 播放逻辑 / Playback logic

`:playback:logic` 提供播放决策、进度恢复、状态协调、PCM 处理和队列规则。

`:playback:logic` provides playback decisions, resume positions, state coordination, PCM processing, and queue rules.

## 结构 / Layout

源码位于 [core/player](src/main/java/moe/ouom/neriplayer/core/player)，依赖 `:model`、`:common`、协程和 Media3 音频接口。实际播放器、服务和设备接入由 `:playback:runtime` 负责。

Sources live in [core/player](src/main/java/moe/ouom/neriplayer/core/player), using `:model`, `:common`, coroutines, and Media3 audio interfaces. `:playback:runtime` owns the player, services, and device integration.

`policy` 不引用 `runtime`、`audio` 或 `queue` 实现；USB 策略保留在 `:playback:runtime`。

`policy` does not reference `runtime`, `audio`, or `queue` implementations. USB policies remain in `:playback:runtime`.

## 测试 / Tests

在仓库根目录运行：

Run from the repository root:

```bash
./gradlew :playback:logic:verifyCrap :playback:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```
