# 播放执行 / Playback runtime

`:playback:runtime` 负责 Media3 播放器、音源解析、后台服务、音频焦点、音效、USB 独占输出及浮窗和蓝牙歌词。

`:playback:runtime` owns the Media3 player, source resolution, background service, audio focus, effects, USB exclusive output, and floating/Bluetooth lyrics.

## 结构 / Layout

主要入口是 [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) 和 [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt)。播放规则来自 `:playback:logic`，平台、本地数据、歌词和一起听使用 `:platform`、`:local`、`:database`、`:lyrics` 与 `:listentogether`；下载通过 `PlayerDownloadAccess` 接入。

The main entry points are [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) and [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt). Rules come from `:playback:logic`; platform sources, local data, lyrics, and Listen Together use `:platform`, `:local`, `:database`, `:lyrics`, and `:listentogether`. Downloads use `PlayerDownloadAccess`.

app 在 `core/di/player` 安装 `PlayerDependencies`；`isInitialized()` 通过环境的 `isReady` 检查仓库是否就绪。

The app installs `PlayerDependencies` in `core/di/player`; `isInitialized()` checks repository readiness through the environment's `isReady` callback.

`audio/effects` 将共享音效模型接入 Media3 AudioSink 和 native DSP，`AudioEffectsOwner` 跟随输出路由更新参数。`AudioEffectsAudioSink` 在系统输出或 USB 独占 sink 之前处理 PCM；USB 原生输出遵守 USB 音效开关和 bit-perfect 旁路规则。C/C++ DSP 与参数布局由 `:native` 维护。

`audio/effects` connects shared effect models to the Media3 AudioSink and native DSP; `AudioEffectsOwner` updates parameters with output-route changes. `AudioEffectsAudioSink` processes PCM before the system-output or USB-exclusive sink. Native USB output respects the USB effects setting and bit-perfect bypass rules. `:native` owns the C/C++ DSP and parameter layout.

## 播放统计 / Playback statistics

| 环节 | 行为 |
| --- | --- |
| 队列与工作线程 | 播放统计通过独立磁盘 FIFO 暂存增量，磁盘写入与数据库重放各使用单一 IO worker |
| 回调交接上限 | 回调最多交接 32 个统计专用快照，停止采样前额外保留最多 2 个已采事件；歌词数据不进入该队列 |
| 持久帧与游标推进 | 每个持久帧记录原事件 ID、播放时间戳、清除栅栏（clear epoch）与歌单归属；待两份统计存储均确认后才推进持久游标 |
| 同步与覆盖恢复栅栏 | 同步与备份在读取主存前先排空该队列；手动覆盖恢复从排空旧事件到发布新清除栅栏期间暂停采样，但回调继续更新播放上下文，防止旧栅栏事件覆盖新恢复的数据 |

| 异常或恢复场景 | 行为与边界 |
| --- | --- |
| 写盘失败 / 交接耗尽 / 超限 | 磁盘不可写、交接容量耗尽或单帧元数据超过 1 MiB 上限时，保留队列内已有事件，停止统计采样并记录明确错误，音频播放继续 |
| 恢复计时规则 | 恢复后沿用当前播放周期的已计数状态，从恢复时刻重新计时，停采区间不补造统计 |
| 进程退出与重试恢复 | 进程退出前尚未落盘的交接事件可能丢失；已发布的持久帧按原事件 ID 重试，残留临时帧不会阻塞已确认的前缀 |

| Stage | Behavior |
| --- | --- |
| Queue & Workers | Stages playback statistics deltas in a dedicated disk FIFO with one IO worker for disk writes and one for database replay |
| Handoff Capacity | Callbacks hand off up to 32 statistics-only snapshots plus at most 2 extra sampled events before stopping; lyrics never enter this queue |
| Durable Frames & Cursor | Each durable frame preserves the event ID, timestamp, clear epoch, and playlist attribution; the durable cursor advances only after both statistics stores acknowledge the event |
| Sync & Overwrite Restore | Sync and backups wait for the queue before reading primary stores; manual overwrite restores pause sampling from queue drain until the new clear epoch is published while callbacks keep updating playback context |

| Failure or Recovery Case | Behavior and Boundaries |
| --- | --- |
| Write Failure / Exhaustion / Oversize | When disk writes fail, handoff capacity is exhausted, or frame metadata exceeds the 1 MiB limit, queued events remain intact, sampling stops with an explicit error, and playback continues |
| Recovery Timing | Resuming retains the current playback cycle's counted state and restarts timing from the recovery point without fabricating data for the paused interval |
| Process Exit & Frame Replay | Unpersisted handoff events may be lost on process exit; published durable frames retry with their original IDs, and leftover temporary frames never block the confirmed prefix |

## 歌曲信息获取 / Song information acquisition

手动获取歌曲信息时可选择歌词来源、原文、翻译、音译及逐词偏好；未选择或未获取到的歌词内容会保留原值。本地歌曲可选择回写音频或仅应用内保存。界面显示歌曲信息、歌词、封面和保存进度，准备期间可取消；开始保存校验后等待本轮提交完成，进度走完并显示完成状态后才关闭。选项与保存位置分组排列，小屏和大字体下可滚动选择，底部操作保持可见。

Manual song information acquisition supports lyric sources, original, translated and romanized lyrics, and word-timing preference. Unselected or unavailable lyric content retains its current value. Local songs offer audio write-back or app-only persistence. The UI shows song information, lyric, artwork and save progress. Preparation can be cancelled; once saving and verification start, the current commit finishes before the progress reaches the end and the completed state is shown, then the sheet closes. Options and save destinations are grouped, with scrollable choices and visible actions on small screens or with larger text.

播放中的本地信息补全在提交时校验播放请求及歌曲快照，并与手动保存共用元数据锁，防止旧读取覆盖刚保存或恢复的信息。明确恢复为无封面时，媒体会话和通知清除旧图及未完成的图片请求；暂时未解析到封面时仍保留当前画面。

Local playback hydration validates the playback request and song snapshots when publishing, using the same metadata lock as manual saves so an older read cannot overwrite saved or restored values. A confirmed restoration to no artwork clears media-session and notification images and pending image requests; temporarily unresolved artwork retains the current frame.

媒体通知合并密集更新并确认实际发布的最后一条内容，系统静默丢弃时有限重试。退出前台或销毁服务时取消待发通知与确认任务，初次前台提升仍立即执行。

Media notifications coalesce rapid updates and confirm the latest publication, retrying a silent system drop within a fixed limit. Leaving foreground or destroying the service cancels pending publications and confirmations; initial foreground promotion remains immediate.

## 兼容 / Compatibility

[UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt) 的完整类名、native 方法名及 FFmpeg 回调须与 JNI 和 [consumer R8 规则](consumer-rules.pro) 一致；[Manifest](src/main/AndroidManifest.xml) 中的服务类名也须保持兼容。FFmpeg AAR 仅用作编译和测试接口，由 app 打包；[`:native`](../../native/README.md) 维护 C/C++ 实现并通过 AAR 向 app 提供 `lib_neri.so`，Kotlin 桥仍在本模块。

The fully qualified name and native methods of [UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt), plus FFmpeg callbacks, must match JNI and [consumer R8 rules](consumer-rules.pro). Service names in the [Manifest](src/main/AndroidManifest.xml) must remain compatible. The FFmpeg AAR supplies compile/test interfaces and is packaged by app. [`:native`](../../native/README.md) owns the C/C++ implementation and supplies `lib_neri.so` to app through its AAR; the Kotlin bridge stays in this module.

[AudioEffectsNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/audio/effects/AudioEffectsNativeBridge.kt) 同样使用静态 JNI 绑定，其类名、native 签名和 R8 保留规则必须与 `:native` 保持一致。该模块的 JVM 测试使用 JDK 21 运行 Android 16 Robolectric sandbox，生产字节码仍以 Java 17 为目标。

[AudioEffectsNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/audio/effects/AudioEffectsNativeBridge.kt) also uses static JNI binding, so its class name, native signatures, and R8 rules must stay aligned with `:native`. JVM tests in this module use JDK 21 for the Android 16 Robolectric sandbox; production bytecode still targets Java 17.

## 测试 / Tests

在仓库根目录运行，设备测试使用 Android 模拟器。USB 实际输出、断连和前后台恢复需另做设备验证。

Run from the repository root with an Android emulator for device tests. USB output, disconnects, and foreground/background recovery require separate device validation.

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```
