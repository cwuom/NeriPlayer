# 同步会话与规则

`:data:sync` 承载 GitHub/WebDAV 共用的会话、兼容编解码、快照清洗、差异检测、合并和并发保护。共享载荷与状态契约位于 `:data:model`。

`runtime` 编排会话；`codec`、`sanitize` 和 `change` 处理载荷与本地变化；`merge` 按歌单、歌曲、历史与统计维护合并规则；`remote` 与 `retry` 处理远端版本和冲突；`schedule` 维护 Worker 资格判断、播放优先级和失败策略。

本地仓库、封面映射、文案和 WorkManager 适配位于 `:data:repository`，通过接口接入。网络协议位于 `:api:sync`，凭据与删除状态位于 `:data:sync-store`。计算组件不得引用 Android 宿主、数据库、网络客户端或播放器实现。

仓库适配通过 `host` 组装平台能力，`mapping` 按歌曲、歌单、收藏、历史快照和本地恢复拆分转换，`work` 处理任务请求与通知，`cover` 维护封面持久化。Worker 类全名、存储文件名和键名属于升级兼容边界。

```bash
./gradlew :data:sync:verifyCrap :data:sync:verifyDomainDependencies :data:sync:lintDebug
./gradlew :data:repository:verifySyncIntegrationCrap
```

整个 `data/sync` 目录自动进入复杂度门禁，任意方法 CRAP 大于 9 即失败。依赖门禁验证编译后的类，包括新类、协程、lambda 和生成类。
