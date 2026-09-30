# 播放器集成

`:feature:player` 拥有 Media3 播放器、播放源解析、前台服务、队列持久化、USB 独占输出、平台音效、悬浮歌词和 Lyricon 输出。服务声明与 consumer R8 规则由本模块维护，原有类名保持稳定，以兼容 Manifest、JNI 和已有播放状态

`host` 定义仓库、下载、Listen Together 和呈现接口。app 在 `core/di/player` 绑定这些能力，并在播放器首次使用前安装环境。安装接口不等于仓库已准备好，`isReady` 保留初始化门控；安全模式和辅助进程可以使用启动策略而不触发仓库初始化

纯规则、状态协调和 PCM 分别位于 `:core:player-policy`、`:core:player-runtime`、`:core:player-audio`；队列算法位于 `:core:playback-queue`。本模块不依赖 app 或下载实现，下载状态契约位于 `:data:model` 的 `playback/storage`。结构门禁禁止直接依赖 `:feature:download`，下载调用必须经过 `PlayerDownloadAccess`

app 保留页面、入口、组件绑定，以及 C++ 和 FFmpeg AAR 的最终打包。app 的生产 `core/player` 目录必须保持不存在，新增播放器实现应加入本模块对应职责包

```bash
./gradlew :feature:player:verifyCrap :feature:player:lintDebug
./gradlew :feature:player:connectedDebugAndroidTest
```

JVM 测试位于 `src/test`，设备测试位于 `src/androidTest`。共享 CRAP scope 包含原有入口方法及 host、USB 规则、widget 呈现等完整职责包；受管方法分数大于 9 会使门禁失败。模块报告位于 `build/reports/crap`

模块布局、完整复杂度范围及依赖方向见仓库根目录 [README](../../../README.md) 和 [质量工具说明](../../../tools_pub/quality/README.md)
