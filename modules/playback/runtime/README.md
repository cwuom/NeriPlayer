# 播放执行 / Playback runtime

`:playback:runtime` 负责 Media3 播放器、音源解析、后台服务、音频焦点、音效、USB 独占输出及浮窗和蓝牙歌词。

`:playback:runtime` owns the Media3 player, source resolution, background service, audio focus, effects, USB exclusive output, and floating/Bluetooth lyrics.

## 结构 / Layout

主要入口是 [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) 和 [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt)。播放规则来自 `:playback:logic`，平台、本地数据、歌词和一起听使用 `:platform`、`:local`、`:database`、`:lyrics` 与 `:listentogether`；下载通过 `PlayerDownloadAccess` 接入。

The main entry points are [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) and [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt). Rules come from `:playback:logic`; platform sources, local data, lyrics, and Listen Together use `:platform`, `:local`, `:database`, `:lyrics`, and `:listentogether`. Downloads use `PlayerDownloadAccess`.

app 在 `core/di/player` 安装 `PlayerDependencies`；`isInitialized()` 通过环境的 `isReady` 检查仓库是否就绪。

The app installs `PlayerDependencies` in `core/di/player`; `isInitialized()` checks repository readiness through the environment's `isReady` callback.

播放统计通过独立的磁盘 FIFO 暂存增量，磁盘写入和数据库重放各只有一个 IO worker。回调先交接最多 32 个统计专用快照，停止采样前还保留最多两个已采事件；歌词不进入该队列。每个持久帧保留原事件 ID、播放时间、清除栅栏和歌单归属，两份统计都确认后才推进持久游标。同步和备份在读取主存前等待该队列；手动覆盖恢复从排空旧事件到发布新清除栅栏期间暂停采样，回调继续更新播放上下文，防止旧栅栏的事件覆盖刚恢复的数据。

磁盘也不可写或交接容量耗尽时，已有事件保留，统计停止采样并明确记录错误，播放继续。恢复后沿用当前播放周期的计数状态，从恢复时刻重新计时，停采区间不补造统计。单帧元数据最多 1 MiB，超预算会进入同一停采流程。进程退出前尚未落盘的交接事件无法保证恢复；已发布持久帧会按原 ID 重试，残临时帧不会阻塞已确认的前缀。

## 兼容 / Compatibility

[UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt) 的完整类名、native 方法名及 FFmpeg 回调须与 JNI 和 [consumer R8 规则](consumer-rules.pro) 一致；[Manifest](src/main/AndroidManifest.xml) 中的服务类名也须保持兼容。FFmpeg AAR 仅用作编译和测试接口，由 app 打包；[`:native`](../../native/README.md) 维护 C/C++ 实现并通过 AAR 向 app 提供 `lib_neri.so`，Kotlin 桥仍在本模块。

The fully qualified name and native methods of [UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt), plus FFmpeg callbacks, must match JNI and [consumer R8 rules](consumer-rules.pro). Service names in the [Manifest](src/main/AndroidManifest.xml) must remain compatible. The FFmpeg AAR supplies compile/test interfaces and is packaged by app. [`:native`](../../native/README.md) owns the C/C++ implementation and supplies `lib_neri.so` to app through its AAR; the Kotlin bridge stays in this module.

## 测试 / Tests

在仓库根目录运行，设备测试使用 Android 模拟器。USB 实际输出、断连和前后台恢复需另做设备验证。

Run from the repository root with an Android emulator for device tests. USB output, disconnects, and foreground/background recovery require separate device validation.

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```
