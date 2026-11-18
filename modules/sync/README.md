# 🔄 同步 / Sync

[English](#english) | [中文](#中文)

## 中文

`:sync` 通过 GitHub 或 WebDAV 同步歌单、歌曲、历史与统计，负责传输、编解码、合并和冲突重试。共享模型在 `:model`，Android 仓库与 Worker 适配在 `:local`。

### 🧩 结构

源码位于 `src/main/java/moe/ouom/neriplayer`：

- `api/sync`：GitHub/WebDAV 请求与响应
- `data/sync/archive`：V4 可逆记录压缩、ZSTD、Merkle 校验与块缓存
- `data/sync/runtime/SyncSession.kt`：会话入口，通过 `SyncLocalDataStore` 与 `SyncBackend` 读写
- `data/sync/codec`、`sanitize`、`change`、`merge`：编解码、清洗、变化检测与合并
- `data/sync/remote`、`retry`、`store`、`schedule`：远端版本、重试、凭据与调度

### 📦 归档协议

#### 发布与版本

- 发布文件名：`neriplayer-sync-v3.manifest`，是唯一的发布入口
- 内容封装标识：`NPSYNC04`
- 数据对象前缀：`neriplayer-sync-v4-`
- 显式记录协议与 record schema 版本
- 缺失 V4 版本字段或遇到未知未来版本，立即中止读取与发布
- 传统 JSON/GZIP 与 V3 格式仅供只读迁移
- 旧客户端无法写入 V4，迁移前须升级共用同一同步目标的所有设备

#### 编码与内容复用

- 元数据按有界块组织
- 歌词全文按内容寻址复用
- 数字、时间戳与文本编码均精确可逆
- 保留同曲不同内容的旧歌词候选、可空字段、原词基线与历史日桶元数据

#### Merkle 树与块大小上限

- 主记录、旧歌词候选与正文池分别构建独立 Merkle 树
- 数据块解压上限 4 MiB，索引上限 128 KiB
- 网络对象上限 2 MiB，不可压缩块继续拆分

#### 完整性与临时目录

- 解包后的原始记录流须通过完整 SHA-256 校验
- 完成、失败或取消时，自动清理临时工作目录

#### 压缩级别

- 新对象使用 ZSTD level 6，降低本地压缩耗时
- V3/V4 沿用同一窗口限制和帧校验
- 已有 level 19 缓存校验原始内容哈希后复用
- 缺失或损坏的块按当前设置重建
- 压缩级别不影响无损协议，仍须通过原始哈希和记录流校验

### 📥 读取与缓存

#### V4 读取流程

- 先按顺序验证索引、叶子数量和字节预算
- 每批最多并发下载 4 个数据对象
- 同一读取中的重复对象复用已校验内容，记录顺序不变
- 生产读取验证所有远端引用对象
- 取消会中止归档 GET 与缺失目录探测的实际 HTTP 请求
- WebDAV 续租与租约状态由共享锁保护，网络读取与正文解析在锁外执行
- GitHub 同一冷却期内的并发限流响应只计一轮，新一轮仍遵守自动重试上限

#### 分支与根树解析

- 每轮同步重新解析默认分支
- 从同一次分支响应获取 commit 与 root tree OID
- 读取完整根文件树

#### 本地块缓存校验

- 缓存块先通过压缩长度与 SHA-256 校验
- 再与根树中普通 blob 的 Git OID 比对
- 匹配则直接复用，省去逐块 GET 与重复 `fsync`
- 缺失、损坏或 OID 变化的块重新下载，并核对响应 OID

#### 树证明复用与垃圾清理

- 同一轮上传仅在 API 地址、仓库、分支与 commit 全部一致时复用树证明，否则重读
- 对象清理基于完整根树，而非清单引用的子集
- 树被截断、缺失引用对象或正文不符时直接失败
- 全程执行解压、原始哈希与完整记录流校验

#### 请求开销

- 热缓存且无变更的平铺归档需 4 次读取请求
- 少量修改增加 1 次发布请求
- 总耗时取决于网络状况与本地数据规模

#### 临时目录回收

- 归档仓库初始化和创建工作目录时，回收进程退出后遗留的规范 `sync-stage-UUID` 目录，含旧版本未标记的目录
- 每个活跃目录持有独立文件锁，创建与回收由稳定根锁保护
- 并发实例使用中的原始记录不会被误删
- 进程终止时自动释放锁
- 清理不跟随符号链接，不处理无关目录或保留的旧歌词来源
- 删除失败时保留所有权文件，供后续重试

### 🚀 发布与并发控制

#### GitHub 快速路径（单次提交）

- 读取固定 HEAD
- 新增与删除合计不超过 64 项、原始内容不超过 3 MiB、完整 UTF-8 JSON 不超过 4 MiB 时走此路径
- 调用 GraphQL `createCommitOnBranch`，附带 `expectedHeadOid`
- 单次原子提交二进制对象、清单与清理

#### GitHub 分阶段上传

- 超出快速路径预算时，发送任何写请求前切换为分阶段上传
- 最后通过 `updateRefs`（`beforeOid`、`force=false`）原子更新引用
- 快速提交发出后若报错、取消或响应不完整，不切换到其他写入流程

#### GitHub 清理范围

- 同一提交内发布新清单与引用对象
- 同一提交内移除根目录中未引用的规范 V3/V4 内容对象
- 保留无关文件与既有 Git 历史

#### WebDAV 对象创建与读取

- 内容对象与首次清单使用 `If-None-Match: *` 创建
- 读取归档强制 identity encoding，避免 nginx 等服务端因压缩将强 ETag 降为弱 ETag

#### WebDAV 条件发布与无引号 ETag

- 每次发布前重读清单并核对内容指纹
- 有强 ETag 时同时核对，并提交 `If-Match`
- 坚果云等无锁服务端返回无引号 ETag，原样填入 `If-Match`，由服务端拦截并发
- 返回 `412` 按冲突处理，不退回无条件写入

#### WebDAV 无可用 ETag 的更新

- 无有限锁且无可用于 `If-Match` 的 ETag 时，上传任何对象前拒绝更新已有清单
- 本地与远端数据均保留
- 首次创建仍使用 `If-None-Match: *`
- 发布后的回读可发现部分覆盖，但不能替代服务端原子条件写入

#### WebDAV 有限独占目录锁与 7 天观察

- 支持有限租期独占 `Depth: infinity` 写锁的服务端，在完整读取、上传、发布全程持锁
- 每个请求携带锁令牌
- 每次发布生成新 `publicationId`，仅保护当前完整闭包
- 前代对象刚失去引用时，重置候选年龄并启动 7 天本地观察
- 无变更读取同样推进观察

#### WebDAV 到期条件清理

- 前提：持有有限锁，且候选满 7 天
- 先条件刷新同一闭包的 `publicationId`，不重传内容对象，并确认获得新强 ETag
- 再核对当前完整闭包，按各对象强 ETag 条件删除规范对象，每批最多 32 个
- 本地最多跟踪 1,024 个候选
- 目录、无关文件与传统备份永不删除

#### WebDAV 无锁兼容

- 尚未确认有限锁能力的目标，仅在有可用 ETag 时更新已有清单，并保留旧对象
- 触发条件之一：不支持 `LOCK`，返回 `405`、`501` 或 `403`，如坚果云
- 触发条件之二：初次获授无限期或超过 300 秒的锁，并已成功释放
- 曾确认支持有限锁的目标，失锁或能力降级时立即中止操作

#### WebDAV 远端闭包验证

- 生产读取强制验证完整远端对象闭包
- 本地缓存命中不能证明远端对象存在

#### WebDAV 租期上限与释放

- 目录锁会短时阻塞同目录的其他写入方
- 实际授予租期必须有限且不超过 300 秒
- 无限期或超过 300 秒的锁立即尝试释放并拒绝持有，避免进程异常退出后遗留长期目录锁

#### WebDAV 重定向与 URL 规范

- 持锁请求禁止跟随重定向
- 带 `query` 的地址沿用既有路由与强 ETag 同步，不探测目录锁，不清理对象
- 已确认支持有限锁的目标，即使地址带 `query` 也拒绝降级
- `fragment` 不参与 HTTP 锁与维护状态的资源身份计算

#### WebDAV 本机观察与无锁读取

- 7 天宽限期基于本机观察，不是跨设备的全局引用历史
- 时钟异常、对象重新被引用或内容变化均重置候选年龄
- 使用有限锁的客户端整段读取全程持锁
- 旧版无锁读取在清理后遇到缺块时，完整校验拒绝应用部分数据

#### WebDAV 清理失败与解锁恢复

- 普通延迟清理失败：返回已校验或已发布的版本，留待后续同步重试，不回滚清单
- 完整上传成功后，仅释放锁阶段因网络或服务端错误而结果不确定时，客户端重新获取一次有限独占锁
- 核对原清单的强 ETag、指纹与全部远端引用对象，成功释放新锁后才确认发布
- 恢复期间不重复上传或清理
- 失锁、条件冲突、取消或核对未完成时，不确认本次同步，保留远端已发布清单，下次同步重读

#### 不兼容服务的处理

- 既无有限目录锁，也无服务端支持的条件 ETag 时，更新同步会明确报错
- 可改用支持其一的 WebDAV 服务，或切换 GitHub 同步
- 切换前先导出本地备份，新目标同步完成后再处理旧目标
- 有限锁下，弱 ETag 或缺失 ETag 同样可用
- 无锁写入的安全性无法靠错开手动同步获得

### ⬆️ 升级与容量

#### 升级流程

- 新安装和空远端使用 V4
- 已有同步地址首次升级时显示一次升级确认
- 新增地址读取到合法传统载荷或 V3 时提示
- 迁移许可绑定地址、实际内容指纹和来源/目标版本，其一变更即重新确认
- 观察到该地址已使用 V4，才完成升级并移除提示
- 失败或取消后可重试
- 升级完整保留旧歌词
- 历史有损开关已停用，不参与快照、迁移和投影，常驻压缩选项已移除
- 普通网络缓存按既有编辑状态语义省略，已编辑歌词和恢复版本独立持久保存

#### 容量预算

- 分块与内容寻址复用降低增量同步流量
- 首次全库体积与压缩比取决于实际数据
- 播放统计采用磁盘暂存与分页处理
- 歌单、历史与歌词 registry 仍有列表开销
- 读写共用对称的留存记录上限：主元数据与旧歌词候选合计最多 32 MiB 原始载荷、131,072 个已知解码对象
- 真正分页处理的统计不计入该聚合总额，单条记录仍有独立上限
- 物化为 `SyncData` 列表的统计共享该聚合预算
- 超出预算时直接报错中止，不截断历史或歌词，不应用部分数据
- 完整 Android 合并流程在低堆内存设备上的表现需独立验证

歌词同步说明见 [歌词同步](LYRIC_SYNC.md)。

### 🧪 测试

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

`SyncArchiveScaleTest` 默认跳过，显式设置 `-DrunSyncScale=true` 才运行：

- 百万歌单成员的容量拒绝测试
- 千万统计记录的归档测试
- 仅验证归档层，完整 Android 同步流程的内存验证需另行进行

```bash
./gradlew :sync:testDebugUnitTest --tests '*SyncArchiveScaleTest' -DrunSyncScale=true
```

#### 🔌 本地 WebDAV 兼容测试

真实服务测试使用独立临时目录，覆盖：

- 首次 V4 发布与冷缓存重开
- V3 升级
- 歌词与分页统计完整性
- 陈旧发布拒绝
- 中文目录续租、锁竞争和释放后重取

运行要求：

- 服务绑定 loopback 并允许匿名访问
- 地址不带凭据、query 或 fragment
- 租约测试只读取 `NERIPLAYER_WEBDAV_COMPAT_URLS`
- 完整同步测试同时读取该变量和 `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`
- 未配置对应地址时跳过

本地验证服务为 WsgiDAV 4.3.5 + Cheroot 11.1.2、rclone 1.75.1 和 Apache 2.4.67 mod_dav，回归覆盖：

- WsgiDAV 的裸令牌
- rclone 的十进制令牌与续租目录规范化
- Apache 省略的 lockroot
- 有限锁下缺失或弱 ETag
- 任意相对令牌与错误锁根均被拒绝

拒绝 LOCK 的服务（如坚果云）放入 `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`，验证有可用 ETag 的条件同步。缺失或弱 ETag 的更新拒绝，以及条件冲突不降级，由确定性交错回归覆盖。

```bash
NERIPLAYER_WEBDAV_COMPAT_URLS='http://127.0.0.1:38091/,http://127.0.0.1:38090/,http://127.0.0.1:38092/' \
  ./gradlew :sync:testDebugUnitTest --tests '*WebDavProviderLeaseTest' \
  :local:testDebugUnitTest --tests '*WebDavProviderCompatibilityTest'
```

## English

`:sync` synchronizes playlists, songs, history, and statistics through GitHub or WebDAV. It handles transport, codecs, merging, and conflict retries. Shared models live in `:model`. Android repository and Worker adapters are in `:local`.

### 🧩 Layout

Sources are in `src/main/java/moe/ouom/neriplayer`:

- `api/sync`: GitHub/WebDAV requests and responses
- `data/sync/archive`: V4 reversible record packing, ZSTD, Merkle validation, and chunk cache
- `data/sync/runtime/SyncSession.kt`: session entry point, accessing data through `SyncLocalDataStore` and `SyncBackend`
- `data/sync/codec`, `sanitize`, `change`, `merge`: codecs, sanitization, change detection, and merging
- `data/sync/remote`, `retry`, `store`, `schedule`: remote versions, retries, credentials, and scheduling

### 📦 Archive protocol

#### Publication and versions

- Publication filename: `neriplayer-sync-v3.manifest`, the sole publication entry
- Envelope identifier: `NPSYNC04`
- Data object prefix: `neriplayer-sync-v4-`
- Protocol and record schema versions are recorded explicitly
- A missing V4 version field or an unknown future version aborts both reads and publication
- Legacy JSON/GZIP and V3 formats are read-only, for migration
- Older clients cannot write V4, so upgrade every device sharing a target before migrating

#### Encoding and content reuse

- Metadata is organized into bounded blocks
- Lyric text is deduplicated by content address
- Numeric, timestamp, and text encodings are exactly reversible
- Legacy lyric variants for the same song, nullable fields, original baselines, and historical daily-bucket metadata are retained

#### Merkle trees and block limits

- Main records, legacy candidates, and the text pool each get a separate Merkle tree
- Decoded data blocks are capped at 4 MiB and indexes at 128 KiB
- Wire objects are capped at 2 MiB, and incompressible blocks are split further

#### Integrity and temporary directories

- Unpacked raw record streams must pass full SHA-256 verification
- Temporary workspaces are cleaned up on completion, failure, or cancellation

#### Compression level

- New objects use ZSTD level 6 to reduce local compression time
- V3/V4 share the same window limits and frame checksums
- Existing level 19 cache objects are reused after raw-content hash validation
- Missing or corrupt blocks are rebuilt with the current setting
- The compression level does not affect the lossless protocol, and raw hashes and record-stream checks remain required

### 📥 Reads and caching

#### V4 read flow

- Validates indexes, leaf counts, and byte budgets in order
- Downloads at most 4 data objects concurrently per batch
- Repeated objects within one read reuse verified content, and record order is preserved
- Production reads verify every referenced remote object
- Cancellation aborts the actual archive GET and missing-directory probe HTTP calls
- WebDAV lease renewal and state are guarded by a shared lock, with network reads and body parsing outside the lock
- Concurrent GitHub rate-limit responses in one cooldown count as one round, and new rounds still observe the automatic retry limit

#### Branch and root tree resolution

- Resolves the default branch on every sync round
- Takes the commit and root tree OIDs from that same branch response
- Fetches the complete root tree

#### Local chunk cache validation

- Cached chunks first pass compressed-length and SHA-256 checks
- They are then matched against the Git OID of the regular blob in the root tree
- A match is reused directly, skipping per-object GETs and repeated `fsync` calls
- Missing, corrupt, or changed entries are downloaded again and checked against the response OID

#### Tree proof reuse and garbage collection

- Uploads in one round reuse the tree proof only when API base, repository, branch, and commit all match, and reread it otherwise
- Object cleanup inspects the complete root tree, not just the manifest-referenced subset
- A truncated tree, a missing referenced object, or a body mismatch fails immediately
- Decompression, raw-hash, and complete record-stream checks always run

#### Request overhead

- A warm flat archive with no changes takes 4 read requests
- A small edit adds 1 publication request
- Total latency depends on network conditions and local dataset size

#### Temporary directory reclamation

- Archive initialization and workspace creation reclaim canonical `sync-stage-UUID` directories left by a terminated process, including unmarked older workspaces
- Each active workspace holds its own file lock, and a stable root lock protects creation and recovery
- Raw records used by concurrent instances are never deleted by mistake
- Process termination releases locks automatically
- Cleanup does not follow symbolic links or touch unrelated directories or retained lyric sources
- A failed deletion keeps the ownership file for a later retry

### 🚀 Publication and concurrency

#### GitHub fast path (single commit)

- Reads a fixed HEAD
- Used when additions plus deletions total at most 64, raw content is at most 3 MiB, and full UTF-8 JSON is at most 4 MiB
- Calls GraphQL `createCommitOnBranch` with `expectedHeadOid`
- Publishes binary objects, the manifest, and cleanup in one atomic commit

#### GitHub staged upload

- When fast-path budgets are exceeded, switches to staged upload before sending any write request
- Finalizes by updating refs atomically through `updateRefs` (`beforeOid`, `force=false`)
- After a fast commit is sent, errors, cancellation, or incomplete responses never switch to another write flow

#### GitHub cleanup scope

- Publishes the new manifest and its referenced objects in one commit
- Removes unreferenced canonical V3/V4 root objects in the same commit
- Preserves unrelated files and existing Git history

#### WebDAV object creation and reads

- Content objects and the initial manifest are created with `If-None-Match: *`
- Archive reads force identity encoding so servers such as nginx do not weaken strong ETags through compression

#### WebDAV conditional publication and unquoted ETags

- Rereads the manifest before every publication and verifies its content fingerprint
- When a strong ETag exists, verifies it too and submits `If-Match`
- Unlocked servers such as Nutstore return unquoted ETags, which are placed verbatim in `If-Match` so the server rejects concurrent writes
- A `412` response is treated as a conflict and never falls back to an unconditional write

#### WebDAV updates without usable ETags

- Without a finite lease or an ETag usable in `If-Match`, updates to an existing manifest are rejected before any object upload
- Local and remote data are both retained
- Initial creation still uses `If-None-Match: *`
- A post-publication read can detect some partial overwrites but cannot replace atomic conditional writes on the server

#### WebDAV finite exclusive collection locks and the 7-day window

- Servers supporting finite exclusive `Depth: infinity` write locks hold the lease across the full read, upload, and publication
- Every request carries the lock token
- Each publication assigns a fresh `publicationId` that protects only the current closure
- Objects that just lost their last reference reset their candidate age and start a 7-day local observation window
- Unchanged reads also advance the window

#### WebDAV conditional garbage collection

- Precondition: a finite lease is held and the candidate is at least 7 days old
- First conditionally refreshes the same closure's `publicationId` without re-uploading content objects, and confirms a new strong ETag
- Then re-validates the complete closure and deletes canonical objects conditionally by each object's strong ETag, at most 32 per batch
- The local journal tracks at most 1,024 candidates
- Directories, unrelated files, and legacy backups are never deleted

#### WebDAV lockless compatibility

- Targets without a confirmed finite-lease capability update existing manifests only with a usable ETag, and retain old objects
- Trigger one: `LOCK` is unsupported, returning `405`, `501`, or `403`, as with Nutstore
- Trigger two: the initial grant was infinite or longer than 300 seconds and was released cleanly
- Targets with a confirmed finite-lease capability abort immediately on lease loss or capability downgrade

#### WebDAV remote closure verification

- Production reads verify the complete remote object closure
- A local cache hit does not prove remote availability

#### WebDAV lease cap and release

- Collection locks briefly block other writers in the same directory
- Granted leases must be finite and at most 300 seconds
- Infinite or longer grants are released immediately where possible and never held, so a crashed process cannot leave a long-lived collection lock

#### WebDAV redirects and URL identity

- Locked requests never follow redirects
- URLs with a `query` keep their routing and strong ETag sync, without probing locks or collecting objects
- Targets with a confirmed finite-lease capability still refuse to downgrade, even for such URLs
- URL `fragment`s are excluded from lock and maintenance resource identity

#### WebDAV local observation and lockless readers

- The 7-day window reflects local observations, not global cross-device reference history
- Clock anomalies, renewed references, or object changes reset the candidate age
- Lease-capable clients hold a finite lock across the entire read
- When an older lockless reader hits a block removed by cleanup, full verification rejects the partial data

#### WebDAV cleanup failure and unlock recovery

- Ordinary deferred cleanup failure: returns the validated or newly published version, retries on a later sync, and does not roll back the manifest
- After a full upload succeeds, if only lock release fails with an uncertain network or server error, the client reacquires a finite exclusive lock once
- It verifies the original manifest's strong ETag, fingerprint, and complete remote object closure, and acknowledges publication only after releasing the new lock
- No re-upload or cleanup happens during recovery
- On lease loss, conflict, cancellation, or incomplete verification, the sync is not acknowledged, the published remote manifest stays intact, and the next sync rereads it

#### Handling incompatible servers

- A server with neither finite collection locks nor server-supported conditional ETags gets an explicit update error
- Use a WebDAV server supporting either mechanism, or switch to GitHub sync
- Export a local backup before switching, and retire the old target only after syncing the new one
- Under a finite lease, weak or absent ETags still work
- Staggering manual syncs cannot make unlocked writes safe

### ⬆️ Upgrade and capacity

#### Upgrade flow

- New installations and empty targets use V4
- Existing sync targets show a one-time upgrade prompt on first update
- Newly configured targets prompt when validated legacy payloads or V3 data are found
- Approval binds the target, the actual content fingerprint, and the source/destination versions, and any change requires renewed approval
- The upgrade completes and the prompt disappears only after V4 is observed for that target
- Failures and cancellations can be retried
- The upgrade preserves legacy lyrics completely
- The historical lossy preference is retired, takes no part in snapshots, migration, or projection, and the permanent compression option is removed
- Ordinary network caches are omitted per the existing edit-state semantics, while edited lyrics and reset versions persist independently

#### Capacity budget

- Chunking and content-addressed reuse reduce incremental sync traffic
- Initial archive size and compression ratio depend on the dataset
- Playback statistics use disk staging and pagination
- Playlists, history, and the lyric registry still carry in-memory list costs
- Reads and writes share a symmetric retained-record budget: main metadata and legacy lyric candidates combined are capped at 32 MiB of raw payloads and 131,072 decoded objects
- Truly paged statistics are excluded from this aggregate total, with per-record limits still applied
- Statistics materialized into `SyncData` lists share the aggregate budget
- Exceeding the budget fails explicitly, without truncating history or lyrics or applying partial data
- Low-heap behavior across the full Android merge workflow needs separate verification

For lyric sync details, see [Lyric synchronization](LYRIC_SYNC.md).

### 🧪 Tests

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

`SyncArchiveScaleTest` is skipped by default. It runs only with an explicit `-DrunSyncScale=true`:

- Capacity rejection for a million playlist members
- Archiving test for ten million statistics records
- These cover the archive layer only, and memory validation of the complete Android sync flow is done separately

```bash
./gradlew :sync:testDebugUnitTest --tests '*SyncArchiveScaleTest' -DrunSyncScale=true
```

#### 🔌 Local WebDAV compatibility tests

Real provider tests use isolated temporary collections and cover:

- Fresh V4 publication and cold-cache reopening
- V3 migration
- Complete lyrics and paged statistics
- Stale publication rejection
- Encoded collection lease refresh, contention, and reacquisition after release

Requirements:

- The provider binds to loopback and allows anonymous access
- URLs carry no credentials, query, or fragment
- Lease tests read only `NERIPLAYER_WEBDAV_COMPAT_URLS`
- Full sync tests read that variable and `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS`
- Tests without configured targets are skipped

The local matrix covers WsgiDAV 4.3.5 with Cheroot 11.1.2, rclone 1.75.1, and Apache 2.4.67 mod_dav. Regression coverage includes:

- WsgiDAV bare tokens
- rclone decimal tokens and refreshed collection normalization
- Optional lockroot in Apache
- Absent or weak ETags under finite leases
- Arbitrary relative tokens and mismatched lock roots, which are rejected

Providers that refuse LOCK, such as Nutstore, go in `NERIPLAYER_WEBDAV_COMPAT_UNLOCKED_URLS` to verify conditional sync with usable ETags. Deterministic interleaving regressions cover rejected updates without usable ETags and conflicts that must not downgrade to unconditional writes.

```bash
NERIPLAYER_WEBDAV_COMPAT_URLS='http://127.0.0.1:38091/,http://127.0.0.1:38090/,http://127.0.0.1:38092/' \
  ./gradlew :sync:testDebugUnitTest --tests '*WebDavProviderLeaseTest' \
  :local:testDebugUnitTest --tests '*WebDavProviderCompatibilityTest'
```
