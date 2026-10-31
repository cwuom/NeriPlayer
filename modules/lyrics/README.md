# 歌词 / Lyrics

`:lyrics` 负责 LRC、YRC、TTML 解析、翻译对齐、嵌入歌词、时间偏移，以及 Lyricon 和 SuperLyric 接入。远程歌词检索由 `:platform` 处理。

`:lyrics` handles LRC, YRC, and TTML parsing, translation alignment, embedded lyrics, time offsets, and Lyricon/SuperLyric integration. Remote lyric lookup belongs to `:platform`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/lyrics`。解析在 `parser`，嵌入歌词兼容在 `embedded`，偏移计算在 `offset`。模型来自 `:model`，共用工具来自 `:common`，基础解析库为 `:accompanist-lyrics-core`；歌词设置由 `:local` 保存。

Sources are in `src/main/java/moe/ouom/neriplayer/lyrics`: `parser` for parsing, `embedded` for embedded lyric compatibility, and `offset` for offset calculations. Dependencies are `:model`, `:common`, and `:accompanist-lyrics-core`; `:local` persists lyric settings.

## 词幕 / Lyricon

| 项目 | 说明 |
| --- | --- |
| 组件与打包范围 | `output/LyriconPlaybackOutput.kt` 接收播放状态并通过 `LyriconLyricsLoader` 加载歌词；`lyricon` 包集成词幕（Lyricon）SDK 并兼容 SuperLyric，SDK、Manifest 元数据与 consumer R8 规则均内置于本模块 |
| 位置推送与熄屏策略 | 已启用、正在播放、屏幕可交互且 provider 就绪时，每 200 ms 独立推送位置；熄屏停止周期推送并保留媒体锚点，亮屏后重新对齐 |
| 显示预推偏移 | 400 ms 显示预推仅作用于输出端，不改变底层媒体时间轴 |

| Item | Details |
| --- | --- |
| Components & Packaging | `output/LyriconPlaybackOutput.kt` receives playback state and loads lyrics via `LyriconLyricsLoader`; the `lyricon` package integrates the Lyricon SDK with SuperLyric compatibility, keeping SDKs, manifest metadata, and consumer R8 rules inside this module |
| Position Feed & Screen State | Pushes independent position updates every 200 ms when enabled, playing, screen-interactive, and backed by a ready provider; stops periodic updates on screen-off while keeping the media anchor, then realigns on screen-on |
| Display Lead | The 400 ms display lead applies only to external output and does not alter the media timeline |

## 测试 / Tests

JVM 测试覆盖解析与输出时序；嵌入歌词的 Unicode 时间戳回归测试位于 app 的 Android 测试。

JVM tests cover parsing and output timing. Embedded Unicode timestamp regressions are covered by Android tests in app.

```bash
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :lyrics:testDebugUnitTest --tests "*Lyricon*"
```
