# 📚 本地媒体与应用数据

`:local` 维护本地歌单与媒体、导入和侧载元数据、设置持久化、历史与统计、备份、流量、身份和应用数据编排。平台账号与缓存能力由 `:platform` 提供，数据库 schema 和 DAO 归 `:database`。

源码保留 `data` 包名，按具体职责细分目录。共享契约来自 `:model`，文件能力来自 `:storage`，平台接入来自 `:platform`；模块同时使用下载基础、`:lyrics`、网络和一起听能力，不依赖播放器运行库或 app。

同步的 Android 宿主、歌曲与歌单映射、封面持久化、通知和 WorkManager 适配位于 `data/sync/host`、`mapping`、`cover`、`work`，通过 `:sync` 的接口连接会话。Worker 类全名、存储文件名和键名属于升级兼容边界。

设置 schema 在本库运行 KSP，Compose 设置渲染留在 app。歌词设置只维护读取与持久化，默认值选择、归一化与有效时间偏移计算由 `:lyrics` 共用。

Room 执行存储、持久化实体和运行私有状态不作为业务调用接口；消费者使用映射、快照与宿主端口。新增生产代码不得回流 app 的 `data` 目录。

`verifyCrap` 检查本库共享范围，`verifySyncIntegrationCrap` 独立检查整个同步 Android 适配目录，两者阈值均为任意受检方法大于 9 即失败。

## 🧪 验证

```bash
./gradlew :local:verifyCrap :local:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
