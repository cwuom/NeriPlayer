# 💽 媒体存储能力

`:storage` 提供媒体来源、目录扫描、空间统计、清理规划与存储策略。调用方通过来源和清理端口提供文件访问，展示层消费统计或扫描快照。

实现位于 `data/storage/source`、`scan`、`accounting`、`cleanup` 和 `policy`。模块公开使用 `:model` 的存储契约，不依赖本地仓库、下载运行库或播放器。

扫描与计算组件不得直接读取应用容器或数据库。新增 Provider 或目录行为应保持取消、文件身份和所有权语义；删除计划与实际文件删除需要分别验证。

## 🧪 验证

```bash
./gradlew :storage:testDebugUnitTest :storage:lintDebug
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
