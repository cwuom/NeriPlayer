# 下载逻辑 / Download logic

`:download:logic` 提供下载准入、状态迁移、重试、并发和文件所有权规则，以及命名、引用访问和元数据编解码。

`:download:logic` provides admission, state transitions, retries, concurrency, file ownership, naming, reference access, and metadata codecs.

## 结构 / Layout

源码位于 [core/download](src/main/java/moe/ouom/neriplayer/core/download)。依赖 `:model`、`:common`、协程、OkHttp 和 AndroidX 文件兼容库；实际传输、Room 队列和后台任务由 `:download:runtime` 负责。

Sources live in [core/download](src/main/java/moe/ouom/neriplayer/core/download). Dependencies include `:model`, `:common`, coroutines, OkHttp, and AndroidX file helpers. `:download:runtime` owns transfers, Room queues, and background jobs.

`resource/permit/DownloadTransferPermitRegistry` 统一管理传输槽位、FIFO 等待和心跳。调用方在结束或取消时释放 `Permit`。

`resource/permit/DownloadTransferPermitRegistry` manages transfer permits, FIFO waiters, and heartbeats. Callers release their `Permit` on completion or cancellation.

## 测试 / Tests

在仓库根目录运行：

Run from the repository root:

```bash
./gradlew :download:logic:verifyCrap :download:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```
