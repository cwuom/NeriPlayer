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

## 测试 / Tests

```bash
./gradlew :local:testDebugUnitTest :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```
