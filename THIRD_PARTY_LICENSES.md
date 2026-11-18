[English](#english) | [中文](#中文)

# 第三方许可引用 / Third-Party License References

## 中文

NeriPlayer 按根目录的 [GPL-3.0 许可证](LICENSE) 发布。

本文记录部分直接引入或改编的源码，不是 Gradle 依赖、子模块、字体和其他素材的完整清单。这些组件遵循各自的版权和许可证声明。

### 来源与许可

- [Capsulyric](https://github.com/FrancoGiudans/Capsulyric)
  - 引用：直接移植 `ShizukuHook`、`FirewallCompat`、`ShizukuXmsfNetworkHelper`，以及超级岛歌词桥使用的激进隔离状态处理
  - 许可：GPL-3.0-or-later，以改编源码头部声明为准
- [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)
  - 引用：Capsulyric 移植所沿用的上游 Shizuku hooked-binder 技术
  - 许可：GPL-3.0-only
- libusb
  - 引用：[`modules/native/src/main/cpp/third_party/libusb/`](modules/native/src/main/cpp/third_party/libusb/) 中的源码和头文件，用于 USB 独占输出
  - 许可：LGPL-2.1-or-later，以导入源码头部声明为准

### 超级岛改编与 Native 授权

- 超级岛相关改编位于 [`shizuku/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/shizuku/) 与 [`service/lyrics/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/service/lyrics/)
- 具体以文件声明为准
- NeriPlayer 的 Native 替代授权仅覆盖 [Native 授权说明](modules/native/src/main/cpp/README.md) 中列出的自有部分
- 该授权不替代第三方许可证

## English

NeriPlayer is distributed under the repository-root [GPL-3.0 license](LICENSE).

This document records selected source imports and adaptations. It is not an exhaustive inventory of Gradle dependencies, submodules, fonts, or other assets. Those components keep their own copyright and license notices.

### Sources and licenses

- [Capsulyric](https://github.com/FrancoGiudans/Capsulyric)
  - Contribution: directly ported `ShizukuHook`, `FirewallCompat`, `ShizukuXmsfNetworkHelper`, and the aggressive-isolation state handling used by the Super Island lyric bridge
  - License: GPL-3.0-or-later, as declared in the adapted source headers
- [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)
  - Contribution: the upstream Shizuku hooked-binder technique retained by the Capsulyric port
  - License: GPL-3.0-only
- libusb
  - Contribution: sources and headers in [`modules/native/src/main/cpp/third_party/libusb/`](modules/native/src/main/cpp/third_party/libusb/), used by USB exclusive output
  - License: LGPL-2.1-or-later, as declared in the imported source headers

### Super Island adaptations and native licensing

- The Super Island adaptations are in [`shizuku/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/shizuku/) and [`service/lyrics/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/service/lyrics/)
- File-specific notices are authoritative
- NeriPlayer's native alternative license covers only the owned portions listed in the [native license document](modules/native/src/main/cpp/README.md)
- It does not replace any third-party license
