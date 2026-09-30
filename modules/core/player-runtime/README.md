# 播放状态协调

`:core:player-runtime` 管理刷新归属、预取竞争、持久化调度、进度、统计、质量状态和传输控制。它接收稳定歌曲标识、状态快照和副作用回调，不读取播放器单例或应用容器

`runtime` 下按 `blocking`、`metadata`、`persistence`、`prefetch`、`progress`、`quality`、`refresh`、`service`、`source`、`stats`、`transport` 分包。平台 URL 解析和 Android 服务在 `:feature:player`，播放决策在 `:core:player-policy`，共享模型在 `:data:model`

新增协调器应明确请求归属、取消行为和清理时机，并在本模块 `src/test` 验证状态转换。共享 CRAP scope 自动包含职责包中的新文件和子目录；任何受管方法的分数大于 9 都会使门禁失败

```bash
./gradlew :core:player-runtime:verifyCrap :core:player-runtime:lintDebug
```

模块布局和依赖方向见仓库根目录 [README](../../../README.md)
