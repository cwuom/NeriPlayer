# 🤝 一起听会话与协议

`:listentogether` 维护 Listen Together 的 HTTP/WebSocket 传输、消息编解码、协议边界与完整客户端会话：创建和加入房间、成员凭据、房间版本、连接恢复、控制确认、播放同步、共享音源和邀请。共享协议模型由 `:model` 提供。

`api/ltw` 按 HTTP、重连与 WebSocket 维护传输，`listentogether/protocol` 维护协议编解码和响应限额。HTTP 响应最多 2 MiB，WebSocket 文本最多 2 × 1024 × 1024 个字符，限额在解析前执行。共享 profile 校验由 `listentogether/profile` 维护。

`ListenTogetherSessionManager` 组装会话组件并暴露房间与连接状态。`data/ltw/session` 按状态、成员、连接、控制、音源、心跳和 socket 分类；各异步组件维护自己的任务与取消逻辑。`control` 构造事件与队列变更，`playback` 同步队列、权威音源与位置，`mapping` 管理跨平台身份，`invite` 和 `validation` 维护输入边界。

外部能力通过 `ListenTogetherPlaybackHost`、`ListenTogetherSongMapper` 和 `ListenTogetherPlatformHost` 注入。播放适配归 `:playback:runtime` 的 `core/player/ltw`，平台绑定归 app 的 `core/di/ltw`；歌曲转换端口不读取全局容器。库使用 `:platform` 的 YouTube 身份协议辅助和 `:common`，域规则禁止直接调用平台仓库、app 或 `PlayerManager`。

Android 接入只使用域门禁明确允许的线程、时钟、URI、唤醒锁、资源与本地化能力；输入校验文案通过 `Context.getString` 和 `LanguageManager` 生成。每个会话独立持有唤醒锁，结束实例须调用 `close()` 断开连接并取消实例拥有的协程作用域；调度器、单调时钟和主线程判断可注入。

协议变更须兼容 `np-submodule/NeriPlayer-LTW`。共享候选仅用于会话，不写入普通歌曲或离线缓存，关闭 `shareAudioLinks` 时立即清空。播放同步发出 `REMOTE_SYNC`，不能重入本地控制发送链路；队列变更保持重复歌曲的出现位置、连续插入顺序和当前曲目。

新实现加入所属职责包，宿主适配只连接能力，房间角色、版本门控、控制顺序、候选去重和恢复策略由本库维护。生产一起听代码不得回流 app。

`verifyCrap` 使用本库全部客户端、传输和协议 JVM 测试的真实覆盖率，完整检查三个源码族，包括默认参数、协程、lambda 和新目录；任意受检方法大于 9 即失败。`verifyDomainDependencies` 保持 runtime 与 protocol 的独立白名单，模块 `check` 执行两个门禁。报告位于本库 `build/reports/crap`、`domain-dependencies`、`tests` 和 `lint-results-debug.html`。

## 🧪 验证

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
python3 -B tools_pub/quality/module_boundaries.py
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
