# 🔄 同步 / Sync

`:sync` 通过 GitHub 或 WebDAV 同步歌单、歌曲、历史与统计，负责传输、编解码、合并和冲突重试。共享模型在 `:model`，Android 仓库与 Worker 适配在 `:local`。

`:sync` synchronizes playlists, songs, history, and statistics through GitHub or WebDAV. It handles transport, codecs, merging, and conflict retries. Shared models live in `:model`; Android repository and Worker adapters are in `:local`.

## 🧩 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer`。

Sources are in `src/main/java/moe/ouom/neriplayer`.

- `api/sync`：GitHub/WebDAV 请求与响应 / GitHub/WebDAV requests and responses
- `data/sync/archive`：V4 可逆记录压缩、ZSTD、Merkle 校验与块缓存 / V4 reversible record packing, ZSTD, Merkle validation, and chunk cache
- `data/sync/runtime/SyncSession.kt`：会话入口，通过 `SyncLocalDataStore` 与 `SyncBackend` 读写 / session entry point, accessing data through `SyncLocalDataStore` and `SyncBackend`
- `data/sync/codec`、`sanitize`、`change`、`merge`：编解码、清洗、变化检测与合并 / codecs, sanitization, change detection, and merging
- `data/sync/remote`、`retry`、`store`、`schedule`：远端版本、重试、凭据与调度 / remote versions, retries, credentials, and scheduling

V4 使用明确的协议和记录 schema 版本。`neriplayer-sync-v3.manifest` 文件名继续作为唯一发布点，内容标识为 `NPSYNC04`，对象使用 `neriplayer-sync-v4-` 前缀。传统 JSON/GZIP 与 V3 仅供读取迁移，V4 版本字段缺失或出现未知未来版本会中止读取和发布。旧客户端不能写入 V4，升级前必须确认使用该地址的所有设备都已更新。

V4 stores explicit protocol and record schema versions. The existing `neriplayer-sync-v3.manifest` filename remains the only publication point; its envelope is `NPSYNC04`, and object names use `neriplayer-sync-v4-`. Traditional JSON/GZIP and V3 are read only for migration. Missing V4 version fields or unsupported future versions abort reading and publication. All devices using a target must be updated before migration.

压缩将记录元数据按有界块组织，以内容地址复用歌词全文；时间戳、数字和文字编码均可精确还原。歌曲身份相同但内容不同的旧歌词版本、空值、原词基线和历史日桶元数据全部保留。主记录、旧歌词候选和正文池分别建立 Merkle 树，数据块解压预算为 4 MiB，索引为 128 KiB，网络对象最多 2 MiB；不可压缩块会继续拆分。解包后的原始记录流还必须通过完整 SHA-256 校验。准备、读取失败或取消会清理临时工作目录。

Packing uses bounded metadata blocks and content addresses for lyric text. Numeric, timestamp, and text encodings are exactly reversible. Distinct legacy lyric variants, nullable fields, baselines, and historical bucket metadata are preserved. Main records, legacy candidates, and the text pool have separate Merkle trees. Decoded data blocks are limited to 4 MiB, indexes to 128 KiB, and wire objects to 2 MiB; incompressible blocks are split further. Unpacked original streams also require complete SHA-256 verification. Temporary workspaces are released on completion, failure, and cancellation.

GitHub 固定 HEAD 读取，发布通过 GraphQL `updateRefs` 的 `beforeOid` 与 `force=false` 原子更新引用。新清单与引用对象在同一提交中发布；该提交同时移除根目录中不再引用的规范 V3/V4 内容对象，保留无关文件和旧 Git 历史。WebDAV 使用 `If-None-Match: *` 创建内容对象与首次清单，后续清单必须使用强 ETag 的 `If-Match`，缺少强条件时停止发布。支持有限租期独占目录 `Depth: infinity` 写锁的服务器上，完整读取、上传与发布都持锁，逐请求验证锁令牌和实际租期；每次 WebDAV 发布带新 publicationId，随后才可清理本机连续观测至少七天未引用的规范内容对象。清理还验证当前完整闭包、保护本次读取和发布的闭包，并按对象强 ETag 条件删除；目录、无关文件与传统备份不删除。服务器明确不支持 LOCK，或首次合法长租期锁已成功释放时，继续条件同步并保留旧对象；曾支持有限锁的目标失锁或能力降级会中止操作。生产读取验证远端完整对象闭包，本地缓存不能证明远端对象仍存在。

GitHub reads a fixed HEAD and publishes atomically with GraphQL `updateRefs`, `beforeOid`, and `force=false`. The new manifest, its objects, and deletion of unreferenced canonical V3/V4 root objects share one commit. Unrelated files and previous Git history are preserved. WebDAV creates objects and the initial manifest with `If-None-Match: *`, and requires a strong ETag with `If-Match` for later publication. Servers supporting finite exclusive depth-infinity collection leases protect complete reads and publication, with a positive lock condition on every request. Each WebDAV publication adds a fresh publicationId before optional collection of canonical objects observed locally as unreferenced for at least seven days. Collection validates the current closure, protects both the previous and published closures, and requires each object's strong ETag. Servers without LOCK, or whose first valid long-lived grant was successfully released, retain old objects while continuing conditional sync; a target with confirmed finite leases never silently downgrades. Production reads validate every referenced remote object; a local cache cannot establish remote availability.

WebDAV 的目录锁会短时阻止同目录其它写入者。实际租期必须有限且不超过请求的 300 秒；无限期或过长的锁会立即尝试释放并拒绝持有，降低进程退出后留下长期目录锁的风险。锁内请求不跟随重定向，防止规范对象操作跳转到其它文件。带 query 的地址保留原有路由和强 ETag 同步，不探测可选目录锁、不清理对象；曾确认有限锁能力的目标仍拒绝降级。fragment 不参与 HTTP 锁和维护状态的资源身份。七天宽限来自本机观察，不能证明其它设备两次观察之间的完整引用历史；时钟异常、重新引用或对象变化会重置候选年龄。旧版无锁读取可能在清理后遇到缺块，完整校验失败时不得应用部分数据；使用有限锁的新客户端整段读取持锁。普通清理失败保留并返回已经发布的版本，后续成功发布再重试，不回滚清单；失锁、条件冲突或释放锁失败则不确认本次同步，远端已经发布的清单仍保留，之后重新读取再重试。

Collection locks briefly block other writers in the same WebDAV directory. The actual lease must be finite and no longer than the requested 300 seconds. Infinite or longer grants are released where possible and never held, to reduce the risk of long-lived locks after process termination. Locked requests do not follow redirects to other files. Query URLs retain their routing and strong ETag sync, without optional lease probing or collection; targets with confirmed finite leases still refuse to downgrade. Fragments do not participate in HTTP lock or maintenance identity. The grace period tracks local observations, rather than claiming a complete global reference history. Clock anomalies, renewed references, and changed objects reset candidate age. Older readers without leases may encounter missing old objects and must reject incomplete data; clients using finite leases hold them for their full remote read. Ordinary deferred maintenance returns the published version and retries after a later publication. Lost leases, conditional conflicts, and failed unlocks leave the remote publication intact but do not acknowledge the sync; a later attempt must read the current version again.

新安装和空远端使用 V4。已有同步地址首次升级时显示一次升级确认；新增地址读取到合法传统载荷或 V3 时提示。迁移许可绑定地址、实际内容指纹和来源/目标版本；更换地址、内容或版本后重新确认。只有观察到该地址已使用 V4 才完成升级并移除提示，失败或取消仍可重试。升级完整保留旧歌词，历史有损开关不再参与快照、迁移和投影，不再显示常驻压缩选项。普通网络缓存仍按既有编辑状态语义省略，已编辑歌词和恢复版本独立持久保存。

New installations and empty targets use V4. Existing targets prompt once when updating; newly configured targets prompt after validated traditional or V3 data is found. Approval binds the target, actual content fingerprint, and source/destination versions. Changed content, targets, or versions require renewed approval. Migration completes and its prompt disappears only after V4 is observed for that target. Failures and cancellations remain retryable. Unknown legacy lyrics are preserved completely; the historical lossy preference is no longer consumed, and the permanent compression setting is removed. Ordinary known network caches retain their existing omission semantics, while edits and resets remain independently durable.

分块和内容复用减少增量流量，初次全库总流量没有统一上限或固定压缩比。播放统计使用磁盘暂存和分页处理，歌单、历史与歌词 registry 仍有列表成本；真实百万、千万规模整个业务流程的有界内存尚未验证。

Chunking and content reuse reduce incremental traffic. Initial archive size and compression ratio depend on the data. Playback statistics use disk staging and pages, while playlists, history, and the lyric registry still have list costs. Bounded memory across the complete real million- or ten-million-record workflow has not been established.

歌词同步说明 / Lyric sync details: [歌词 / Lyrics](LYRIC_SYNC.md)。

## 🧪 测试 / Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
