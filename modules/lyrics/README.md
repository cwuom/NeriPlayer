[English](#english) | [中文](#中文)

# 歌词 / Lyrics

## 中文

`:lyrics` 负责 LRC、YRC、TTML 解析、翻译对齐、嵌入歌词、时间偏移，以及 Lyricon 和 SuperLyric 接入。远程歌词检索由 `:platform` 处理。

### 结构

- 源码：`src/main/java/moe/ouom/neriplayer/lyrics`
- `parser`：歌词解析
- `embedded`：嵌入歌词兼容
- `offset`：偏移计算
- 依赖：`:model`、`:common` 和基础解析库 `:accompanist-lyrics-core`
- 歌词设置由 `:local` 保存

### 词幕 / Lyricon

组件与打包：

- `output/LyriconPlaybackOutput.kt` 接收播放状态，通过 `LyriconLyricsLoader` 加载歌词
- `lyricon` 包集成词幕（Lyricon）SDK，并兼容 SuperLyric
- SDK、Manifest 元数据与 consumer R8 规则均内置于本模块

位置推送：

- 已启用、正在播放、屏幕可交互且 provider 就绪时，每 200 ms 独立推送位置
- 熄屏时停止周期推送并保留媒体锚点，亮屏后重新对齐
- 400 ms 显示预推仅作用于输出端，底层媒体时间轴不变

### 测试

JVM 测试覆盖解析与输出时序；嵌入歌词的 Unicode 时间戳回归测试位于 app 的 Android 测试。

```bash
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :lyrics:testDebugUnitTest --tests "*Lyricon*"
```

## English

`:lyrics` handles LRC, YRC, and TTML parsing, translation alignment, embedded lyrics, time offsets, and Lyricon/SuperLyric integration. Remote lyric lookup belongs to `:platform`.

### Layout

- Sources: `src/main/java/moe/ouom/neriplayer/lyrics`
- `parser`: lyric parsing
- `embedded`: embedded lyric compatibility
- `offset`: offset calculations
- Dependencies: `:model`, `:common`, and the base parsing library `:accompanist-lyrics-core`
- `:local` persists lyric settings

### Lyricon

Components and packaging:

- `output/LyriconPlaybackOutput.kt` receives playback state and loads lyrics via `LyriconLyricsLoader`
- The `lyricon` package integrates the Lyricon SDK and supports SuperLyric
- The SDKs, manifest metadata, and consumer R8 rules are all bundled in this module

Position feed:

- When enabled, playing, screen-interactive, and the provider is ready, position is pushed every 200 ms independently
- On screen-off, periodic pushes stop and the media anchor is kept; screen-on realigns
- The 400 ms display lead applies only to the output side; the media timeline is unchanged

### Tests

JVM tests cover parsing and output timing. Embedded Unicode timestamp regressions are covered by Android tests in app.

```bash
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :lyrics:testDebugUnitTest --tests "*Lyricon*"
```
