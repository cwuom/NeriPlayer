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

收藏和播放统计在 IO 协程中加载。Room 读取失败与尚未迁移是不同状态，读取失败时保留原数据，相关写入、备份和同步等待成功加载后再继续；再次操作可重试，其他功能仍可使用。只有确认 Room 尚未接管且旧 JSON 完整可读时才导入，缺失文件与合法空列表仍支持首次迁移。已有完整快照后的 Room 写入故障保留原子 JSON 回退，主存标记也成功保存后才确认落盘。播放统计保留旧 JSON 投影供兼容读取，首次改写前先提交已加载的完整基线，投影写完后再将新完整快照原子写入 `playback_stats_meta.json`；有完整 metadata 快照时恢复以该提交点为准，避免读取中断保存留下的混合投影。

Favorites and playback stats load on an IO coroutine. A failed Room read differs from an unmigrated store: original data is retained, and related writes, backups, and sync require a successful load. Later operations can retry while other features remain available. Legacy import requires confirmation that Room is not primary and that existing JSON is readable; missing files and valid empty lists still support first migration. After a complete snapshot has loaded, Room write failures retain atomic JSON fallback, with persistence confirmed only after the primary-store marker is saved. Playback stats retain legacy JSON projections for compatible readers, commit the fully loaded baseline before their first rewrite, and atomically write the new complete snapshot to `playback_stats_meta.json` after all projections succeed. Recovery uses that committed snapshot when present, avoiding mixed projections left by an interrupted save.

## 测试 / Tests

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
