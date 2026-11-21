[English](#english) | [中文](#中文)

# 播放逻辑 / Playback logic

## 中文

`:playback:logic` 提供播放决策、进度恢复、状态协调、PCM 处理和队列规则。

### 结构

- 源码：[core/player](src/main/java/moe/ouom/neriplayer/core/player)
- 依赖：`:model`、`:common`、协程和 Media3 音频接口
- 实际播放器、服务和设备接入由 `:playback:runtime` 负责
- `policy` 不引用 `runtime`、`audio` 或 `queue` 实现
- USB 策略位于 `:playback:runtime`

### 测试

在仓库根目录运行：

```bash
./gradlew :playback:logic:verifyCrap :playback:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```

## English

`:playback:logic` provides playback decisions, resume positions, state coordination, PCM processing, and queue rules.

### Layout

- Sources: [core/player](src/main/java/moe/ouom/neriplayer/core/player)
- Dependencies: `:model`, `:common`, coroutines, and Media3 audio interfaces
- `:playback:runtime` owns the player, services, and device integration
- `policy` does not reference `runtime`, `audio`, or `queue` implementations
- USB policies live in `:playback:runtime`

### Tests

Run from the repository root:

```bash
./gradlew :playback:logic:verifyCrap :playback:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```
