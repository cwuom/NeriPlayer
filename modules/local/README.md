# 本地数据 / Local data

`:local` 管理本地媒体、歌单、设置、历史、统计、备份、存储占用和缓存清理，并接入 GitHub/WebDAV 同步。

`:local` manages local media, playlists, settings, history, statistics, backups, storage usage, cache cleanup, and GitHub/WebDAV sync integration.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/data`。

Sources are in `src/main/java/moe/ouom/neriplayer/data`.

- `local`：媒体、导入与歌单 / media, imports, and playlists
- `local/storage`：占用统计、扫描与清理 / storage accounting, scans, and cleanup
- `settings`、`config`：设置与配置，KSP 在本库运行 / settings and configuration, with KSP run in this library
- `history`、`stats`、`playlist`：历史与使用统计 / history and usage statistics
- `backup`、`identity`、`traffic`：备份、歌曲身份与流量 / backups, song identity, and traffic
- `sync`：本地仓库、封面与 WorkManager 适配 / local repository, cover, and WorkManager adapters

Room 数据由 `:database` 保存，平台账号和缓存由 `:platform` 管理，同步会话由 `:sync` 执行。界面与播放器通过仓库和宿主接口访问本库。

`:database` persists Room data, `:platform` manages platform accounts and caches, and `:sync` runs sync sessions. UI and playback access this library through repositories and host interfaces.

缓存清理保留已下载的音频、歌词和封面。`ExtraCacheClearResult` 区分文件释放空间与 Room 可复用空间；删除记录不会立即缩小数据库文件。已有目录、索引文件名及同步 Worker 类名需保持升级兼容。

Cache cleanup preserves downloaded audio, lyrics, and covers. `ExtraCacheClearResult` distinguishes freed file bytes from reusable Room space; deleting records does not immediately shrink the database file. Existing directories, index filenames, and sync Worker class names must remain compatible across upgrades.

收藏、历史和歌单使用统计在 IO 协程中加载。Room 读取失败与尚未迁移是不同状态，读取失败时保留原数据，相关写入、备份和同步等待成功加载后再继续；再次操作可重试，其他功能仍可使用。只有确认 Room 尚未接管且旧 JSON 完整可读时才导入，缺失文件与合法空列表仍支持首次迁移。保留 JSON 回退的仓库只有在主存标记也成功保存后才确认落盘；事务提交后取消会使内存基线失效，恢复时重新读取实际主存。

Favorites, history, and playlist usage load on an IO coroutine. A failed Room read differs from an unmigrated store: original data is retained, and related writes, backups, and sync require a successful load. Later operations can retry while other features remain available. Legacy import requires confirmation that Room is not primary and that existing JSON is readable; missing files and valid empty lists still support first migration. Repositories retaining JSON fallback acknowledge persistence only after the primary-store marker is saved. Cancellation after commit invalidates the in-memory baseline; recovery reads the actual primary store again.

歌曲播放统计直接使用 Room 主存，不再冷启动读取完整曲目和日桶列表，也不在每次写入时生成全量 JSON 投影。旧数据通过流式导入迁移；稳定事件 ID、持久回执和待应用增量在同一事务中入队，重试不重复计数。同步捕获固定页快照与持久 revision，远端数据完整校验后才能应用，revision 冲突会拒绝旧结果。统计总览与排行榜使用 SQL 汇总和分页查询，手动 JSON 备份统计导入、导出逐页处理，完整历史不按 1,000 条截断。导入在整份 JSON 与版本校验通过后才应用，拒绝未知未来版本；歌单、历史和歌词元数据仍按既有列表恢复，整个备份不是跨仓库事务。配置导入和完整播放队列仍有各自的容量约束。

Track playback statistics use Room as their primary store, without loading all tracks and daily buckets at startup or writing complete JSON projections after every change. Legacy data is imported as a stream. Stable event IDs, durable receipts, and pending deltas are enqueued in one transaction so retries do not count twice. Sync captures fixed-size pages and a durable revision, validates the complete remote dataset before applying it, and rejects stale revisions. Summaries and rankings use SQL aggregation and paginated queries; manual JSON backup imports and exports statistics in pages and retains complete history without the former 1,000-entry truncation. Import validates the entire JSON document and version before application, rejecting future versions. Playlist, history, and lyric metadata still use lists; the overall backup is not a transaction spanning all repositories. Configuration import and full playback queues retain their existing capacity limits.

带事件 ID 的歌单播放增量只有在 Room 计数和回执共用事务时才能确认。旧 JSON 导入失败时保留该事件，恢复后先确认实际主存再重试迁移，避免进程重启后重复计数。没有事件 ID 的既有歌单写入仍支持 JSON 回退。

Playlist play increments carrying event IDs are acknowledged only when Room persists the count and receipt in one transaction. Failed legacy JSON promotion leaves the event pending; recovery confirms the actual primary store before retrying promotion, preventing duplicate counts after process restart. Existing playlist writes without event IDs retain JSON fallback.

## 测试 / Tests

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
