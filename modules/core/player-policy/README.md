# 播放规则

`:core:player-policy` 根据输入快照决定播放命令、进度恢复、停滞检测、缓存准入、音频 offload 和服务生命周期行为

规则按 `audio`、`command`、`offload`、`pending`、`progress`、`service`、`skip`、`storage`、`wake` 分包。模块不读取 `PlayerManager` 或 `AppContainer`，不执行网络、数据库和服务操作。跨模块状态使用 `:data:model`，副作用由 `:feature:player` 的适配器执行

新增规则放入对应职责包，并在本模块 `src/test` 验证边界条件。共享 CRAP scope 自动包含这些包中的新文件和子目录；任何受管方法的分数大于 9 都会使门禁失败

```bash
./gradlew :core:player-policy:verifyCrap :core:player-policy:lintDebug
```

模块布局和依赖方向见仓库根目录 [README](../../../README.md)
