[English](#english) | [中文](#中文)

# 网络 / Network

## 中文

`:network` 提供 HTTP 协程适配、Cookie 和 Header 解析、Range 请求、代理选择、主机校验和网页登录辅助。

- 平台请求协议由 `:platform` 维护
- 同步与一起听的传输实现在各自模块

### 结构

- 源码：`src/main/java/moe/ouom/neriplayer/network`
- 分包：`http`、`range`、`proxy`、`security`、`weblogin`
- 项目依赖：仅 `:common`
- `Call.awaitResponse` 传递取消并关闭响应
- `nonReplayable` 阻止写请求自动重放

### 测试

```bash
./gradlew :network:testDebugUnitTest :network:lintDebug
```

## English

`:network` provides HTTP coroutine adapters, Cookie and Header parsing, ranged requests, proxy selection, host validation, and Web login helpers.

- Platform request protocols belong to `:platform`
- Sync and Listen Together own their transports

### Layout

- Sources: `src/main/java/moe/ouom/neriplayer/network`
- Packages: `http`, `range`, `proxy`, `security`, `weblogin`
- Project dependencies: `:common` only
- `Call.awaitResponse` forwards cancellation and closes responses
- `nonReplayable` prevents automatic replay of write requests

### Tests

```bash
./gradlew :network:testDebugUnitTest :network:lintDebug
```
