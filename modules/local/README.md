# 本地数据 / Local data

`:local` 管理本地媒体、歌单、设置、历史、统计、备份、存储占用和缓存清理，并接入 GitHub/WebDAV 同步。

`:local` manages local media, playlists, settings, history, statistics, backups, storage usage, cache cleanup, and GitHub/WebDAV sync integration.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/data`。

Sources are in `src/main/java/moe/ouom/neriplayer/data`.

- `local`：媒体、导入与歌单 / media, imports, and playlists
- `local/media/curation`、`local/media/metadata`：批量整理的写入授权、内嵌资源检查与元数据恢复事务 / write authorization, embedded asset inspection, and metadata recovery transactions for batch curation
- `local/storage`：占用统计、扫描与清理 / storage accounting, scans, and cleanup
- `settings`、`config`：设置与配置，KSP 在本库运行 / settings and configuration, with KSP run in this library
- `history`、`stats`、`playlist`：历史与使用统计 / history and usage statistics
- `backup`、`identity`、`traffic`：备份、歌曲身份与流量 / backups, song identity, and traffic
- `sync`：本地仓库、封面与 WorkManager 适配 / local repository, cover, and WorkManager adapters

Room 数据由 `:database` 保存，平台账号和缓存由 `:platform` 管理，同步会话由 `:sync` 执行。界面与播放器通过仓库和宿主接口访问本库。

`:database` persists Room data, `:platform` manages platform accounts and caches, and `:sync` runs sync sessions. UI and playback access this library through repositories and host interfaces.

## 配置导入 / Configuration import

配置导入在恢复任何分段前先检查备份内的账号存储：

| 条件 / 阶段 | 行为 |
| --- | --- |
| 需软件兼容存储 | 必须经界面明确确认；直接调用 `ConfigFileManager.importConfig` 默认拒绝恢复 |
| 取消或不可持久化 | 用户取消确认或账号存储无法持久化时，中止操作且不修改任何配置分段 |
| 确认等待期 | 复用同一份已解析的备份数据，不重复读取来源文件 |

Configuration import inspects account storage in the backup before restoring any section:

| Condition / Stage | Behavior |
| --- | --- |
| Software Compatibility Storage | Requires explicit UI confirmation; direct calls to `ConfigFileManager.importConfig` reject restoration by default |
| Cancellation or Unwritable Store | Cancelling confirmation or lacking persistent account storage aborts the import and leaves all sections unchanged |
| Confirmation Window | Reuses the already parsed backup payload without re-reading the source file |

## 缓存与本地编辑 / Cache and local edits

缓存清理保留已下载的音频、歌词和封面。`ExtraCacheClearResult` 区分文件释放空间与 Room 可复用空间；删除记录不会立即缩小数据库文件。已有目录、索引文件名及同步 Worker 类名需保持升级兼容。

Cache cleanup preserves downloaded audio, lyrics, and covers. `ExtraCacheClearResult` distinguishes freed file bytes from reusable Room space; deleting records does not immediately shrink the database file. Existing directories, index filenames, and sync Worker class names must remain compatible across upgrades.

本地导入缓存可单独清理完全没有引用的提取封面，并重置临时扫描索引。歌单、历史、统计、队列和下载仍使用的封面，以及手动封面与编辑前原图均会保留。本次启动中已使用的封面留待下次冷启动判断，保护尚未完成的导入和预览。无法完整确认引用，或下载、元数据恢复任务未完成时，不回收封面。

Local import cache cleanup removes extracted covers with no remaining references and resets temporary scan indexes. Covers used by playlists, history, statistics, queues, or downloads, custom covers, and originals from before edits are retained. Covers used during the current app session are reconsidered after the next cold start to protect pending imports and previews. Covers are not reclaimed when references cannot be read completely or download or metadata recovery tasks are unfinished.

| 项目 | 规则 |
| --- | --- |
| 原始基线与恢复 | 本地编辑保留首次修改前的歌词与封面，恢复时优先采用持久化的原始值；明确无原封面的状态在重扫和重开后仍会保留 |
| 重扫隔离 | 重新扫描不会用当前侧载内容覆盖已保存的原始值 |
| 空值与文件复用 | 区分未知内容与明确为空；缺失且为空的翻译、罗马音歌词不创建新文件，已有歌词文件按类型复用 |

| Aspect | Rule |
| --- | --- |
| Original Baseline & Restore | Local edits preserve lyrics and artwork from before the first edit; restoration prefers persisted originals and retains a confirmed absence of original artwork across rescans and reopening |
| Rescan Isolation | Rescanning never overwrites persisted originals with current sidecar contents |
| Empty Values & File Reuse | Unknown and explicitly empty values remain distinct; missing empty translation or romanization tracks do not create extra files, and existing lyric files are reused by type |

本地歌曲每次在编辑页保存时都可选择回写音频或仅应用内保存。显式回写会处理已有的自定义值和已知的恢复值，即使表单内容没有再次改变；未知内容继续保留。

Every local song save offers audio write-back or app-only persistence. Explicit write-back includes existing custom values and known restored values even when the form has not changed again; unknown content remains preserved.

## 写入授权与恢复 / Write authorization and recovery

`LocalMediaWriteAccess` 在写入前规划存储授权；音频及歌词、封面伴随文件的修改通过恢复日志管理：

| 环节 | 行为 |
| --- | --- |
| Android 11+ 授权 | 对尚不可写的 MediaStore 条目分批请求系统写入同意 |
| Android 10 授权 | 对其他应用创建的文件引导授予所在文件夹权限 |
| 恢复日志与提交 | 日志记录写入意图、原始备份与对象身份，写入后经读回校验确认才提交 |
| 冲突保护 | 覆盖前同时校验完整对象身份与内容；哈希相同不能证明对象归属，检测到对象被替换或内容冲突时保留恢复日志并中止覆盖 |
| 中断写入 | 空文件或不完整音频须匹配原对象身份及已校验原始或更新副本的内容前缀；缺少身份的旧日志只允许清理已完成的操作，不授权覆盖 |
| 描述符写入 | 直接文件和 URI 都在同一可读写描述符上校验、截断、写入与同步，直接文件的修改时间也绑定该描述符；目标不可写或 Provider 不支持非截断打开时保留文件与恢复日志 |
| 写入可见性 | 音频原地更新，不依赖路径替换；其他读取者可能看到写入中的内容，中断后依靠恢复日志及已校验副本重试 |

`LocalMediaWriteAccess` plans storage authorization before writing. Recovery journals manage audio and lyric/artwork companion updates:

| Stage | Behavior |
| --- | --- |
| Android 11+ Authorization | Requests system write consent in batches for MediaStore items that are not yet writable |
| Android 10 Authorization | Prompts for folder grants when modifying files created by other apps |
| Recovery Journal & Commit | Tracks write intent, original backups, and object identity; commits only after read-back verification |
| Conflict Protection | Verifies full instance identity and content before overwriting; an equal hash does not establish ownership, and object replacement or content conflicts retain the journal and stop the write |
| Interrupted Writes | Empty or incomplete audio must match the recorded instance and a prefix of a verified original or updated copy; legacy journals without identity may only clean up completed operations and cannot authorize overwrites |
| Descriptor Writes | Direct files and URIs use the same read/write descriptor for verification, truncation, writing, and sync; direct-file timestamps are also bound to that descriptor. Unwritable targets or providers without non-truncating access retain files and journals |
| Write Visibility | Updates audio in place without replacing its path; other readers may observe a write in progress, and interrupted writes retry using the journal and verified copies |

## 数据加载与统计 / Data loading and statistics

收藏、历史与歌单使用统计在 IO 协程中加载，严格区分 Room 读取失败与尚未迁移状态：

| 状态 / 环节 | 行为 |
| --- | --- |
| Room 读取失败 | 保留原数据；相关写入、备份与同步等待成功加载后才继续，后续操作可触发重试，其余功能保持可用 |
| 旧 JSON 首次迁移 | 仅在确认 Room 尚未接管且旧 JSON 完整可读时导入；文件缺失或合法空列表同样支持首次迁移 |
| 主存标记与取消恢复 | 保留 JSON 回退的仓库须待主存标记保存成功后才确认落盘；事务提交后若协程取消，内存基线即刻失效并在恢复时重读实际主存 |

Favorites, history, and playlist usage statistics load on an IO coroutine, strictly separating failed Room reads from unmigrated stores:

| State / Stage | Behavior |
| --- | --- |
| Failed Room Read | Retains existing data; related writes, backups, and sync wait for a successful load while other features stay available and subsequent actions can retry |
| First Legacy JSON Migration | Imports only after confirming Room is not yet primary and the legacy JSON is fully readable; missing files and valid empty lists still complete initial migration |
| Primary Marker & Cancellation | Repositories with JSON fallback acknowledge persistence only after saving the primary-store marker; post-commit cancellation invalidates the in-memory baseline and forces a fresh read from the primary store |

歌曲播放统计以 Room 为主存，冷启动不加载全量曲目与日桶列表，写入时也不生成全量 JSON 投影：

| 环节 | 行为与边界 |
| --- | --- |
| 迁移与记账事务 | 旧数据采用流式导入迁移；稳定事件 ID、持久回执与待应用增量在同一事务入队，重试不重复计数 |
| 远端同步 | 捕获固定页快照与持久 `revision`，完整校验远端数据集后才应用，遇到 `revision` 冲突直接拒绝旧结果 |
| 查询与手动备份 | 统计总览与排行榜采用 SQL 汇总与分页查询；手动 JSON 备份按页导入/导出统计并保留完整历史，取消原 1,000 条截断 |
| 备份校验与容量边界 | 导入须通过整份 JSON 与版本校验，拒绝未知未来版本；歌单、历史与歌词元数据仍按列表恢复，整份备份非跨仓库原子事务，配置导入与完整播放队列保留各自容量上限 |

Track playback statistics use Room as their primary store, avoiding full track or daily-bucket loads at startup and full JSON projections on writes:

| Area | Behavior and Boundaries |
| --- | --- |
| Migration & Accounting | Legacy data streams into Room; stable event IDs, durable receipts, and pending deltas enqueue in a single transaction so retries never double-count |
| Remote Sync | Captures fixed-size pages and a durable `revision`, validates the entire remote dataset before applying, and rejects stale `revision` conflicts |
| Queries & Manual Backup | Summaries and rankings use SQL aggregation and pagination; manual JSON backups import and export statistics in pages, keeping full history without the 1,000-entry truncation |
| Validation & Limits | Imports validate the entire JSON document and version, rejecting unknown future versions; playlists, history, and lyric metadata still restore as lists, backups are not cross-repository transactions, and config imports plus full queues keep their own capacity limits |

带事件 ID 的歌单播放增量只有在 Room 计数和回执共用事务时才能确认。旧 JSON 导入失败时保留该事件，恢复后先确认实际主存再重试迁移，避免进程重启后重复计数。没有事件 ID 的既有歌单写入仍支持 JSON 回退。

Playlist play increments carrying event IDs are acknowledged only when Room persists the count and receipt in one transaction. Failed legacy JSON promotion leaves the event pending; recovery confirms the actual primary store before retrying promotion, preventing duplicate counts after process restart. Existing playlist writes without event IDs retain JSON fallback.

## 扫描与本地播放 / Scans and local playback

文件夹扫描优先保存授权树内的音频引用，不把路径存在当作 MediaStore 读取授权。旧引用不可读时，播放器可在同一存储卷和已授权目录内恢复同一文件的 SAF 引用，编辑回写也复用这一来源。封面和元数据侧载共用恢复事务，失败或取消时回滚；回滚未完成时停止尝试其它音频别名。

Folder scans prefer audio references within the granted document tree; an existing path does not establish MediaStore read access. When an old reference is unreadable, playback can recover a SAF reference to the same file within a granted directory on the same volume, and metadata edits reuse that source. Artwork and metadata sidecars share a recovery transaction. Failure or cancellation rolls back the changes, and an unresolved rollback stops further audio alias attempts.

| 阶段 | 行为 |
| --- | --- |
| 扫描期复用 | 对仅有 content URI 的音频直接复用目录行与已知侧载索引，不逐首打开音频或查询文档 |
| 预览期后台补全 | 延后的曲目身份信息在预览界面后台异步补全 |
| 导入前身份核对 | 提交歌单持久化前，按有界批次核对所选 MP3 的原始身份（即使立即关闭预览也不会跳过）；现有歌词与封面基线保持原值 |

| Phase | Behavior |
| --- | --- |
| Scan Reuse | Content-URI-only audio reuses directory rows and known sidecar indexes without opening each audio file or querying individual documents |
| Preview Hydration | Deferred track identity is populated in the background while previewing |
| Pre-Commit Verification | Selected MP3s undergo bounded raw-identity checks before playlist persistence, even if the preview closes immediately; existing lyric and artwork baselines remain intact |

从扫描预览创建或加入普通歌单时，持久化用户明确选择的本地引用，保留旁车恢复的远端身份。本机播放仅接受与当前首选引用完全一致的来源标记；同步投影清除此标记和本地地址。旧记录没有扫描来源证据时不自动推断，再次显式扫描导入同一远端条目可以更新它的本地来源。

Creating or updating an ordinary playlist from scan results persists the explicitly selected local reference while retaining remote identity recovered from sidecars. Local playback accepts provenance only when it exactly matches the current preferred reference; remote sync projections remove provenance and local addresses. Records without scan provenance remain unclassified until the user explicitly imports a scanned source again.

## 测试 / Tests

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
