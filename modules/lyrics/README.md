# 歌词 / Lyrics

`:lyrics` 负责 LRC、YRC、TTML 解析、翻译对齐、嵌入歌词、时间偏移，以及 Lyricon 和 SuperLyric 接入。远程歌词检索由 `:platform` 处理。

`:lyrics` handles LRC, YRC, and TTML parsing, translation alignment, embedded lyrics, time offsets, and Lyricon/SuperLyric integration. Remote lyric lookup belongs to `:platform`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/lyrics`。解析在 `parser`，嵌入歌词兼容在 `embedded`，偏移计算在 `offset`。模型来自 `:model`，共用工具来自 `:common`，基础解析库为 `:accompanist-lyrics-core`；歌词设置由 `:local` 保存。

Sources are in `src/main/java/moe/ouom/neriplayer/lyrics`: `parser` for parsing, `embedded` for embedded lyric compatibility, and `offset` for offset calculations. Dependencies are `:model`, `:common`, and `:accompanist-lyrics-core`; `:local` persists lyric settings.

## 词幕 / Lyricon

`output/LyriconPlaybackOutput.kt` 接收播放状态，通过 `LyriconLyricsLoader` 加载歌词。`lyricon` 接入词幕 SDK，并兼容 SuperLyric。位置独立每 200 ms 推送，400 ms 显示预推只用于输出，不改变媒体时间轴。SDK、Manifest 元数据和 consumer R8 规则均在本库。

`output/LyriconPlaybackOutput.kt` receives playback state and loads lyrics through `LyriconLyricsLoader`. The `lyricon` package integrates Lyricon and also supports SuperLyric. Position updates run every 200 ms; the 400 ms display lead affects output only, leaving the media timeline unchanged. SDKs, manifest metadata, and consumer R8 rules stay in this library.

## 测试 / Tests

JVM 测试覆盖解析与输出时序；嵌入歌词的 Unicode 时间戳回归测试位于 app 的 Android 测试。

JVM tests cover parsing and output timing. Embedded Unicode timestamp regressions are covered by Android tests in app.

```bash
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :lyrics:testDebugUnitTest --tests "*Lyricon*"
```
