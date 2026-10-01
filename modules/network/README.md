# 🌐 网络基础能力

`:network` 维护共用 HTTP 客户端基础、响应解析、Range 请求与续传边界、网络工具和网页登录辅助。音乐平台请求协议位于 `:platform` 的 `api` 包，同步与一起听传输随所属业务库维护，响应契约由 `:model` 提供。

生产代码按 `core/api`、`core/network/http/parser`、`core/network/range`、`core/network/weblogin` 和 `util/network` 组织。模块只依赖 `:common`，不读取账号仓库、播放器或应用容器。

修改响应体读取或 Range 行为时，应验证取消、截断响应、状态码与字节区间边界；平台特有的容错留在对应客户端，避免污染通用传输语义。

## 🧪 验证

```bash
./gradlew :network:testDebugUnitTest :network:lintDebug
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
