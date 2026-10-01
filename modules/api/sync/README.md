# 同步传输

`:api:sync` 实现 GitHub Git Data 和 WebDAV 文件传输。调用方注入 `OkHttpClient` 与错误文案，模块不访问 Android Context、仓库、播放器或应用容器。

`github` 管理分支、raw 读取、blob/tree/commit 和非强制引用更新；`webdav` 管理认证、条件请求和冲突分类；`http` 统一响应大小限制与协程取消传播。共享传输结果位于 `:data:model`。

同步正文上限为 12 MiB，声明长度与未知长度流均受到限制。WebDAV 优先使用强 ETag，再使用 Last-Modified；并发冲突交给同步会话重新读取和合并。

```bash
./gradlew :api:sync:verifyCrap :api:sync:verifyDomainDependencies :api:sync:lintDebug
```

新增源码自动进入复杂度门禁，任意方法 CRAP 大于 9 即失败。依赖门禁只允许模型、HTTP 协议库、协程、日志及必要的标准库类型。
