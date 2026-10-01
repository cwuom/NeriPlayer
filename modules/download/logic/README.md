# 📥 下载规则与存储基础

`:download:logic` 维护下载准入、执行状态迁移、重试与延后队列、传输槽位和看门狗、所有权，以及清空、提交、大小和发布规则。

存储基础按后端、引用、命名、目录树、恢复和元数据编解码分包；它描述下载行为与数据边界，实际传输、Room 队列、SAF 写入和后台任务由 `:download:runtime` 执行。

共享状态与数据契约来自 `:model`，通用辅助来自 `:common`。计算域按白名单使用模型、协程和必要网络类型；JSON 编解码不调用存储入口，规则不得读取 Room、Android 服务或应用容器。

传输注册表共用同一状态锁，FIFO、并发限制和活动心跳不得各自修改槽位所有权。新增规则和边界测试放入所属职责包，目录模式会自动纳入 CRAP 门禁。

## 🧪 验证

```bash
./gradlew :download:logic:verifyCrap :download:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../../README.md#模块结构)，共享门禁见 [质量工具说明](../../../tools_pub/quality/README.md)。
