# 一起听客户端

`:data:ltw` 维护 Listen Together 客户端的完整会话流程：创建和加入房间、成员凭据、房间版本、连接恢复、控制确认、播放同步、共享音源、邀请与输入校验。协议模型由 `:data:model` 维护，HTTP/WebSocket 传输位于 `:api:ltw`，消息编解码和响应限额位于 [`:core:ltw-protocol`](../../core/ltw-protocol/README.md)

## 职责与依赖

`ListenTogetherSessionManager` 组装会话组件并暴露房间与连接状态。`session` 按状态、成员、连接、控制、音源、心跳和 socket 分类；每个异步组件维护自己的任务和取消逻辑。`control` 构造兼容事件与队列变更，`playback` 分别同步队列、权威音源和播放位置，`mapping` 管理跨平台曲目身份与歌曲转换，`invite` 和 `validation` 维护输入边界

外部能力通过三个接口注入：

- `ListenTogetherPlaybackHost` 提供播放快照和命令，播放器实现位于 `core/player/ltw`
- `ListenTogetherSongMapper` 提供本地歌曲识别、曲目转换和已解析播放候选；默认实现不读取歌曲仓库或全局容器
- `ListenTogetherPlatformHost` 提供应用上下文、初始化状态、前台服务与资源文案；app 在 `core/di/ltw` 绑定该接口

模块不依赖 app、`PlayerManager` 或数据仓库。Android 框架访问仅允许依赖门禁中明确列出的线程、时钟、URI 和唤醒锁能力。唤醒锁由每个会话独立持有；结束会话实例时调用 `close()`，它断开连接并取消实例拥有的协程作用域。调度器、单调时钟与主线程判断可注入，便于验证网络回调和播放命令的时序

## 扩展约束

新业务实现应加入所属职责包，不能返回 `app/listentogether`。包名与目录保持一致，单文件少于 2000 行，每个生产目录最多 16 个直接 Kotlin/Java 文件。宿主适配器只连接能力，房间角色、版本门控、控制顺序、流候选去重和恢复策略由本模块维护

协议变更须兼容 `np-submodule/NeriPlayer-LTW`。共享候选只用于会话，不写入普通歌曲或离线缓存；关闭 `shareAudioLinks` 时立即清空候选。播放同步发出 `REMOTE_SYNC` 命令，不能重新进入本地控制发送链路。队列变更须保留重复歌曲的出现位置、连续插入顺序和当前曲目

## 验证

```bash
./gradlew :data:ltw:verifyCrap :data:ltw:verifyDomainDependencies :data:ltw:lintDebug
./gradlew :api:ltw:testDebugUnitTest :core:ltw-protocol:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
python3 -B tools_pub/quality/module_boundaries.py
```

`verifyCrap` 执行客户端与协议库的 JVM 测试，合并真实 JaCoCo 执行数据和源码，检查两者全部生产方法，包括默认参数、协程和 lambda。任意 CRAP 分数大于 9 即失败，新文件自动受检，不使用历史 baseline 或平均分豁免。`verifyDomainDependencies` 检查编译类的允许依赖，禁止反向调用宿主实现。模块 `check` 和 Android 的一起听 CI 均调用这两个门禁

报告位于 `build/reports/crap`、`build/reports/domain-dependencies`、`build/reports/tests` 和 `build/reports/lint-results-debug.html`。模块布局与共享检查规则见根目录 [README](../../../README.md) 和 [质量工具说明](../../../tools_pub/quality/README.md)
