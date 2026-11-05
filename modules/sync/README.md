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

## 归档协议 / Archive protocol

| 项目 | 规范 |
| --- | --- |
| 发布入口与标识 | 沿用 `neriplayer-sync-v3.manifest` 作为唯一发布文件名，内容封装标识为 `NPSYNC04`，数据对象使用 `neriplayer-sync-v4-` 前缀 |
| 版本校验 | 显式记录协议与 record schema 版本；缺失 V4 版本字段或遇到未知未来版本时，立即中止读取与发布 |
| 迁移与多端要求 | 传统 JSON/GZIP 与 V3 格式仅供只读迁移；旧客户端无法写入 V4，迁移前须确保共用同一同步目标的所有设备均已升级 |

| Item | Specification |
| --- | --- |
| Publication Entry & Prefix | Retains `neriplayer-sync-v3.manifest` as the sole publication filename with envelope `NPSYNC04` and object prefix `neriplayer-sync-v4-` |
| Version Validation | Stores explicit protocol and record schema versions; missing V4 version fields or unknown future versions abort both reads and publication |
| Migration & Device Requirement | Legacy JSON/GZIP and V3 formats are read-only for migration; older clients cannot write V4, so all devices sharing a target must be updated before migrating |

| 维度 | 规则与限制 |
| --- | --- |
| 编码与内容复用 | 元数据按有界块组织，歌词全文按内容寻址复用；数字、时间戳与文本编码均精确可逆 |
| 历史与特殊状态保留 | 保留同曲不同内容的旧歌词候选、可空字段、原词基线及历史日桶元数据 |
| Merkle 树与块大小上限 | 主记录、旧歌词候选与正文池分别构建独立 Merkle 树；数据块解压上限 4 MiB、索引上限 128 KiB、网络对象上限 2 MiB，不可压缩块继续拆分 |
| 完整性与临时目录 | 解包后的原始记录流须通过完整 SHA-256 校验；操作完成、失败或取消时均自动清理临时工作目录 |

| Aspect | Rules and Limits |
| --- | --- |
| Encoding & Content Reuse | Organizes metadata into bounded blocks and deduplicates lyric text by content address; numeric, timestamp, and text encodings are exactly reversible |
| Preserved States | Retains distinct legacy lyric variants for the same song identity, nullable fields, original baselines, and historical daily-bucket metadata |
| Merkle Trees & Block Limits | Builds separate Merkle trees for main records, legacy candidates, and the text pool; caps decoded data blocks at 4 MiB, indexes at 128 KiB, and wire objects at 2 MiB, splitting incompressible blocks further |
| Stream Integrity & Cleanup | Unpacked raw record streams must pass full SHA-256 verification; temporary workspaces are cleaned up on completion, failure, or cancellation |

新对象使用 ZSTD level 6 降低本地压缩耗时，V3/V4 的窗口限制和帧校验保持不变。已有 level 19 缓存继续校验原始内容哈希并复用；缺失或损坏块按当前设置重建。压缩级别不改变无损协议，完整数据仍需通过原始哈希和记录流校验。

New objects use ZSTD level 6 to reduce local compression time, preserving the V3/V4 window limits and frame checksums. Existing level 19 cache objects still pass raw-content hash validation before reuse; missing or corrupt blocks are rebuilt with the current setting. The compression level does not change the lossless protocol, and raw hashes and complete record-stream checks remain required.

## 读取与缓存 / Reads and caching

V4 读取先按顺序验证索引、叶子数量和字节预算，再每批最多并发下载四个数据对象。同一读取中的重复对象复用已校验内容，记录顺序保持不变；生产读取仍验证所有远端引用对象。取消会中止归档 GET 与缺失目录探测的实际 HTTP 请求。WebDAV 续租和租约状态使用共享锁保护，网络读取与正文解析在锁外执行；GitHub 同一冷却期内的并发限流响应只计作一轮，冷却到期后的新轮仍遵守自动重试上限。

V4 reads validate indexes, leaf counts, and byte budgets in order before downloading at most four data objects per batch. Repeated objects reuse verified content within the read, preserving record order; production reads still verify every referenced remote object. Cancellation aborts the actual archive GET and missing-directory probe HTTP calls. WebDAV synchronizes lease renewal and state while keeping network reads and body parsing outside the lock. Concurrent GitHub rate-limit responses share a cooldown round; new rounds after expiration still observe the automatic retry limit.

| 环节 | 行为 |
| --- | --- |
| 分支与根树解析 | 每轮同步重新解析默认分支，从同一次分支响应获取 commit 与 root tree OID，再读取完整根文件树 |
| 本地块缓存校验 | 本地缓存块须先通过压缩长度与 SHA-256 校验，再与完整根树中普通 blob 的 Git OID 比对；匹配则直接复用（省去逐块 GET 与重复 `fsync`），缺失、损坏或 OID 变化则重新下载并核对响应 OID |
| 树证明复用与垃圾清理 | 同一轮上传仅在 API 地址、仓库、分支与 commit 完全一致时复用该完整树证明，不一致则重读；对象清理基于完整根树（而非仅看清单引用子集），树被截断、缺失引用对象或正文不符均直接失败，同时保留全部解压、原始哈希与完整记录流校验 |
| 请求开销 | 平铺归档在热缓存且无变更时需 4 次读取请求，少量修改通常增加 1 次发布请求；实际总耗时取决于网络状况与本地数据规模 |

| Stage | Behavior |
| --- | --- |
| Branch & Root Tree Resolution | Resolves the default branch on every round, extracts the commit and root tree OIDs from that same response, and fetches the complete root tree |
| Local Chunk Cache Validation | Cached chunks must pass compressed-length and SHA-256 checks before matching against a regular blob's Git OID in the root tree; hits skip per-object GETs and repeated `fsync` calls, while missing, corrupt, or changed entries are downloaded and verified against the tree OID |
| Tree Proof & Garbage Collection | Publication reuses the round's complete tree proof only when the API base, repository, branch, and commit match; collection inspects all owned root objects rather than just manifest references, failing on truncated trees, missing objects, or body mismatches while keeping all decompression, raw-hash, and stream checks |
| Request Overhead | A warm flat archive takes 4 read requests when unchanged, and a small edit typically adds 1 publication request; total latency depends on network conditions and local dataset size |

归档仓库初始化和创建工作目录时回收进程退出后遗留的规范 `sync-stage-UUID` 目录，包括旧版本未标记的目录。每个活跃目录持有独立文件锁，创建与回收另由稳定根锁保护，避免并发实例误删正在使用的原始记录；进程终止会自动释放锁。清理不跟随符号链接，不处理无关目录或保留的旧歌词来源，删除失败会保留所有权文件供后续重试。

Archive initialization and workspace creation reclaim canonical `sync-stage-UUID` directories abandoned by a terminated process, including unmarked older workspaces. Active workspaces hold individual file locks; a stable root lock also protects creation and recovery, preserving raw records used by concurrent repository instances. Process termination releases ownership automatically. Cleanup does not follow symbolic links or touch unrelated directories or retained lyric sources. Failed deletion preserves the ownership file for a later retry.

## 发布与并发控制 / Publication and concurrency

### GitHub 发布 / GitHub publication

| 路径 / 环节 | 行为与约束 |
| --- | --- |
| 快速路径（单次提交） | 读取固定 HEAD；当新增与删除合计不超过 64 项、原始内容不超过 3 MiB 且完整 UTF-8 JSON 不超过 4 MiB 时，调用 GraphQL `createCommitOnBranch`（附带 `expectedHeadOid`）单次原子提交二进制对象、清单与清理 |
| 分阶段上传路径 | 超出客户端快速路径预算时，在发送任何写请求前切换为分阶段上传，最后通过 `updateRefs`（`beforeOid`、`force=false`）原子更新引用；已发出快速提交后若遇报错、取消或响应不完整，不会回退至另一写入流程 |
| 清理范围 | 新清单、引用对象及根目录内未引用的规范 V3/V4 内容对象在同一提交中完成发布与移除，保留无关文件与既有 Git 历史 |

| Path / Stage | Behavior and Constraints |
| --- | --- |
| Fast Path (Single Commit) | Reads a fixed HEAD; when additions plus deletions total at most 64, raw content is at most 3 MiB, and full UTF-8 JSON is at most 4 MiB, publishes binary objects, the manifest, and cleanup in one GraphQL `createCommitOnBranch` mutation guarded by `expectedHeadOid` |
| Staged Upload Path | Archives exceeding client fast-path budgets switch to staged uploads before sending any write request and finalize atomically via `updateRefs` (`beforeOid`, `force=false`); errors, cancellation, or incomplete responses after dispatching a fast commit never fall back to another write flow |
| Cleanup Scope | Publishes the new manifest, its referenced objects, and removal of unreferenced canonical V3/V4 root objects in the same commit while preserving unrelated files and Git history |

### WebDAV 发布与清理 / WebDAV publication and garbage collection

| 场景 | 行为与约束 |
| --- | --- |
| 对象创建与读取 | 内容对象与首次清单使用 `If-None-Match: *` 创建；读取归档强制使用未压缩响应（identity encoding），防止 nginx 等服务端因压缩将强 ETag 降级为弱 ETag |
| 条件发布与无引号 ETag | 每次发布前重读清单并核对内容指纹；若有强 ETag 则同时核对该 ETag 并提交 `If-Match`。对坚果云等返回无引号 ETag 的无锁服务端，原样将 ETag 填入 `If-Match` 交由服务端拦截并发；返回 `412` 时按冲突处理，不退回无条件写入 |
| 无可用 ETag 的更新 | 无有限锁且没有可用于 `If-Match` 的 ETag 时，在上传任何对象前拒绝更新已有清单，保留本地与远端数据；首次创建仍使用 `If-None-Match: *`。发布后的回读可发现部分覆盖，不能替代服务端原子条件写入 |
| 有限独占目录锁与 7 天观察 | 支持有限租期独占 `Depth: infinity` 写锁的服务端在完整读取、上传、发布期间全程持锁，每个请求携带锁令牌。每次发布生成新 `publicationId`，仅保护当前完整闭包；前代对象刚失去引用时重置候选年龄并启动 7 天本地观察，无变更读取也会推进观察 |
| 到期条件清理 | 仅在持有有限锁且候选满 7 天后，先在不重传内容对象的前提下条件刷新同一闭包的 `publicationId`，确认获得新强 ETag 后，再核对当前完整闭包并按各对象的强 ETag 条件删除规范对象，每批最多 32 个；本地最多跟踪 1,024 个候选；目录、无关文件与传统备份永不删除 |
| 无锁兼容与拒绝降级 | 尚未确认有限锁能力的目标，在不支持 `LOCK`（返回 `405`、`501` 或 `403`，如坚果云），或初次获授无限期或超过 300 秒的锁并成功释放时，仅在有可用 ETag 时更新已有清单并保留旧对象；曾确认支持有限锁的目标一旦失锁或能力降级则立即中止操作 |
| 远端闭包验证 | 生产读取强制验证远端完整对象闭包，本地缓存命中不能替代远端对象存在性证明 |

| Scenario | Behavior and Constraints |
| --- | --- |
| Creation & Identity Encoding | Creates content objects and the initial manifest with `If-None-Match: *`; requests archives with identity encoding so servers like nginx do not weaken strong ETags via compression |
| Conditional Put & Unquoted ETags | Rereads the manifest before every publication to verify its content fingerprint and checks the strong ETag and submits it in `If-Match` when available. Unlocked servers returning unquoted ETags (such as Nutstore) receive that verbatim ETag in `If-Match`; a `412` remains a conflict and never falls back to an unconditional write |
| Updates Without Usable ETags | Without a finite lease or an ETag usable in `If-Match`, rejects updates to existing manifests before uploading any objects and retains local and remote data. Initial creation still uses `If-None-Match: *`. A post-publication read can detect some overwrites but cannot replace atomic conditional writes on the server |
| Finite Exclusive Leases & 7-Day Window | Servers supporting finite exclusive `Depth: infinity` collection locks hold the lease across full reads, uploads, and publication with lock tokens on every request. Each publication assigns a fresh `publicationId` protecting only the current closure; newly unreferenced objects reset their candidate age and start a 7-day local observation window advanced by both writes and unchanged reads |
| Conditional Garbage Collection | With a finite lease held and mature candidates, refreshes the same closure's `publicationId` conditionally without re-uploading content objects, confirms a new strong root ETag, re-validates the complete closure, and deletes up to 32 canonical objects per batch using each object's strong ETag; the local journal tracks at most 1,024 candidates; directories, unrelated files, and legacy backups are never deleted |
| Lockless Targets & No Downgrade | Targets without confirmed finite leases update existing manifests only with usable ETags and retain old objects if they reject `LOCK` (`405`, `501`, or `403`, e.g., Nutstore) or their initial infinite or oversized grant was cleanly released; targets with confirmed finite leases abort on lease loss or capability downgrade |
| Remote Closure Verification | Production reads verify the complete remote object closure; local cache hits never prove remote availability |

若现有服务既不提供有限目录锁，也没有服务端支持的条件 ETag，更新同步会明确报错。可改用支持这两种机制之一的 WebDAV 服务，或切换 GitHub 同步；切换前先导出本地备份，并在新目标完成同步后再处理旧目标。有限锁下仍兼容弱或缺失 ETag；仅靠避免同时手动同步不能为无锁写入提供保证。

Existing servers without either finite collection locks or server-supported conditional ETags report an explicit update error. Use a WebDAV server supporting either mechanism or switch to GitHub sync; export a local backup before switching and complete sync with the new target before retiring the old one. Finite leases still support weak or absent ETags; manually avoiding simultaneous sync does not guarantee safe unlocked writes.

| 机制 | 行为与边界 |
| --- | --- |
| 租期上限与释放 | 目录锁会短时阻塞同目录其他写入方；实际授予租期必须有限且不超过 300 秒，无限期或超过 300 秒的锁会立即尝试释放并拒绝持有，降低进程异常退出遗留长期目录锁的风险 |
| 重定向与 URL 规范 | 持锁请求禁止跟随重定向；带 `query` 的地址保留既有路由与强 ETag 同步，不探测目录锁也不清理对象（但已确认支持有限锁的目标仍拒绝降级）；`fragment` 不参与 HTTP 锁与维护状态的资源身份计算 |
| 本机观察与无锁读取边界 | 7 天宽限期基于本机观察，不代表跨设备全局引用历史；时钟异常、对象被重新引用或内容变化均会重置候选年龄。使用有限锁的客户端整段读取全程持锁；旧版无锁读取在清理后若遇到缺块，完整校验会直接拒绝应用部分数据 |
| 清理失败与解锁恢复 | 普通延迟清理失败时保留并返回已校验或已发布的版本，留待后续同步重试，不回滚清单。若完整上传成功后仅在释放锁阶段因网络或服务端错误导致结果不确定，客户端会尝试一次重新获取有限独占锁，核对原清单的强 ETag、指纹及全部远端引用对象并成功释放新锁后才确认发布（期间不重复上传或清理）；若失锁、条件冲突、取消或核对未完成，则不确认本次同步，保留远端已发布清单并在下次同步重读 |

| Mechanism | Behavior and Boundaries |
| --- | --- |
| Lease Cap & Release | Collection locks briefly block other writers in the same directory; granted leases must be finite and at most 300 seconds, while infinite or longer grants are immediately released where possible and never held |
| Redirects & URL Identity | Locked requests never follow redirects; URLs with `query` strings keep their routing and strong ETag sync without probing locks or collecting objects (targets with confirmed finite leases still refuse to downgrade); URL `fragment`s are excluded from lock and maintenance resource identity |
| Local Observation & Reader Safety | The 7-day grace window reflects local observations rather than global cross-device history; clock anomalies, renewed references, or object changes reset candidate age. Lease-capable clients hold a finite lock across the entire read, whereas older lockless readers encountering collected blocks fail full verification and reject partial data |
| Maintenance Failure & Unlock Recovery | Ordinary cleanup failures return the validated or newly published version and retry on a later sync without rolling back the manifest. If a full upload succeeds and only lock release fails with a network or server error, one recovery attempt acquires a fresh finite exclusive lease, verifies the original manifest's strong ETag, fingerprint, and complete remote object closure, and releases the new lease before acknowledging publication (without re-uploading or collecting); lease loss, conflicts, cancellation, or incomplete verification leave the remote manifest intact without acknowledging the sync |

## 升级与容量 / Upgrade and capacity

新安装和空远端使用 V4。已有同步地址首次升级时显示一次升级确认；新增地址读取到合法传统载荷或 V3 时提示。迁移许可绑定地址、实际内容指纹和来源/目标版本；更换地址、内容或版本后重新确认。只有观察到该地址已使用 V4 才完成升级并移除提示，失败或取消仍可重试。升级完整保留旧歌词，历史有损开关不再参与快照、迁移和投影，不再显示常驻压缩选项。普通网络缓存仍按既有编辑状态语义省略，已编辑歌词和恢复版本独立持久保存。

New installations and empty targets use V4. Existing targets prompt once when updating; newly configured targets prompt after validated traditional or V3 data is found. Approval binds the target, actual content fingerprint, and source/destination versions. Changed content, targets, or versions require renewed approval. Migration completes and its prompt disappears only after V4 is observed for that target. Failures and cancellations remain retryable. Unknown legacy lyrics are preserved completely; the historical lossy preference is no longer consumed, and the permanent compression setting is removed. Ordinary known network caches retain their existing omission semantics, while edits and resets remain independently durable.

| 维度 | 预算与限制 |
| --- | --- |
| 流量与存储结构 | 分块与内容寻址复用可减少增量同步流量，首次全库体积与压缩比取决于实际数据；播放统计采用磁盘暂存与分页处理，歌单、历史与歌词 registry 仍保有列表开销 |
| 对称留存记录预算 | 读写共用对称的留存记录上限：主元数据与旧歌词候选合计最多 32 MiB 原始载荷及 131,072 个已知解码对象 |
| 分页统计与列表物化 | 真正分页处理的统计不计入上述聚合总额（单条记录仍有独立上限）；但物化为 `SyncData` 列表的统计共享该聚合预算 |
| 超限行为与内存边界 | 超出容量预算时直接报错中止，不截断历史或歌词，也不应用部分数据；该预算仅约束留存记录容量，完整 Android 合并流程在低堆内存设备上的表现仍需独立验证 |

| Aspect | Budgets and Limits |
| --- | --- |
| Traffic & Storage Model | Chunking and content-addressed reuse reduce incremental traffic, while initial archive size and compression ratio depend on the dataset; playback statistics use disk staging and pagination, whereas playlists, history, and the lyric registry retain in-memory list costs |
| Symmetric Retained-Record Budget | Read and write paths enforce a symmetric retained-record budget: main metadata and legacy lyric candidates combined cannot exceed 32 MiB of raw payloads or 131,072 decoded objects |
| Paged vs. Materialized Stats | Truly paged statistics are excluded from the aggregate budget (while keeping per-record limits), whereas statistics materialized into `SyncData` lists share the aggregate cap |
| Overflow & Heap Boundaries | Exceeding capacity fails explicitly without truncating history or lyrics or applying partial data; these budgets bound retained record capacity, while low-heap behavior across the full Android merge workflow still requires separate verification |

歌词同步说明 / Lyric sync details: [歌词 / Lyrics](LYRIC_SYNC.md)。

## 🧪 测试 / Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

`SyncArchiveScaleTest` 默认跳过；显式设置 `-DrunSyncScale=true` 才运行百万歌单成员的容量拒绝和千万统计记录的归档测试。这些测试只验证归档层，不能替代完整 Android 同步流程的内存验证。

`SyncArchiveScaleTest` is skipped by default. Set `-DrunSyncScale=true` explicitly to test capacity rejection for a million playlist members and archiving ten million statistics records. These tests cover the archive layer and do not replace memory validation of the complete Android sync flow.

```bash
./gradlew :sync:testDebugUnitTest --tests '*SyncArchiveScaleTest' -DrunSyncScale=true
```

### 🔌 本地 WebDAV 兼容测试 / Local WebDAV compatibility tests

真实服务测试使用独立临时目录，覆盖首次 V4 发布、冷缓存重开、V3 升级、歌词与分页统计完整性、陈旧发布拒绝，以及中文目录续租、锁竞争和释放后重取。服务必须绑定 loopback 并允许匿名访问，地址不能带凭据、query 或 fragment。租约测试只读取 `NERIPLAYER_WEBDAV_COMPAT_URLS`；完整同步测试同时读取该变量和 `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`，没有配置对应地址时跳过。

Real provider tests use isolated temporary collections to verify fresh V4 publication, cold-cache reopening, V3 migration, complete lyrics and paged statistics, stale publication rejection, encoded collection refresh, contention, and reacquisition after release. Providers must bind to loopback and allow anonymous access, with no credentials, query, or fragment in their URLs. Lease tests read only `NERIPLAYER_WEBDAV_COMPAT_URLS`; full sync tests also read `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`. Tests without configured targets are skipped.

本地验证服务为 WsgiDAV 4.3.5 + Cheroot 11.1.2、rclone 1.75.1 和 Apache 2.4.67 mod_dav。WsgiDAV 的裸令牌、rclone 的十进制令牌和续租目录规范化、Apache 省略的 lockroot 与有限锁下缺失或弱 ETag 均有回归覆盖；不接受任意相对令牌或错误锁根。拒绝 LOCK 的服务（例如坚果云）放在 `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`，验证有可用 ETag 的条件同步；缺失或弱 ETag 拒绝更新及条件冲突不降级由确定性交错回归覆盖。

The local matrix covers WsgiDAV 4.3.5 with Cheroot 11.1.2, rclone 1.75.1, and Apache 2.4.67 mod_dav. Regression coverage includes bare tokens, decimal tokens and refreshed collection normalization, optional lockroot, and absent or weak ETags under finite leases. Arbitrary relative tokens and mismatched lock roots remain rejected. Providers that refuse LOCK, such as Nutstore, go in `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS` to verify conditional sync with usable ETags; deterministic interleaving regressions cover rejected updates without usable ETags and conflicts that must not downgrade to unconditional writes.

```bash
NERIPLAYER_WEBDAV_COMPAT_URLS='http://127.0.0.1:38091/,http://127.0.0.1:38090/,http://127.0.0.1:38092/' \
  ./gradlew :sync:testDebugUnitTest --tests '*WebDavProviderLeaseTest' \
  :local:testDebugUnitTest --tests '*WebDavProviderCompatibilityTest'
```
