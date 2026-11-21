[English](#english) | [中文](#中文)

# 下载逻辑 / Download logic

## 中文

`:download:logic` 提供下载准入、状态迁移、重试、并发和文件所有权规则，以及命名、引用访问和元数据编解码。

### 结构

- 源码：[core/download](src/main/java/moe/ouom/neriplayer/core/download)
- 依赖：`:model`、`:common`、协程、OkHttp 和 AndroidX 文件兼容库
- 实际传输、Room 队列和后台任务由 `:download:runtime` 负责
- `resource/permit/DownloadTransferPermitRegistry` 统一管理传输槽位、FIFO 等待和心跳
- 调用方在结束或取消时释放 `Permit`

### 测试

在仓库根目录运行：

```bash
./gradlew :download:logic:verifyCrap :download:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```

## English

`:download:logic` provides admission, state transitions, retries, concurrency, file ownership, naming, reference access, and metadata codecs.

### Layout

- Sources: [core/download](src/main/java/moe/ouom/neriplayer/core/download)
- Dependencies: `:model`, `:common`, coroutines, OkHttp, and AndroidX file helpers
- `:download:runtime` owns transfers, Room queues, and background jobs
- `resource/permit/DownloadTransferPermitRegistry` manages transfer permits, FIFO waiters, and heartbeats
- Callers release their `Permit` on completion or cancellation

### Tests

Run from the repository root:

```bash
./gradlew :download:logic:verifyCrap :download:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```
