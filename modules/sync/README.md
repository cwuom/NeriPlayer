# 🔄 同步 / Sync

`:sync` 通过 GitHub 或 WebDAV 同步歌单、歌曲、历史与统计，负责传输、编解码、合并和冲突重试。共享模型在 `:model`，Android 仓库与 Worker 适配在 `:local`。

`:sync` synchronizes playlists, songs, history, and statistics through GitHub or WebDAV. It handles transport, codecs, merging, and conflict retries. Shared models live in `:model`; Android repository and Worker adapters are in `:local`.

## 🧩 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer`。

Sources are in `src/main/java/moe/ouom/neriplayer`.

- `api/sync`：GitHub/WebDAV 请求与响应 / GitHub/WebDAV requests and responses
- `data/sync/archive`：v3 分块记录流、ZSTD、Merkle 校验与块缓存 / v3 chunked records, ZSTD, Merkle validation, and chunk cache
- `data/sync/runtime/SyncSession.kt`：会话入口，通过 `SyncLocalDataStore` 与 `SyncBackend` 读写 / session entry point, accessing data through `SyncLocalDataStore` and `SyncBackend`
- `data/sync/codec`、`sanitize`、`change`、`merge`：编解码、清洗、变化检测与合并 / codecs, sanitization, change detection, and merging
- `data/sync/remote`、`retry`、`store`、`schedule`：远端版本、重试、凭据与调度 / remote versions, retries, credentials, and scheduling

v3 的根清单是唯一发布点。GitHub 固定 HEAD 读取，最终通过 GraphQL `updateRefs` 的 `beforeOid` 与 `force=false` 原子更新引用。WebDAV 使用 `If-None-Match: *` 创建内容对象与首次清单，后续清单必须使用强 ETag 的 `If-Match`；缺少强条件时停止发布。普通网络歌词不传全文，用户编辑与重置有独立持久版本；旧来源未知的歌词保守迁移。本地凭据、设备身份与删除状态保留，旧载荷只用于读取迁移。

The v3 root manifest is the publication point. GitHub reads a fixed HEAD and atomically updates the ref through GraphQL `updateRefs` with `beforeOid` and `force=false`. WebDAV creates objects and the initial manifest with `If-None-Match: *`; later manifest writes require `If-Match` with a strong ETag. Publication stops when a strong condition is unavailable. Network lyric caches are omitted; user edits and resets have independent durable versions. Legacy lyrics with unknown provenance are preserved conservatively. Local credentials, device identities, and deletion state are preserved; legacy payloads are read only for migration.

默认使用 V3，空远端或已有 V3 数据直接同步。读取到合法的传统云端数据后，才要求用户声明所有参与同步的设备已更新；确认绑定同步地址与旧内容指纹，其他地址或内容变化不能沿用许可。未确认时不迁移、不应用同步数据，后台任务静默结束。启动弹窗和设置顶部警告只显示当前已配置地址的待升级状态，设置显示“同步数据库版本”；确认后继续原同步请求。新格式不向下兼容，清除配置或导入设置不会批准传统数据迁移。

V3 is enabled by default, and empty or existing V3 remotes sync directly. A valid traditional cloud payload requires confirmation that every participating device has been updated. Approval is bound to the target and exact legacy content fingerprint; another target or changed content requires a new confirmation. Pending migration does not apply local data, and background jobs finish quietly. Startup and settings warnings concern configured targets only, and settings display the sync database version. Confirmation resumes the original sync request. The new format is not backward compatible; clearing or importing configuration does not approve traditional data migration.

分块解决单报文容量限制，变化块复用减少增量流量；初次全库总流量不能保证 3 MB。Android 仓库和合并仍保留全量列表，真实百万、千万规模的全过程有界内存尚未完成。

Chunking removes the single-payload limit, and chunk reuse reduces incremental traffic. Initial full sync traffic is not guaranteed below 3 MB. Android repositories and merging still retain complete lists; bounded memory throughout real million- and ten-million-record workflows remains unfinished.

歌词同步说明 / Lyric sync details: [歌词 / Lyrics](LYRIC_SYNC.md)。

## 🧪 测试 / Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
