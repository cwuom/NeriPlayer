# Third-Party License References / 第三方许可引用

NeriPlayer is distributed under the repository-root [GPL-3.0 license](LICENSE).
This document records selected source imports and adaptations, rather than an
exhaustive inventory of Gradle dependencies, submodules, fonts, or other assets.
Those components retain their own copyright and license notices.

NeriPlayer 按根目录的 [GPL-3.0 许可证](LICENSE) 发布。本文记录部分直接引入或
改编的源码，不是 Gradle 依赖、子模块、字体和其他素材的完整清单；这些组件
继续遵循各自的版权和许可证声明。

| Project | Contribution/reference | License |
| --- | --- | --- |
| [BeatSync](https://github.com/freeman-jiang/beatsync) | Unmodified NTP algorithms, scheduling constants, transport helper and tests at commit `94c38ab16ec861835ce0b4bc3a7db82dabbb9b88` in [`tools_pub/beatsync-prototype/js/upstream/`](tools_pub/beatsync-prototype/js/upstream/); prototype and generated production NTP resource in `modules/ltwlan/src/main/resources/beatsync/`, plus original audio scheduling functions and AudioContextManager in `modules/ltwlan/src/main/assets/lan-audio/` | MIT; original notice retained in [`LICENSE`](tools_pub/beatsync-prototype/js/upstream/LICENSE) |
| [Capsulyric](https://github.com/FrancoGiudans/Capsulyric) | Directly ported `ShizukuHook`, `FirewallCompat`, `ShizukuXmsfNetworkHelper`, and the aggressive-isolation state handling used by the Super Island lyric bridge | GPL-3.0-or-later, as declared in the adapted source headers |
| [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) | Upstream Shizuku hooked-binder technique retained by the Capsulyric port | GPL-3.0-only |
| libusb | Sources and headers in [`modules/native/src/main/cpp/third_party/libusb/`](modules/native/src/main/cpp/third_party/libusb/) used by USB exclusive output | LGPL-2.1-or-later, as declared in the imported source headers |

The Super Island adaptations are in
[`shizuku/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/shizuku/)
and [`service/lyrics/`](modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/service/lyrics/).
File-specific notices remain authoritative. NeriPlayer's native alternative
license covers only the owned portions listed in the
[native license document](modules/native/src/main/cpp/README.md); it does not
replace any third-party license.

超级岛相关改编位于上述 `shizuku/` 与 `service/lyrics/` 目录，具体以文件声明为准。
NeriPlayer 的 Native 替代授权仅覆盖 [Native 授权说明](modules/native/src/main/cpp/README.md)
中列出的自有部分，不替代第三方许可证。
