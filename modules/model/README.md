# 📦 共享模型与契约

`:model` 维护跨库传递的歌曲、歌单、播放与队列状态、下载执行状态、平台响应、歌词、设置、同步和一起听协议模型。

主要源码位于 `data/model`，部分兼容契约保留在 `data/sync/model`、`core/download/naming` 和 `ui/viewmodel`。源码目录归属由本库确定，原 Kotlin 包名和生产类全名保持稳定。

本库不依赖项目实现。序列化与 Parcelable 支持服务于边界传递；模型不得读取仓库、数据库、播放器或全局容器。更改字段、默认值、枚举和序列化名称时，应同时检查历史载荷与消费者兼容性。

同步模型的 JVM 覆盖率会并入 `:sync:verifyCrap`；全项目覆盖率仍按登记聚合本库。

## 🧪 验证

```bash
./gradlew :model:testDebugUnitTest :model:lintDebug
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
