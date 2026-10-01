# 通用工具 / Common

`:common` 提供日志、集合与 JSON 辅助、搜索匹配、时间与单位转换、请求代次、协程取消保护和文件原子写入，以及共用文案、图标和语言切换。

`:common` provides logging, collection and JSON helpers, search matching, time and unit conversions, request generations, cancellation handling, and atomic file writes, plus shared strings, icons, and locale support.

源码在 `src/main/java/moe/ouom/neriplayer/common`，资源在 `src/main/res`。模块不依赖其它项目实现；共享业务模型由 `:model` 维护。`src/testFixtures` 供播放和下载运行库复用。

Sources are in `src/main/java/moe/ouom/neriplayer/common`, with resources in `src/main/res`. The module has no project implementation dependencies; shared business models belong to `:model`. Playback and download runtime libraries reuse `src/testFixtures`.

## 测试 / Tests

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```
