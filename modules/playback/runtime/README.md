# 播放执行 / Playback runtime

`:playback:runtime` 负责 Media3 播放器、音源解析、后台服务、音频焦点、音效、USB 独占输出及浮窗和蓝牙歌词。

`:playback:runtime` owns the Media3 player, source resolution, background service, audio focus, effects, USB exclusive output, and floating/Bluetooth lyrics.

## 结构 / Layout

主要入口是 [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) 和 [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt)。播放规则来自 `:playback:logic`，平台、本地数据、歌词和一起听使用 `:platform`、`:local`、`:database`、`:lyrics` 与 `:listentogether`；下载通过 `PlayerDownloadAccess` 接入。

The main entry points are [PlayerManager](src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) and [AudioPlayerService](src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt). Rules come from `:playback:logic`; platform sources, local data, lyrics, and Listen Together use `:platform`, `:local`, `:database`, `:lyrics`, and `:listentogether`. Downloads use `PlayerDownloadAccess`.

app 在 `core/di/player` 安装 `PlayerDependencies`；`isInitialized()` 通过环境的 `isReady` 检查仓库是否就绪。

The app installs `PlayerDependencies` in `core/di/player`; `isInitialized()` checks repository readiness through the environment's `isReady` callback.

## 兼容 / Compatibility

[UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt) 的完整类名、native 方法名及 FFmpeg 回调须与 JNI 和 [consumer R8 规则](consumer-rules.pro) 一致；[Manifest](src/main/AndroidManifest.xml) 中的服务类名也须保持兼容。FFmpeg AAR 仅用作编译和测试接口，AAR 与 native 库由 app 打包。

The fully qualified name and native methods of [UsbExclusiveNativeBridge](src/main/java/moe/ouom/neriplayer/core/player/usb/transport/UsbExclusiveNativeBridge.kt), plus FFmpeg callbacks, must match JNI and [consumer R8 rules](consumer-rules.pro). Service names in the [Manifest](src/main/AndroidManifest.xml) must remain compatible. The FFmpeg AAR supplies compile/test interfaces; app packages the AAR and native libraries.

## 测试 / Tests

在仓库根目录运行，设备测试使用 Android 模拟器。USB 实际输出、断连和前后台恢复需另做设备验证。

Run from the repository root with an Android emulator for device tests. USB output, disconnects, and foreground/background recovery require separate device validation.

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```
