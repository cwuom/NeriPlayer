# 一起听协议支持

`:core:ltw-protocol` 维护 Android 客户端共用的协议边界，不依赖 app、播放器、Android 框架或 OkHttp。协议数据类型统一位于 `:data:model`；HTTP/WebSocket 连接由 `:api:ltw` 维护，房间业务位于 [`:data:ltw`](../../data/ltw/README.md)

`protocol` 提供统一 JSON 配置、有界 HTTP 响应读取、WebSocket 消息编解码和 `np_ping`/旧版 `ping`。HTTP 读取同时检查声明长度与实际读取字节数，上限为 2 MiB，并在成功或失败后关闭输入流。WebSocket 上限为 2 Mi 个字符，超限异常由传输层转换为协议错误与关闭代码；连接层继续负责过期 socket 回调隔离

`profile` 维护 UUID、默认昵称生成、昵称清洗和允许字符规则，供客户端与设置仓库共用。资源文案及应用偏好不进入协议库

```bash
./gradlew :core:ltw-protocol:testDebugUnitTest :core:ltw-protocol:lintDebug
./gradlew :data:ltw:verifyCrap :data:ltw:verifyDomainDependencies
```

一起听客户端门禁合并本模块测试覆盖率，全部生产方法和新增文件自动参与 CRAP 检查，分数大于 9 即失败。依赖门禁只允许协议模型、序列化和标准库。报告位于 `modules/data/ltw/build/reports/crap` 与 `modules/data/ltw/build/reports/domain-dependencies`

依赖方向和协议兼容规则见根目录 [README](../../../README.md) 与 [贡献指南](../../../CONTRIBUTING.md)
