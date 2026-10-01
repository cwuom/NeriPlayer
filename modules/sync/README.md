# 同步 / Sync

`:sync` 通过 GitHub 或 WebDAV 同步歌单、歌曲、历史与统计，负责传输、编解码、合并和冲突重试。共享模型在 `:model`，Android 仓库与 Worker 适配在 `:local`。

`:sync` synchronizes playlists, songs, history, and statistics through GitHub or WebDAV. It handles transport, codecs, merging, and conflict retries. Shared models live in `:model`; Android repository and Worker adapters are in `:local`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer`。

Sources are in `src/main/java/moe/ouom/neriplayer`.

- `api/sync`：GitHub/WebDAV 请求与响应 / GitHub/WebDAV requests and responses
- `data/sync/runtime/SyncSession.kt`：会话入口，通过 `SyncLocalDataStore` 与 `SyncBackend` 读写 / session entry point, accessing data through `SyncLocalDataStore` and `SyncBackend`
- `data/sync/codec`、`sanitize`、`change`、`merge`：编解码、清洗、变化检测与合并 / codecs, sanitization, change detection, and merging
- `data/sync/remote`、`retry`、`store`、`schedule`：远端版本、重试、凭据与调度 / remote versions, retries, credentials, and scheduling

GitHub 写入携带预期 head；WebDAV 使用 `If-None-Match`、ETag 或 Last-Modified 条件。缺少条件令牌时，先重读并确认远端指纹未变，再回退写入。凭据文件与键名、载荷格式和删除记录需保持升级兼容。

GitHub writes use an expected head; WebDAV uses `If-None-Match`, ETag, or Last-Modified conditions. Without a condition token, fallback writes require a reread confirming the remote fingerprint is unchanged. Credential files and keys, payload formats, and deletion records must remain compatible across upgrades.

## 测试 / Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
