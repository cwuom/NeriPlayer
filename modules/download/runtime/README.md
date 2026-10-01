# 下载执行 / Download runtime

`:download:runtime` 负责下载传输、Room 队列、受管文件、目录迁移和恢复，以及 WorkManager 与 JobScheduler 接入。

`:download:runtime` owns transfers, Room queues, managed files, directory migration and recovery, and WorkManager/JobScheduler integration.

## 结构 / Layout

主要入口是 [GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) 和 [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt)。下载规则来自 `:download:logic`，来源、网络、歌词和存储接入使用 `:platform`、`:network`、`:lyrics`、`:database` 与 `:local`。

The main entry points are [GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) and [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt). Rules come from `:download:logic`; sources, networking, lyrics, and storage use `:platform`, `:network`, `:lyrics`, `:database`, and `:local`.

app 在 `core/integration/download` 通过 `DownloadHosts.install(DownloadHostBindings(...))` 安装宿主接口后再启动下载。

The app installs host interfaces through `DownloadHosts.install(DownloadHostBindings(...))` in `core/integration/download` before starting downloads.

## 兼容 / Compatibility

[后台 Worker](src/main/java/moe/ouom/neriplayer/core/download/execution/worker)、[ManagedDownloadMigrationWorker](src/main/java/moe/ouom/neriplayer/core/download/storage/migration/ManagedDownloadMigrationWorker.kt) 和 [Manifest](src/main/AndroidManifest.xml) 中的 `UidtDownloadJobService` 须保持完整类名兼容，以保留已持久化任务和系统入口。

[Background Workers](src/main/java/moe/ouom/neriplayer/core/download/execution/worker), [ManagedDownloadMigrationWorker](src/main/java/moe/ouom/neriplayer/core/download/storage/migration/ManagedDownloadMigrationWorker.kt), and `UidtDownloadJobService` in the [Manifest](src/main/AndroidManifest.xml) must retain compatible fully qualified names for persisted jobs and system entry points.

## 测试 / Tests

在仓库根目录运行，设备测试使用 Android 模拟器。JVM 测试不能替代实际 Provider 的读写、权限丢失和进程恢复验证。

Run from the repository root with an Android emulator for device tests. JVM tests do not replace actual Provider checks for I/O, permission loss, and process recovery.

```bash
./gradlew :download:runtime:verifyCrap :download:runtime:lintDebug
./gradlew :app:verifyDomainDependencies :app:connectedDebugAndroidTest
```
