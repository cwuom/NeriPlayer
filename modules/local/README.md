# 本地数据 / Local data

[English](#english) | [中文](#中文)

## 中文

`:local` 管理本地媒体、歌单、设置、历史、统计、备份、存储占用和缓存清理，并接入 GitHub/WebDAV 同步。

### 结构

源码位于 `src/main/java/moe/ouom/neriplayer/data`：

- `local`：媒体、导入与歌单
- `local/media/curation`、`local/media/metadata`：批量整理的写入授权、内嵌资源检查与元数据恢复事务
- `local/storage`：占用统计、扫描与清理
- `settings`、`config`：设置与配置，KSP 在本库运行
- `history`、`stats`、`playlist`：历史与使用统计
- `backup`、`identity`、`traffic`：备份、歌曲身份与流量
- `sync`：本地仓库、封面与 WorkManager 适配

模块分工：

- `:database` 保存 Room 数据
- `:platform` 管理平台账号和缓存
- `:sync` 执行同步会话
- 界面与播放器通过仓库和宿主接口访问本库

### 配置导入

配置导入在恢复任何分段前，先检查备份内的账号存储。

#### 软件兼容存储

- 须经界面明确确认
- 直接调用 `ConfigFileManager.importConfig` 默认拒绝恢复

#### 取消或不可持久化

- 用户取消确认，或账号存储无法持久化时，中止导入
- 所有配置分段均不修改

#### 确认等待期

- 复用同一份已解析的备份数据
- 不重复读取来源文件

### 缓存与本地编辑

#### 缓存清理

- 保留已下载的音频、歌词和封面
- `ExtraCacheClearResult` 区分文件释放空间与 Room 可复用空间
- 删除记录不会立即缩小数据库文件
- 已有目录、索引文件名及同步 Worker 类名须保持升级兼容

#### 本地导入缓存清理

- 清理完全没有引用的提取封面
- 重置临时扫描索引
- 保留歌单、历史、统计、队列和下载仍在使用的封面
- 保留手动封面与编辑前原图
- 本次启动中已使用的封面留待下次冷启动判断，保护尚未完成的导入和预览
- 无法完整确认引用，或下载、元数据恢复任务未完成时，不回收封面

#### 原始基线与恢复

- 本地编辑保留首次修改前的歌词与封面
- 恢复时优先采用持久化的原始值
- 明确无原封面的状态，在重扫和重开后仍保留

#### 重扫隔离

- 重新扫描不会用当前侧载内容覆盖已保存的原始值

#### 空值与文件复用

- 区分未知内容与明确为空
- 缺失且为空的翻译、罗马音歌词不创建新文件
- 已有歌词文件按类型复用

#### 保存方式

- 本地歌曲在编辑页保存时，可选择回写音频，或仅在应用内保存
- 显式回写会处理已有的自定义值和已知的恢复值，即使表单内容没有再次改变
- 未知内容继续保留

### 写入授权与恢复

`LocalMediaWriteAccess` 在写入前规划存储授权。音频及歌词、封面伴随文件的修改通过恢复日志管理。

#### Android 11+ 授权

- 将旧曲库的公共存储路径，按卷和完整路径匹配到 MediaStore
- 对尚不可写的条目，分批请求系统写入同意

#### 文件夹授权

- Android 10 上其他应用创建的文件，引导授予所在文件夹权限
- Android 11+ 上未索引且不可直接写入的路径，同样引导授予

#### 恢复日志与提交

- 日志记录写入意图、原始备份与对象身份
- 写入后经读回校验确认才提交

#### 冲突保护

- 覆盖前同时校验完整对象身份与内容
- 哈希相同不能证明对象归属
- 检测到对象被替换或内容冲突时，保留恢复日志并中止覆盖

#### 中断写入

- 空文件或不完整音频，须匹配原对象身份及已校验原始或更新副本的内容前缀
- 缺少身份的旧日志只允许清理已完成的操作，不授权覆盖

#### 描述符写入

- 直接文件和 URI 都在同一可读写描述符上校验、截断、写入与同步
- 直接文件的修改时间也绑定该描述符
- 目标不可写或 Provider 不支持非截断打开时，保留文件与恢复日志

#### 写入可见性

- 音频原地更新，不依赖路径替换
- 其他读取者可能看到写入中的内容
- 中断后依靠恢复日志及已校验副本重试

### 数据加载与统计

收藏、历史与歌单使用统计在 IO 协程中加载，严格区分 Room 读取失败与尚未迁移状态。

#### Room 读取失败

- 保留原数据
- 相关写入、备份与同步等待成功加载后才继续
- 后续操作可触发重试，其余功能保持可用

#### 旧 JSON 首次迁移

- 仅在确认 Room 尚未接管且旧 JSON 完整可读时导入
- 文件缺失或合法空列表同样支持首次迁移

#### 主存标记与取消恢复

- 保留 JSON 回退的仓库，须待主存标记保存成功后才确认落盘
- 事务提交后若协程取消，内存基线即刻失效
- 恢复时重读实际主存

#### 播放统计

歌曲播放统计以 Room 为主存。冷启动不加载全量曲目与日桶列表，写入时也不生成全量 JSON 投影。

##### 迁移与记账事务

- 旧数据采用流式导入迁移
- 稳定事件 ID、持久回执与待应用增量在同一事务入队
- 重试不重复计数

##### 远端同步

- 捕获固定页快照与持久 `revision`
- 完整校验远端数据集后才应用
- 遇到 `revision` 冲突，直接拒绝旧结果

##### 查询与手动备份

- 统计总览与排行榜采用 SQL 汇总与分页查询
- 手动 JSON 备份按页导入/导出统计，保留完整历史，取消原 1,000 条截断

##### 备份校验与容量边界

- 导入须通过整份 JSON 与版本校验，拒绝未知未来版本
- 歌单、历史与歌词元数据按列表恢复
- 整份备份不是跨仓库的原子事务
- 配置导入与完整播放队列各有容量上限

#### 手动歌单备份预算

- 支持导出大量元数据，继续校验对象数量、单个 JSON token 和单条统计记录
- 导入和差异预览按开始读取时的进程堆余量，额外设置元数据字节和对象预算，给解码和合并留出空间
- 超出预算时，在修改本地仓库前返回失败
- 统计列表按页处理

#### 歌单播放增量

- 带事件 ID 的增量，只有 Room 计数和回执共用事务时才能确认
- 旧 JSON 导入失败时保留该事件
- 恢复后先确认实际主存再重试迁移，避免进程重启后重复计数
- 没有事件 ID 的既有歌单写入仍支持 JSON 回退

### 扫描与本地播放

#### 授权树内的音频引用

- 文件夹扫描优先保存授权树内的音频引用
- 路径存在不等于 MediaStore 读取授权
- 旧引用不可读时，播放器可在同一存储卷和已授权目录内，恢复同一文件的 SAF 引用
- 编辑回写复用这一来源

#### 恢复事务

- 封面和元数据侧载共用恢复事务，失败或取消时回滚
- 回滚未完成时，停止尝试其它音频别名

#### 扫描期复用

- 仅有 content URI 的音频，直接复用目录行与已知侧载索引
- 不逐首打开音频，也不逐个查询文档

#### 预览期后台补全

- 延后的曲目身份信息在预览界面后台异步补全

#### 导入前身份核对

- 提交歌单持久化前，按有界批次核对所选 MP3 的原始身份
- 即使立即关闭预览也不会跳过
- 现有歌词与封面基线保持原值

#### 扫描来源标记

- 从扫描预览创建或加入普通歌单时，持久化用户明确选择的本地引用，保留旁车恢复的远端身份
- 本机播放仅接受与当前首选引用完全一致的来源标记
- 同步投影清除此标记和本地地址
- 旧记录没有扫描来源证据时不自动推断，再次显式扫描导入同一远端条目，可更新它的本地来源

### 测试

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

## English

`:local` manages local media, playlists, settings, history, statistics, backups, storage usage, cache cleanup, and GitHub/WebDAV sync integration.

### Layout

Sources are in `src/main/java/moe/ouom/neriplayer/data`:

- `local`: media, imports, and playlists
- `local/media/curation`, `local/media/metadata`: write authorization, embedded asset inspection, and metadata recovery transactions for batch curation
- `local/storage`: storage accounting, scans, and cleanup
- `settings`, `config`: settings and configuration, with KSP run in this library
- `history`, `stats`, `playlist`: history and usage statistics
- `backup`, `identity`, `traffic`: backups, song identity, and traffic
- `sync`: local repository, cover, and WorkManager adapters

Module roles:

- `:database` persists Room data
- `:platform` manages platform accounts and caches
- `:sync` runs sync sessions
- UI and playback access this library through repositories and host interfaces

### Configuration import

Configuration import inspects account storage in the backup before restoring any section.

#### Software compatibility storage

- Requires explicit UI confirmation
- Direct calls to `ConfigFileManager.importConfig` reject restoration by default

#### Cancellation or unwritable store

- If the user cancels confirmation or persistent account storage is unavailable, the import aborts
- All configuration sections stay unmodified

#### Confirmation window

- Reuses the already parsed backup payload
- Does not re-read the source file

### Cache and local edits

#### Cache cleanup

- Preserves downloaded audio, lyrics, and covers
- `ExtraCacheClearResult` distinguishes freed file bytes from reusable Room space
- Deleting records does not immediately shrink the database file
- Existing directories, index filenames, and sync Worker class names must stay compatible across upgrades

#### Local import cache cleanup

- Removes extracted covers with no remaining references
- Resets temporary scan indexes
- Retains covers still used by playlists, history, statistics, queues, or downloads
- Retains custom covers and originals from before edits
- Covers used during the current app session are reconsidered after the next cold start, protecting pending imports and previews
- Covers are not reclaimed when references cannot be read completely or download or metadata recovery tasks are unfinished

#### Original baseline and restore

- Local edits preserve lyrics and artwork from before the first edit
- Restoration prefers the persisted originals
- A confirmed absence of original artwork persists across rescans and reopening

#### Rescan isolation

- Rescanning never overwrites persisted originals with current sidecar contents

#### Empty values and file reuse

- Unknown and explicitly empty values are distinct
- Missing, empty translation or romanization lyrics create no extra files
- Existing lyric files are reused by type

#### Save modes

- Each local song save in the editor offers audio write-back or app-only persistence
- Explicit write-back includes existing custom values and known restored values, even when the form has not changed again
- Unknown content stays preserved

### Write authorization and recovery

`LocalMediaWriteAccess` plans storage authorization before writing. Recovery journals manage audio and lyric/artwork companion updates.

#### Android 11+ authorization

- Resolves legacy shared-storage paths to MediaStore items by volume and full path
- Requests system write consent in batches for items that are not yet writable

#### Folder authorization

- Prompts for a folder grant for files created by other apps on Android 10
- Does the same for unindexed paths without direct write access on Android 11+

#### Recovery journal and commit

- The journal tracks write intent, original backups, and object identity
- A write commits only after read-back verification

#### Conflict protection

- Verifies full object identity and content before overwriting
- An equal hash does not establish ownership
- On object replacement or a content conflict, retains the journal and stops the write

#### Interrupted writes

- Empty or incomplete audio must match the recorded object identity and a prefix of a verified original or updated copy
- Legacy journals without identity may only clean up completed operations and cannot authorize overwrites

#### Descriptor writes

- Direct files and URIs use the same read/write descriptor for verification, truncation, writing, and sync
- Direct-file timestamps are also bound to that descriptor
- Unwritable targets, or providers without non-truncating access, retain files and journals

#### Write visibility

- Audio is updated in place, without replacing its path
- Other readers may observe a write in progress
- Interrupted writes retry using the journal and verified copies

### Data loading and statistics

Favorites, history, and playlist usage statistics load on an IO coroutine, separating failed Room reads from unmigrated stores.

#### Failed Room read

- Existing data is retained
- Related writes, backups, and sync wait for a successful load
- Subsequent actions can retry, and other features stay available

#### First legacy JSON migration

- Imports only after confirming Room is not yet primary and the legacy JSON is fully readable
- Missing files and valid empty lists still complete initial migration

#### Primary marker and cancellation

- Repositories with JSON fallback acknowledge persistence only after saving the primary-store marker
- If the coroutine is cancelled after the transaction commits, the in-memory baseline is invalidated immediately
- Recovery rereads the actual primary store

#### Playback statistics

Track playback statistics use Room as their primary store. Startup does not load full track or daily-bucket lists, and writes do not build full JSON projections.

##### Migration and accounting

- Legacy data streams into Room
- Stable event IDs, durable receipts, and pending deltas enqueue in a single transaction
- Retries never double-count

##### Remote sync

- Captures fixed-size pages and a durable `revision`
- Validates the entire remote dataset before applying
- Rejects stale results on a `revision` conflict

##### Queries and manual backup

- Summaries and rankings use SQL aggregation and pagination
- Manual JSON backups import and export statistics in pages, keeping full history without the former 1,000-entry truncation

##### Validation and limits

- Imports validate the entire JSON document and version, and reject unknown future versions
- Playlists, history, and lyric metadata restore as lists
- A full backup is not a cross-repository transaction
- Config imports and full playback queues each keep their own capacity limit

#### Manual playlist backup budgets

- Exports allow large metadata payloads while still enforcing object, token, and statistics-record limits
- Imports and difference previews also set byte and object budgets from the process heap headroom at the start of each read, leaving room for decoding and merging
- A backup exceeding these budgets fails before local repositories are modified
- Statistics lists are processed in pages

#### Playlist play increments

- An increment carrying an event ID is acknowledged only when Room persists the count and receipt in one transaction
- A failed legacy JSON import leaves the event pending
- Recovery confirms the actual primary store before retrying, preventing duplicate counts after a process restart
- Existing playlist writes without event IDs retain JSON fallback

### Scans and local playback

#### Audio references in granted trees

- Folder scans prefer audio references within the granted document tree
- An existing path does not establish MediaStore read access
- When an old reference is unreadable, playback can recover a SAF reference to the same file within a granted directory on the same volume
- Metadata edits reuse that source

#### Recovery transactions

- Artwork and metadata sidecars share a recovery transaction, and failure or cancellation rolls it back
- An unresolved rollback stops further audio alias attempts

#### Scan reuse

- Content-URI-only audio reuses directory rows and known sidecar indexes
- It does not open each audio file or query individual documents

#### Preview hydration

- Deferred track identity is populated in the background while previewing

#### Pre-commit verification

- Selected MP3s undergo bounded raw-identity checks before playlist persistence
- The checks run even if the preview closes immediately
- Existing lyric and artwork baselines keep their values

#### Scan provenance

- Creating or updating an ordinary playlist from scan results persists the explicitly selected local reference and keeps the remote identity recovered from sidecars
- Local playback accepts provenance only when it exactly matches the current preferred reference
- Remote sync projections remove provenance and local addresses
- Records without scan provenance are not inferred, and explicitly importing a scanned source for the same remote entry again can update its local source

### Tests

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
