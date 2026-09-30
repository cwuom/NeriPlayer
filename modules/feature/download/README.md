# 下载执行与存储

`:feature:download` 负责下载传输、Room 执行队列、批次与启动恢复、受管音频与侧载文件、目录迁移，以及 WorkManager 和 JobScheduler 接入。Worker 和 JobService 保留原有完整类名，以兼容已持久化的任务和系统入口

下载准入、状态迁移、重试、传输槽位、所有权和元数据编解码规则位于 `:core:download`；跨模块数据契约位于 `:data:model`。本模块通过 `host` 接口使用来源服务、凭据、歌词、流量、启动门控和播放能力，应用在 `core/integration/download` 安装完整接口集合后再启动下载

模块不引用应用容器或播放器实现。共享 HTTP Range 能力位于 `:core:network`，平台响应解析位于对应 `:api:*` 模块。新增下载生产代码应放入本模块的职责子包或已有规则模块，`app/core/download` 和 `app/core/player/download` 由结构门禁禁止回流

界面通过 `presentation/progress` 查询执行进度，通过 `storage/migration/access` 读取迁移检查点。`integration/legacy` 提供旧数据交接所需的标记和维护操作；Room 执行存储与检查点写入器保持内部可见，调用方不直接操作这些实现

```bash
./gradlew :feature:download:testDebugUnitTest
./gradlew :core:download:verifyCrap :feature:download:verifyCrap
./gradlew :feature:download:lintDebug
```

JVM 测试随实现放在 `src/test`，共享源码契约工具使用 `:core:common` 的 test fixtures。应用及 Provider 集成测试保留在 app，仅 JVM 和设备测试编译通过 Kotlin friend paths 访问下载内部状态，业务代码仍使用公开接口。JVM 覆盖率不能替代设备上的文件与恢复行为验证

`verifyCrap` 和 `check` 使用共享范围与真实 JaCoCo 执行数据。原有方法选择器继续有效，宿主接口、请求代次、通知刷新、任务编号、重试截止时间和单项异常隔离按职责目录整文件受检，app 的下载宿主实现由聚合门禁整目录检查，新文件自动纳入；范围内任意方法分数大于 9 会使 Android CI 失败。报告位于 `build/reports/crap`

模块结构和质量约束见仓库根目录 [README](../../../README.md#模块结构) 与 [质量工具说明](../../../tools_pub/quality/README.md)
