# ▶️ 播放运行与设备接入

`:playback:runtime` 负责播放器门面、ExoPlayer 引擎、音源解析、播放服务与通知、音频焦点、USB 独占输出、浮窗和外部蓝牙歌词。播放决策、状态协调、音频计算和队列基础能力来自 `:playback:logic`。

源码按 `core/player` 内的职责子包组织。USB 设备、路由、会话、sink、恢复和确认逻辑分别维护；与设备和服务生命周期绑定的 USB policy 留在 `core/player/policy/usb`。

`host` 定义仓库、下载、一起听和呈现接口，app 在首次使用前安装环境。环境安装不代表仓库已就绪，`isReady` 保持初始化门控；安全模式和辅助进程可以使用启动策略而不触发仓库初始化。

本库不依赖下载运行实现，下载调用必须经过 `PlayerDownloadAccess`，状态契约使用 `:model` 的 `playback/storage`。服务声明、consumer R8 规则及既有类全名保持稳定，以兼容 Manifest、JNI 和已保存的播放状态。

来源与持久化能力通过 `:platform`、`:local`、`:storage`、`:database` 和宿主端口接入，一起听适配位于 `core/player/ltw`。应用在 `core/di/player` 组装环境，运行库不得反向依赖 app 或应用容器。

词幕 SDK、异步歌词输出控制器、请求取消、偏移快照和共用时间偏移计算归 `:lyrics`。本库的 `core/player/integration/lyrics` 通过窄加载端口消费 `:platform` 的歌词来源，并向输出组件提供歌曲、播放状态、位置和生命周期输入。

解码器 AAR 作为编译与测试接口使用，JNI 和 native 库由 app 打包，避免重复打包。设备、服务与 USB 行为需要 Android 集成验证；JVM 测试只证明其覆盖的计算和接口行为。

`verifyCrap` 保留原播放方法选择器与各职责目录范围，宿主接口、展示状态、一起听映射和 USB 策略继续受检。

## 🧪 验证

```bash
./gradlew :playback:runtime:verifyCrap :playback:runtime:lintDebug
./gradlew :playback:runtime:connectedDebugAndroidTest
./gradlew :app:verifyDomainDependencies
```

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../../README.md#模块结构)，共享门禁见 [质量工具说明](../../../tools_pub/quality/README.md)。
