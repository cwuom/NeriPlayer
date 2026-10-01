# 🧰 通用能力与资源

`:common` 提供跨领域使用的日志、集合与 JSON 辅助、请求代次、协程取消保护、文件原子写入、时间与单位转换，以及应用共用的文案和图标资源。

实现位于 `core/logging`、`util` 和少量通用存储辅助包。它不依赖其它项目实现，不承载平台仓库、下载执行、播放会话或同步规则；具有业务含义的数据契约归 `:model`。

源码契约测试工具通过 test fixtures 供播放与下载运行库复用。通用能力的测试随实现维护在本库；新增工具应先确认多个领域实际需要同一语义。

## 🧪 验证

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
