# 数据库 / Database

`:database` 管理 `NeriUserDataDatabase`、Room 实体、DAO、存储入口和版本升级，保存歌单、历史、统计、播放队列、下载记录与平台缓存。

`:database` owns `NeriUserDataDatabase`, Room entities, DAOs, stores, and migrations. It persists playlists, history, statistics, the playback queue, download records, and platform caches.

源码在 `src/main/java/moe/ouom/neriplayer/data/local/database`，历史 schema 在 `schemas`。模块仅依赖 `:model`，通过 KSP 生成 Room 代码。业务映射由 `:local` 维护，平台缓存策略由 `:platform` 维护；数据库仅在应用主进程打开。

Sources are in `src/main/java/moe/ouom/neriplayer/data/local/database`, with historical schemas in `schemas`. The module depends only on `:model` and uses KSP to generate Room code. `:local` owns business mappings and `:platform` owns cache policies. The database opens only in the app's main process.

## 升级 / Migrations

schema 变更需同步更新导出文件和历史升级链，保留旧记录、冲突数据与下载恢复索引。旧下载表在数据保全后才能移除，不能用重建数据库替代兼容升级。

Schema changes require updated exports and historical migrations that preserve old records, conflicting data, and download recovery indexes. Legacy download tables can be dropped only after preserving their data; rebuilding the database cannot replace a compatible upgrade.

## 测试 / Tests

`DatabaseUpgradeInstrumentedTest` 使用真实 Room 检查历史升级和旧下载数据保留，需连接 Android 模拟器运行。

`DatabaseUpgradeInstrumentedTest` checks historical upgrades and legacy download preservation against real Room. It requires a connected Android emulator.

```bash
./gradlew :database:testDebugUnitTest :database:lintDebug
./gradlew :database:connectedDebugAndroidTest
```
