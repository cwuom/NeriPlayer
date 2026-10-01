# 🎵 播放基础与计算

`:playback:logic` 汇集播放 policy、状态协调 runtime、音频计算 audio 和队列 queue。它根据快照与注入端口生成决策，不承担 ExoPlayer、Android 服务、USB 设备或来源仓库的运行实现。

`core/player/policy` 仅含 `audio`、`command`、`offload`、`pending`、`progress`、`service`、`skip`、`storage`、`wake` 九个叶子包。policy 不引用同库的 runtime、audio 或 queue 实现；状态协调可以消费 policy，音频与队列保持窄依赖。USB policy 位于 `:playback:runtime`。

`core/player/runtime` 按阻塞、元数据、持久化、预取、进度、质量、刷新、服务、来源、统计和传输组织协调器；`audio` 维护 PCM 与响应式计算；`queue` 维护身份端口、导航和编辑策略、状态存储。

共享契约由 `:model` 提供，通用辅助来自 `:common`。库内计算域不得读取 `PlayerManager`、`AppContainer`、数据库或网络客户端；副作用由 `:playback:runtime` 的适配器执行。

新增组件及边界测试归本库。共享 CRAP 配置覆盖四组职责包的新文件和子目录，任何受检方法大于 9 即失败；包级编译依赖白名单由应用聚合域任务校验。

## 🧪 验证

```bash
./gradlew :playback:logic:verifyCrap :playback:logic:lintDebug
./gradlew :app:verifyDomainDependencies
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../../README.md#模块结构)，共享门禁见 [质量工具说明](../../../tools_pub/quality/README.md)。
