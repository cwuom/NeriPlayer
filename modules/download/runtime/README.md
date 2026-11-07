# 下载执行 / Download runtime

`:download:runtime` 负责下载传输、Room 队列、受管文件、目录迁移和恢复，以及 WorkManager 与 JobScheduler 接入。

`:download:runtime` owns transfers, Room queues, managed files, directory migration and recovery, and WorkManager/JobScheduler integration.

## 结构 / Layout

主要入口是 [GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) 和 [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt)。下载规则来自 `:download:logic`，来源、网络、歌词和存储接入使用 `:platform`、`:network`、`:lyrics`、`:database` 与 `:local`。

The main entry points are [GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) and [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt). Rules come from `:download:logic`; sources, networking, lyrics, and storage use `:platform`, `:network`, `:lyrics`, `:database`, and `:local`.

app 在 `core/integration/download` 通过 `DownloadHosts.install(DownloadHostBindings(...))` 安装宿主接口后再启动下载。

The app installs host interfaces through `DownloadHosts.install(DownloadHostBindings(...))` in `core/integration/download` before starting downloads.

最终音频校验不能把平台解析器不支持的容器直接判为损坏；Bilibili 独立 FLAC-in-MP4 音频流可使用项目现有版本的 Media3 解析器独立核验，仍需确认音轨、实际时长和至少一个完整可读样本，并限制解析过程的读取量与内存分配。恢复已有收尾操作时，应按持久 attempt 和进度检查点重建任务展示；取消必须等待该 operation 的资产处理和不可取消写入完成后，才释放取消状态。

Final audio validation must distinguish unsupported platform containers from damaged files. Bilibili's standalone FLAC-in-MP4 audio stream can be independently checked with the project's existing Media3 version, while still requiring an audio track, a measured duration, and at least one complete readable sample within read and allocation budgets. Resuming post-core operations restores task presentation from the durable attempt and progress checkpoint. Cancellation waits for that operation's asset processing and non-cancellable writes before releasing cancellation state.

额外的 Media3 兜底仅接受纯音频 MP4，且所有 sample entry 及 FLAC 配置都必须通过 `fLaC` 白名单预检。包含视频或其他编码条目的混合容器不在该兜底支持范围；独立的平台解析、可信 TagLib 属性和元数据 retriever 校验继续适用。Media3 在输出轨道前已解析各轨道的编码配置，因此只丢弃非音频输出不能限制这些配置的内存分配。

The additional Media3 fallback accepts audio-only MP4 whose sample entries and FLAC configuration pass the `fLaC` allowlist. Mixed containers with video or other codec entries remain outside this fallback; independent platform parsing, trusted TagLib properties, and metadata-retriever validation remain available. Media3 parses each track's codec configuration before emitting track output, so discarding non-audio output does not bound those allocations.

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
