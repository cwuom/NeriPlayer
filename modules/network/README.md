# 网络 / Network

`:network` 提供 HTTP 协程适配、Cookie 和 Header 解析、Range 请求、代理选择、主机校验和网页登录辅助。平台请求协议由 `:platform` 维护，同步与一起听的传输实现在各自模块。

`:network` provides HTTP coroutine adapters, Cookie and Header parsing, ranged requests, proxy selection, host validation, and Web login helpers. Platform protocols belong to `:platform`; sync and Listen Together own their transports.

源码在 `src/main/java/moe/ouom/neriplayer/network`，按 `http`、`range`、`proxy`、`security` 和 `weblogin` 分包。项目依赖只有 `:common`。`Call.awaitResponse` 传递取消并关闭响应，`nonReplayable` 阻止写请求自动重放。

Sources are in `src/main/java/moe/ouom/neriplayer/network`, grouped into `http`, `range`, `proxy`, `security`, and `weblogin`. Its only project dependency is `:common`. `Call.awaitResponse` forwards cancellation and closes responses; `nonReplayable` prevents automatic replay of writes.

## 测试 / Tests

```bash
./gradlew :network:testDebugUnitTest :network:lintDebug
```
