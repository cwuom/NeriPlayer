# 一起听 / Listen Together

`:listentogether` 管理房间会话、连接恢复、控制与播放同步，以及 HTTP/WebSocket 协议。共享模型在 `:model`，服务端在 `np-submodule/NeriPlayer-LTW`。

`:listentogether` handles room sessions, reconnection, playback controls and synchronization, and the HTTP/WebSocket protocol. Shared models live in `:model`; the server is in `np-submodule/NeriPlayer-LTW`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer`。

Sources are in `src/main/java/moe/ouom/neriplayer`.

- `data/ltw/ListenTogetherSessionManager.kt`：会话入口 / session entry point
- `api/ltw`：HTTP、WebSocket 与重连 / HTTP, WebSocket, and reconnects
- `listentogether/protocol`、`listentogether/profile`：消息编解码与身份校验 / message codecs and profile validation

播放、歌曲映射与平台能力通过宿主接口注入；app 在 `core/di/ltw` 组装依赖。退出当前房间调用 `leaveRoom()`；释放管理器实例时调用 `close()`。

Playback, song mapping, and platform capabilities are injected through host interfaces; app assembles them in `core/di/ltw`. Use `leaveRoom()` to exit the current room; call `close()` when disposing of the manager instance.

协议与服务端保持兼容。HTTP 响应上限为 2 MiB，WebSocket 文本上限为 2 × 1024 × 1024 个字符。共享音源只用于当前会话；远端控制使用 `REMOTE_SYNC`，避免重新向房间发送同一操作。

Keep the protocol compatible with the server. HTTP responses are limited to 2 MiB; WebSocket text to 2 × 1024 × 1024 characters. Shared sources are session-only. Remote controls use `REMOTE_SYNC` to prevent the same action being sent back to the room.

## 测试 / Tests

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
```
