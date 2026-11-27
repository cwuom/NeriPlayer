[English](#english) | [中文](#中文)

# 下载执行 / Download runtime

## 中文

`:download:runtime` 负责下载传输、Room 队列、受管文件、目录迁移和恢复，以及 WorkManager 与 JobScheduler 接入。

### 结构

- 主要入口：[GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) 和 [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt)
- 下载规则来自 `:download:logic`
- 来源、网络、歌词和存储使用 `:platform`、`:network`、`:lyrics`、`:database`、`:local`
- app 在 `core/integration/download` 调用 `DownloadHosts.install(DownloadHostBindings(...))` 安装宿主接口，之后才启动下载

### 最终音频校验

校验规则：

- 平台解析器不支持的容器不直接判为损坏
- Bilibili 独立 FLAC-in-MP4 音频流可用项目现有版本的 Media3 解析器独立核验
- 核验要求：存在音轨、实测时长、至少一个完整可读样本
- 解析过程限制读取量与内存分配

Media3 兜底范围：

- 仅接受纯音频 MP4
- 所有 sample entry 及 FLAC 配置须通过 `fLaC` 白名单预检
- 含视频或其他编码条目的混合容器不在兜底范围
- 平台解析、可信 TagLib 属性和元数据 retriever 校验独立执行
- Media3 在输出轨道前已解析各轨道的编码配置，仅丢弃非音频输出无法限制这些配置的内存分配

恢复与取消：

- 恢复已有收尾操作时，按持久 attempt 和进度检查点重建任务展示
- 取消须等该 operation 的资产处理和不可取消写入完成，再释放取消状态

### 兼容

以下类须保持完整类名兼容，以保留已持久化任务和系统入口：

- [后台 Worker](src/main/java/moe/ouom/neriplayer/core/download/execution/worker)
- [ManagedDownloadMigrationWorker](src/main/java/moe/ouom/neriplayer/core/download/storage/migration/ManagedDownloadMigrationWorker.kt)
- [Manifest](src/main/AndroidManifest.xml) 中的 `UidtDownloadJobService`

### 测试

在仓库根目录运行，设备测试使用 Android 模拟器。JVM 测试不能替代实际 Provider 的读写、权限丢失和进程恢复验证。

```bash
./gradlew :download:runtime:verifyCrap :download:runtime:lintDebug
./gradlew :app:verifyDomainDependencies :app:connectedDebugAndroidTest
```

## English

`:download:runtime` owns transfers, Room queues, managed files, directory migration and recovery, and WorkManager/JobScheduler integration.

### Layout

- Main entry points: [GlobalDownloadManager](src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt) and [ManagedDownloadStorage](src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt)
- Download rules come from `:download:logic`
- Sources, networking, lyrics, and storage use `:platform`, `:network`, `:lyrics`, `:database`, `:local`
- The app installs host interfaces with `DownloadHosts.install(DownloadHostBindings(...))` in `core/integration/download` before starting downloads

### Final audio validation

Validation rules:

- Containers unsupported by platform parsers are not treated as damaged outright
- Bilibili's standalone FLAC-in-MP4 audio stream can be checked independently with the project's existing Media3 version
- The check requires an audio track, a measured duration, and at least one complete readable sample
- Parsing is limited in reads and memory allocation

Media3 fallback scope:

- Accepts audio-only MP4 only
- All sample entries and the FLAC configuration must pass the `fLaC` allowlist precheck
- Mixed containers with video or other codec entries are outside the fallback
- Platform parsing, trusted TagLib properties, and metadata-retriever validation run independently
- Media3 parses each track's codec configuration before emitting track output, so discarding non-audio output cannot bound those allocations

Resume and cancellation:

- Resuming a post-core operation restores task presentation from the durable attempt and progress checkpoint
- Cancellation waits for that operation's asset processing and non-cancellable writes before releasing cancellation state

### Compatibility

These classes must keep compatible fully qualified names for persisted jobs and system entry points:

- [Background Workers](src/main/java/moe/ouom/neriplayer/core/download/execution/worker)
- [ManagedDownloadMigrationWorker](src/main/java/moe/ouom/neriplayer/core/download/storage/migration/ManagedDownloadMigrationWorker.kt)
- `UidtDownloadJobService` in the [Manifest](src/main/AndroidManifest.xml)

### Tests

Run from the repository root; device tests use an Android emulator. JVM tests do not replace actual Provider checks for I/O, permission loss, and process recovery.

```bash
./gradlew :download:runtime:verifyCrap :download:runtime:lintDebug
./gradlew :app:verifyDomainDependencies :app:connectedDebugAndroidTest
```
