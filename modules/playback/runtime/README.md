[English](#english) | [中文](#中文)

# 播放执行 / Playback runtime

## 中文

`:playback:runtime` 负责 Media3 播放器、音源解析、后台服务、音频焦点、音效、USB 独占输出及浮窗和蓝牙歌词。

### 结构

- 主要入口：[PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) 和 [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt)
- 播放规则来自 `:playback:logic`
- 平台、本地数据、歌词和一起听使用 `:platform`、`:local`、`:database`、`:lyrics`、`:listentogether`
- 下载通过 `PlayerDownloadAccess` 接入
- app 在 `core/di/player` 安装 `PlayerDependencies`
- `isInitialized()` 通过环境的 `isReady` 检查仓库是否就绪

### 音效

- `audio/effects` 将共享音效模型接入 Media3 AudioSink 和 native DSP
- `AudioEffectsOwner` 跟随输出路由更新参数
- `AudioEffectsAudioSink` 在系统输出或 USB 独占 sink 之前处理 PCM
- USB 原生输出遵守 USB 音效开关和 bit-perfect 旁路规则
- C/C++ DSP 与参数布局由 `:native` 维护

### 播放统计

队列与线程：

- 播放统计通过独立磁盘 FIFO 暂存增量
- 磁盘写入与数据库重放各使用一个 IO worker
- 回调最多交接 32 个统计专用快照，停止采样前另留最多 2 个已采事件
- 歌词数据不进入该队列

持久帧：

- 每个持久帧记录原事件 ID、播放时间戳、清除栅栏（clear epoch）与歌单归属
- 两份统计存储均确认后，才推进持久游标

同步与恢复：

- 同步与备份在读取主存前先排空该队列
- 手动覆盖恢复从排空旧事件到发布新清除栅栏期间暂停采样
- 暂停期间回调继续更新播放上下文，旧栅栏事件不会覆盖新恢复的数据

异常处理：

- 磁盘不可写、交接容量耗尽或单帧元数据超过 1 MiB 时，保留队列内已有事件
- 此时停止统计采样并记录明确错误，音频播放继续
- 恢复后沿用当前播放周期的已计数状态，从恢复时刻重新计时，停采区间不补造统计
- 进程退出前尚未落盘的交接事件可能丢失
- 已发布的持久帧按原事件 ID 重试，残留临时帧不会阻塞已确认的前缀

### 歌曲信息获取

手动获取：

- 可选择歌词来源、原文、翻译、音译及逐词偏好
- 未选择或未获取到的歌词内容保留原值
- 本地歌曲可选择回写音频，或仅在应用内保存
- 界面显示歌曲信息、歌词、封面和保存进度
- 准备期间可取消
- 开始保存校验后，等当前提交完成，进度走完并显示完成状态再关闭
- 选项与保存位置分组排列，小屏和大字体下可滚动，底部操作保持可见

播放中的本地信息补全：

- 提交时校验播放请求及歌曲快照
- 与手动保存共用元数据锁，旧读取不会覆盖刚保存或恢复的信息
- 明确恢复为无封面时，媒体会话和通知清除旧图及未完成的图片请求
- 封面暂时未解析时，保留当前画面

媒体通知：

- 合并密集更新，并确认实际发布的最后一条内容
- 系统静默丢弃时有限次重试
- 退出前台或销毁服务时取消待发通知与确认任务
- 初次前台提升立即执行

### 兼容

- [UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt) 的完整类名、native 方法名及 FFmpeg 回调须与 JNI 和 [consumer R8 规则](consumer-rules.pro) 一致
- [Manifest](src/main/AndroidManifest.xml) 中的服务类名须保持兼容
- [AudioEffectsNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/audio/effects/AudioEffectsNativeBridge.kt) 同样使用静态 JNI 绑定，类名、native 签名和 R8 规则须与 `:native` 一致
- FFmpeg AAR 仅提供编译和测试接口，由 app 打包
- [`:native`](../../native/README.md) 维护 C/C++ 实现，通过 AAR 向 app 提供 `lib_neri.so`；Kotlin 桥在本模块
- 本模块 JVM 测试使用 JDK 21 运行 Android 16 Robolectric sandbox，生产字节码以 Java 17 为目标

### 测试

在仓库根目录运行，设备测试使用 Android 模拟器。USB 实际输出、断连和前后台恢复需另做设备验证。

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```

## English

`:playback:runtime` owns the Media3 player, source resolution, background service, audio focus, effects, USB exclusive output, and floating/Bluetooth lyrics.

### Layout

- Main entry points: [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) and [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt)
- Playback rules come from `:playback:logic`
- Platform sources, local data, lyrics, and Listen Together use `:platform`, `:local`, `:database`, `:lyrics`, `:listentogether`
- Downloads connect through `PlayerDownloadAccess`
- The app installs `PlayerDependencies` in `core/di/player`
- `isInitialized()` checks repository readiness through the environment's `isReady` callback

### Effects

- `audio/effects` connects shared effect models to the Media3 AudioSink and native DSP
- `AudioEffectsOwner` updates parameters as the output route changes
- `AudioEffectsAudioSink` processes PCM before the system-output or USB-exclusive sink
- Native USB output follows the USB effects switch and bit-perfect bypass rules
- `:native` owns the C/C++ DSP and parameter layout

### Playback statistics

Queue and workers:

- Statistics deltas are staged in a dedicated disk FIFO
- One IO worker handles disk writes and another handles database replay
- Callbacks hand off up to 32 statistics-only snapshots, plus at most 2 extra sampled events before sampling stops
- Lyrics data never enters this queue

Durable frames:

- Each durable frame records the event ID, timestamp, clear epoch, and playlist attribution
- The durable cursor advances only after both statistics stores acknowledge the event

Sync and restore:

- Sync and backups drain the queue before reading the primary stores
- Manual overwrite restore pauses sampling from queue drain until the new clear epoch is published
- Callbacks keep updating playback context during the pause, so old-epoch events cannot overwrite restored data

Failure handling:

- When the disk is unwritable, handoff capacity runs out, or a frame's metadata exceeds 1 MiB, queued events are kept
- Sampling then stops with an explicit error, and audio playback continues
- After recovery, the current playback cycle's counted state is kept and timing restarts at the recovery point; the paused interval is not backfilled
- Handoff events not yet written to disk may be lost on process exit
- Published durable frames retry with their original event IDs, and leftover temporary frames never block the confirmed prefix

### Song information acquisition

Manual acquisition:

- Choose the lyric source, original, translated and romanized lyrics, and word-timing preference
- Unselected or unavailable lyric content keeps its current value
- Local songs can write back to the audio file or save within the app only
- The UI shows song information, lyrics, artwork, and save progress
- Preparation can be cancelled
- Once save verification starts, the current commit finishes, progress completes, the done state shows, and the sheet closes
- Options and save destinations are grouped; choices scroll on small screens or large text, and actions stay visible

Local hydration during playback:

- Publishing validates the playback request and song snapshots
- It shares the metadata lock with manual saves, so an older read cannot overwrite saved or restored values
- A confirmed restore to no artwork clears media-session and notification images and pending image requests
- Artwork that is temporarily unresolved keeps the current frame

Media notifications:

- Rapid updates are coalesced, and the last published content is confirmed
- A silent system drop is retried a limited number of times
- Leaving foreground or destroying the service cancels pending publications and confirmations
- Initial foreground promotion runs immediately

### Compatibility

- The fully qualified name, native methods, and FFmpeg callbacks of [UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt) must match JNI and the [consumer R8 rules](consumer-rules.pro)
- Service class names in the [Manifest](src/main/AndroidManifest.xml) must stay compatible
- [AudioEffectsNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/audio/effects/AudioEffectsNativeBridge.kt) also uses static JNI binding; its class name, native signatures, and R8 rules must match `:native`
- The FFmpeg AAR supplies compile and test interfaces only and is packaged by app
- [`:native`](../../native/README.md) owns the C/C++ implementation and supplies `lib_neri.so` to app through its AAR; the Kotlin bridge is in this module
- JVM tests in this module use JDK 21 for the Android 16 Robolectric sandbox; production bytecode targets Java 17

### Tests

Run from the repository root; device tests use an Android emulator. USB output, disconnects, and foreground/background recovery require separate device validation.

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```
