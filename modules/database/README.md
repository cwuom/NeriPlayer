# 🗃️ 数据库与升级

`:database` 负责 Room 数据库、实体、DAO、存储入口和完整历史升级链。歌单与历史的业务映射、平台缓存使用策略由 `:local` 与 `:platform` 维护。

源码位于 `data/local/database`，按 `dao`、`entity`、`maintenance`、`store` 和 `migration` 分包。升级组件进一步按 `library`、`platform`、`download`、`legacy` 分类；历史 schema 保存在本库的 `schemas` 目录。

本库仅依赖 `:model`，通过 KSP 生成 Room 代码。修改 schema 或升级逻辑时必须维护历史数据保全、冲突处理和恢复索引，不能以重建数据库替代兼容升级。

升级目录进入全项目 CRAP 范围；本库的独立 JVM 测试校验历史升级链。涉及真实 Room 行为时使用本库 Android 测试，JVM 结果不能替代设备升级验证。

## 🧪 验证

```bash
./gradlew :database:testDebugUnitTest :database:lintDebug
./gradlew :database:connectedDebugAndroidTest
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
