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

归档仓库初始化和创建工作目录时回收进程退出后遗留的规范 `sync-stage-UUID` 目录，包括旧版本未标记的目录。每个活跃目录持有独立文件锁，创建与回收另由稳定根锁保护，避免并发实例误删正在使用的原始记录；进程终止会自动释放锁。清理不跟随符号链接，不处理无关目录或保留的旧歌词来源，删除失败会保留所有权文件供后续重试。

Archive initialization and workspace creation reclaim canonical `sync-stage-UUID` directories abandoned by a terminated process, including unmarked older workspaces. Active workspaces hold individual file locks; a stable root lock also protects creation and recovery, preserving raw records used by concurrent repository instances. Process termination releases ownership automatically. Cleanup does not follow symbolic links or touch unrelated directories or retained lyric sources. Failed deletion preserves the ownership file for a later retry.

GitHub 固定 HEAD 读取，发布通过 GraphQL `updateRefs` 的 `beforeOid` 与 `force=false` 原子更新引用。新清单与引用对象在同一提交中发布；该提交同时移除根目录中不再引用的规范 V3/V4 内容对象，保留无关文件和旧 Git 历史。WebDAV 使用 `If-None-Match: *` 创建内容对象与首次清单，无锁更新清单必须使用强 ETag 的 `If-Match`，缺少强条件时停止发布。有限目录锁内先重新核对清单的内容指纹；已有强 ETag 时同时核对并提交 `If-Match`，防止忽略条件头的服务器覆盖其它设备的新发布。只有持有有限锁且指纹一致时，才允许缺少强 ETag 的服务器发布；对象清理仍要求强 ETag。支持有限租期独占目录 `Depth: infinity` 写锁的服务器上，完整读取、上传与发布都持锁，请求携带锁令牌并受实际租期限制；每次 WebDAV 发布带新 publicationId；仅当前完整闭包受保护，前代刚失去引用时重置候选年龄并开始新的七天观察。普通无变更读取也推进候选观察；只有候选到期时才条件刷新同一闭包的 publicationId，确认新的强 ETag 后删除，不重复上传内容对象。清理验证当前完整闭包，并按对象强 ETag 条件删除；目录、无关文件与传统备份不删除。服务器明确不支持 LOCK，或首次合法长租期锁已成功释放时，继续条件同步并保留旧对象；曾支持有限锁的目标失锁或能力降级会中止操作。生产读取验证远端完整对象闭包，本地缓存不能证明远端对象仍存在。

GitHub reads a fixed HEAD and publishes atomically with GraphQL `updateRefs`, `beforeOid`, and `force=false`. The new manifest, its objects, and deletion of unreferenced canonical V3/V4 root objects share one commit. Unrelated files and previous Git history are preserved. WebDAV creates objects and the initial manifest with `If-None-Match: *`, and requires a strong ETag with `If-Match` for unlocked updates. Under a finite collection lease, publication first rereads the manifest and checks its observed fingerprint, plus its strong ETag when available. This protects against servers that ignore conditional PUT headers. A matching fingerprint permits publication without a strong ETag only while holding the finite lease; garbage collection still requires strong ETags. Servers supporting finite exclusive depth-infinity collection leases protect complete reads and publication, with a positive lock condition on every request. Each WebDAV publication adds a fresh publicationId. Only the complete current closure is protected; newly retired objects start a fresh seven-day observation period. Unchanged reads also advance candidate observation. Eligible candidates trigger a conditional publicationId refresh for the same closure, and deletion requires a new strong root ETag. Content objects are not uploaded again. Collection validates the complete current closure and requires each object's strong ETag. Servers without LOCK, or whose first valid long-lived grant was successfully released, retain old objects while continuing conditional sync; a target with confirmed finite leases never silently downgrades. Production reads validate every referenced remote object; a local cache cannot establish remote availability.

WebDAV 的目录锁会短时阻止同目录其它写入者。实际租期必须有限且不超过请求的 300 秒；无限期或过长的锁会立即尝试释放并拒绝持有，降低进程退出后留下长期目录锁的风险。锁内请求不跟随重定向，防止规范对象操作跳转到其它文件。带 query 的地址保留原有路由和强 ETag 同步，不探测可选目录锁、不清理对象；曾确认有限锁能力的目标仍拒绝降级。fragment 不参与 HTTP 锁和维护状态的资源身份。七天宽限来自本机观察，不能证明其它设备两次观察之间的完整引用历史；时钟异常、重新引用或对象变化会重置候选年龄。旧版无锁读取可能在清理后遇到缺块，完整校验失败时不得应用部分数据；使用有限锁的新客户端整段读取持锁。普通清理失败保留并返回已校验或已经发布的版本，后续同步再重试，不回滚清单。完整上传成功后，若仅释放锁的网络或服务端错误使结果不确定，会尝试一次重新取得有限独占锁，核对原清单的强 ETag、指纹及全部远端引用对象，并成功释放新锁后才确认原发布；此过程不重新上传或清理。失锁、条件冲突、取消或未完成上述核对时不确认本次同步，保留已经发布的清单，之后重新读取再重试。

Collection locks briefly block other writers in the same WebDAV directory. The actual lease must be finite and no longer than the requested 300 seconds. Infinite or longer grants are released where possible and never held, to reduce the risk of long-lived locks after process termination. Locked requests do not follow redirects to other files. Query URLs retain their routing and strong ETag sync, without optional lease probing or collection; targets with confirmed finite leases still refuse to downgrade. Fragments do not participate in HTTP lock or maintenance identity. The grace period tracks local observations, rather than claiming a complete global reference history. Clock anomalies, renewed references, and changed objects reset candidate age. Older readers without leases may encounter missing old objects and must reject incomplete data; clients using finite leases hold them for their full remote read. Ordinary deferred maintenance returns the validated or newly published version and retries during a later sync. If a complete upload succeeds and only a network or server error during release leaves its outcome uncertain, one recovery attempt acquires a fresh finite exclusive lease, verifies the original manifest's strong ETag, fingerprint, and complete remote object closure, then successfully releases that lease before acknowledging the original publication. Recovery does not upload or collect objects. Lost leases, conditional conflicts, cancellation, or incomplete recovery leave the remote publication intact without acknowledging the sync; a later attempt must read the current version again.

新安装和空远端使用 V4。已有同步地址首次升级时显示一次升级确认；新增地址读取到合法传统载荷或 V3 时提示。迁移许可绑定地址、实际内容指纹和来源/目标版本；更换地址、内容或版本后重新确认。只有观察到该地址已使用 V4 才完成升级并移除提示，失败或取消仍可重试。升级完整保留旧歌词，历史有损开关不再参与快照、迁移和投影，不再显示常驻压缩选项。普通网络缓存仍按既有编辑状态语义省略，已编辑歌词和恢复版本独立持久保存。

New installations and empty targets use V4. Existing targets prompt once when updating; newly configured targets prompt after validated traditional or V3 data is found. Approval binds the target, actual content fingerprint, and source/destination versions. Changed content, targets, or versions require renewed approval. Migration completes and its prompt disappears only after V4 is observed for that target. Failures and cancellations remain retryable. Unknown legacy lyrics are preserved completely; the historical lossy preference is no longer consumed, and the permanent compression setting is removed. Ordinary known network caches retain their existing omission semantics, while edits and resets remain independently durable.

分块和内容复用减少增量流量，初次全库总流量没有统一上限或固定压缩比。播放统计使用磁盘暂存和分页处理，歌单、历史与歌词 registry 仍有列表成本。保留在内存的记录使用对称读写预算：主元数据与旧歌词候选合计最多 32 MiB 原始载荷和 131,072 个已知解码对象；真正分页的统计不计入总量，但每条记录仍有独立限制。超限明确失败，不截断历史或歌词，也不应用部分数据。物化为 SyncData 列表的统计使用相同总量限制。预算界定留存记录容量，整个 Android 合并流程的低堆内存行为仍需独立验证。

Chunking and content reuse reduce incremental traffic. Initial archive size and compression ratio depend on the data. Playback statistics use disk staging and pages, while playlists, history, and the lyric registry still have list costs. Symmetric read and write budgets limit retained main metadata and legacy candidates to 32 MiB of original payloads and 131,072 known decoded objects combined. Truly paged statistics are excluded from the aggregate but have independent per-record limits. Exceeding capacity fails explicitly without truncating history or lyrics or applying partial data. Statistics materialized into SyncData lists share the aggregate budget. These limits bound retained record capacity; low-heap behavior across the complete Android merge workflow still requires separate verification.

歌词同步说明 / Lyric sync details: [歌词 / Lyrics](LYRIC_SYNC.md)。

## 🧪 测试 / Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

### 🔌 本地 WebDAV 兼容测试 / Local WebDAV compatibility tests

真实服务测试使用独立临时目录，覆盖首次 V4 发布、冷缓存重开、V3 升级、歌词与分页统计完整性、陈旧发布拒绝，以及中文目录续租、锁竞争和释放后重取。服务必须绑定 loopback 并允许匿名访问；未配置环境变量时跳过这两类外部测试。

Real provider tests use isolated temporary collections to verify fresh V4 publication, cold-cache reopening, V3 migration, complete lyrics and paged statistics, stale publication rejection, encoded collection refresh, contention, and reacquisition after release. Providers must bind to loopback and allow anonymous access. These two external test classes are skipped when the environment variable is absent.

本地验证服务为 WsgiDAV 4.3.5 + Cheroot 11.1.2、rclone 1.75.1 和 Apache 2.4.67 mod_dav。WsgiDAV 的裸令牌、rclone 的十进制令牌和续租目录规范化、Apache 省略的 lockroot 与缺失或弱 ETag 均有回归覆盖；不接受任意相对令牌、错误锁根或无锁指纹更新。

The local matrix covers WsgiDAV 4.3.5 with Cheroot 11.1.2, rclone 1.75.1, and Apache 2.4.67 mod_dav. Regression coverage includes bare tokens, decimal tokens and refreshed collection normalization, optional lockroot, and absent or weak ETags. Arbitrary relative tokens, mismatched lock roots, and unlocked fingerprint-only updates remain rejected.

```bash
NERIPLAYER_WEBDAV_COMPAT_URLS='http://127.0.0.1:38091/,http://127.0.0.1:38090/,http://127.0.0.1:38092/' \
  ./gradlew :sync:testDebugUnitTest --tests '*WebDavProviderLeaseTest' \
  :local:testDebugUnitTest --tests '*WebDavProviderCompatibilityTest'
```
