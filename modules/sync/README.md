# 🔄 同步传输、状态与会话

`:sync` 维护 GitHub 与 WebDAV 同步的完整业务能力：HTTP 传输、凭据和删除状态、会话编排、兼容编解码、快照清洗、差异检测、合并与远端并发保护。共享载荷和状态契约位于 `:model`。

`api/sync/github`、`http`、`webdav` 维护网络协议、响应读取和条件写入；`data/sync/store` 按 GitHub、WebDAV、偏好、加密凭据和状态组织持久化。原源码包名继续保留，均属于本库。

`data/sync/runtime` 编排会话，`codec`、`sanitize`、`change` 处理载荷与本地变化，`merge` 按歌单、歌曲、历史和统计维护规则；`remote`、`retry` 处理版本与冲突，`schedule` 维护 Worker 资格、播放优先级和失败策略。

网络与状态实现只消费所属职责端口，计算域不得引用 Android 仓库、数据库、网络客户端或播放器实现。Bilibili 与 YouTube 身份能力来自 `:platform`，通用辅助来自 `:common`；域级允许列表继续限制同库包之间的依赖。

本地仓库、封面映射、文案和 WorkManager 适配由 `:local` 的 `data/sync` 职责包维护。其 `host`、`mapping`、`work`、`cover` 通过接口接入；Worker 类全名、存储文件名和键名保持升级兼容。

`verifyCrap` 合并本库与 `:model` 同步模型的真实 JVM 覆盖率，完整检查 `data/sync` 和 `api/sync` 的受管文件。任意受检方法大于 9 即失败。`verifyDomainDependencies` 同时检查业务与传输的实际编译类，包括新类、协程、lambda 和生成类；Android 适配由本地库另行验证。

## 🧪 验证

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
