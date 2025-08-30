## Contributing to NeriPlayer / 贡献指南

感谢你愿意为 NeriPlayer 做出贡献！本指南将帮助你快速上手开发、提交修改和参与讨论。请在提交 PR 前阅读完本页。

---

### 目标与原则 / Goals & Principles
- **学习与研究优先**: 项目仅供学习与研究使用，请勿将其用于任何非法用途。
- **隐私与合规**: 不上传/分发第三方媒体；不收集个人信息；遵循第三方平台服务条款。
- **可维护性**: 代码清晰、可读、可测试，避免引入不必要复杂度。
- **GPL-3.0**: 贡献代码默认以 GPL-3.0 授权发布。

---

### 开发环境 / Development Environment
- **Android Studio**: 最新稳定版
- **Android Gradle Plugin (AGP)**: 8.12.2（由 `gradle/libs.versions.toml` 管理）
- **Android SDK**: compileSdk 36，minSdk 28，targetSdk 36
- **Java/Kotlin 目标**: Java 11（`jvmTarget = 11`）
- **UI**: Jetpack Compose（BOM 对齐）、Material 3

提示:
- 构建版本名基于 Git 短提交和时间戳；请确保本地安装了 Git。

---

### 快速开始 / Quick Start
1) 克隆并打开项目
```bash
git clone https://github.com/cwuom/NeriPlayer.git
cd NeriPlayer
```

2) 同步依赖（Android Studio 会自动执行）

3) 运行 Debug 构建
```bash
./gradlew :app:assembleDebug
```

4) 安装到设备（Android 9+）
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

5) 启用调试功能（用于反馈/自测）
- 设置页面连续点击“版本号”7次以开启开发者模式与调试入口。

---

### 构建发布版 / Release Build
发布版已启用混淆与资源收缩。

在你的 `~/.gradle/gradle.properties` 或命令行中提供签名参数：
```properties
KEYSTORE_FILE=/absolute/path/to/neri.jks
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=key0
KEY_PASSWORD=your_key_password
```

构建命令：
```bash
./gradlew :app:assembleRelease
```

产物命名：`app/release/NeriPlayer-<git>.<MMddHHmm>.apk`

注意：不要将证书或密码提交到仓库。

---

### 项目结构与扩展点 / Project Structure & Extension Points
- `app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt`
  - 全局 Service Locator。新增单例、仓库、API 客户端时请在此注册。

- `core/api` 平台 API
  - `netease/`：网易云客户端 `NeteaseClient`
  - `bili/`：B站客户端 `BiliClient` + 播放仓库 `BiliPlaybackRepository`
  - `search/`：跨平台搜索接口 `SearchApi` 及实现（网易云、QQ 音乐）

- `core/player` 播放层
  - `PlayerManager`：播放队列/状态/事件聚合
  - `AudioPlayerService`：前台服务 + 媒体通知/媒体按钮
  - `ConditionalHttpDataSourceFactory`：为特定域名动态附加 UA/Cookie/Referer

- `data` 数据与持久化
  - DataStore 封装：`SettingsRepository`、`NeteaseCookieRepository`、`BiliCookieRepository`

- `ui` 采用 Jetpack Compose（含 `screen/debug` 调试页面）

常见扩展：
- 新增搜索平台：
  1. 实现 `SearchApi`
  2. 在 `AppContainer` 注册实例
  3. 在 `MusicPlatform` 与 `SearchManager` 中接入路由
  4. 视图层按需新增入口/筛选

- 新增流媒体平台（播放）：
  1. 参考 B 站：实现平台客户端与音频流抽取
  2. 设计数据源接口（可类比 `BiliAudioDataSource`）与仓库（类比 `BiliPlaybackRepository`）
  3. 在 `PlayerManager.resolveSongUrl` 添加解析逻辑并在 `AppContainer` 注入依赖
  4. 如需附加头/鉴权，仿照 `ConditionalHttpDataSourceFactory`

---

### 编码规范 / Code Style
- Kotlin + 协程：
  - IO/网络使用 `Dispatchers.IO`；避免在主线程阻塞
  - 早返回、浅层分支；错误要记录并向上语义化传播
- Compose：
  - 保持状态不可变，尽量将副作用迁至 ViewModel/Manager
  - 避免不必要的重组；注意列表 key 与稳定性
- 命名：使用完整、清晰的英文名称；函数用动词，变量用名词
- 日志：统一使用 `NPLogger`；过滤标签可用 `[NeriPlayer]`（BuildConfig.TAG）
- 依赖注入：集中于 `AppContainer`，避免在多处重复创建网络客户端
- 不要硬编码隐私数据（Cookie、Token、账号），一律通过 DataStore/登录流程获得

建议提交信息遵循 Conventional Commits（如 `feat: ...`/`fix: ...`/`refactor: ...`/`docs: ...`）。

---

### 调试与日志 / Debugging & Logs
- 过滤应用日志：
```bash
adb logcat | grep "\[NeriPlayer\]"
```
- 问题反馈前请开启调试模式（设置页连点“版本号”7次）。
- 如需文件日志，可在应用初始化时调用 `NPLogger.init(context, enableFileLogging = true)`（默认未启用，在开启调试模式(DEBUG MODE)后会自动启用）。

---

### 提交前自检 / Pre-PR Checklist
- 功能在 Android 9+ 设备/模拟器上可运行
- 不引入未使用的依赖与资源
- 不提交私密信息（证书、Cookie、令牌、个人数据）
- 变更点有必要的注释与易读性

---

### Git 与 PR 工作流 / Git & PR Workflow
- 从 `master` 创建功能分支：`feat/<short-name>`、`fix/<short-name>` 等
- 提交信息建议遵循 Conventional Commits（如 `feat: ...`、`fix: ...`）
- PR 需简要描述动机、变更点、风险、测试方式和截图（UI 相关）
- 避免提交构建产物与环境文件：如 `app/release/*.apk`、`.idea/*`、本地签名文件、缓存/日志等
- 变更较大时，可先开草案 PR 进行早期讨论

---

### 问题反馈 / Bug Reports
- 打开 Issues，按照模板填写
- 提供：系统版本、机型、应用版本、可复现步骤、关键日志

---

### 法律与许可 / Legal & License
- 本项目使用 **GPL-3.0** 许可
- 贡献即表示同意以 GPL-3.0 许可分发你的修改

---

### 沟通与支持 / Communication
- Issues: 用于缺陷、需求、问题讨论
- 参考：`README.md`

欢迎你的想法、建议与 PR！谢谢！

---

### 目录结构与关键实现 / Directory Layout & Key Implementations

以下仅列出最常用的目录与核心文件，帮助你快速定位扩展点：

- `app/src/main/AndroidManifest.xml`
  - 应用入口、权限与前台服务（`AudioPlayerService`）声明；`NeriPlayerApplication` 为 `Application` 类。

- `moe/ouom/neriplayer/activity/`
  - `MainActivity.kt`: 应用宿主。主题/动态色、免责声明引导、媒体弹窗、顶层 `NeriApp()` 组合。
  - `NeteaseWebLoginActivity.kt`, `BiliWebLoginActivity.kt`: 第三方平台 Web 登录流程（注：使用后 Cookie 由 `DataStore` 持久化）。

- `moe/ouom/neriplayer/core/di/`
  - `AppContainer.kt`: Service Locator。集中管理单例依赖（`SettingsRepository`、Cookie 仓库、`NeteaseClient`、`BiliClient`、`BiliPlaybackRepository`、搜索 API 等）。新增平台/仓库/客户端时在此注册。

- `moe/ouom/neriplayer/core/api/`
  - `netease/`
    - `NeteaseClient.kt`: 封装网易云接口访问与加密（`CryptoMode`: WEAPI/EAPI/Linux/API），维护持久化 Cookie 注入、CSRF 处理、`getSongDownloadUrl`、歌词、歌单等业务接口。扩展接口时优先走 `request()/callWeApi()/callEApi()`。
  - `bili/`
    - `BiliClient.kt`: 封装 B 站 Web 接口访问（WBI 签名、Nav 获取 mixin key 缓存、视频基础信息/分页/取流、搜索、收藏夹、点赞等）。
    - `BiliPlaybackRepository.kt`: 按用户偏好音质从 `BiliClientAudioDataSource.fetchAudioStreams` 中选择最佳音轨。
  - `search/`
    - `SearchApi.kt`: 跨平台搜索接口（`search()`/`getSongInfo()`）。
    - `CloudMusicSearchApi.kt`: 基于 `NeteaseClient` 的网易云实现，包含歌曲信息与歌词并发获取。
    - `QQMusicSearchApi.kt`: QQ 音乐实现（JSON 解析、Base64 歌词处理）。

- `moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`: 播放核心（队列/状态/事件/随机洗牌/进度同步/收藏/缓存键策略/解析源 URL）。统一入口：`playPlaylist()`、`next()`、`previous()`、`togglePlayPause()` 等。
  - `AudioPlayerService.kt`: 前台媒体服务与通知（媒体按钮、收藏切换等），订阅 `PlayerManager` 的 `StateFlow` 同步 UI。
  - `ReactiveRenderersFactory.kt`: 在 Media3 中插入 `TeeAudioProcessor`，以收集 PCM 实时能量送给 `AudioReactive`。
  - `AudioReactive.kt`: 实时音频分析（RMS、包络、鼓点脉冲），对外暴露 `level` 与 `beat` 的 `StateFlow`。
  - `ConditionalHttpDataSourceFactory.kt`: 针对 `bilivideo.`/`upos-hz-` 的请求自动附加 UA/Referer/Cookie，确保 B 站音轨拉流可用。

- `moe/ouom/neriplayer/data/`
  - `SettingsDataStore.kt`（`SettingsRepository`）: 所有设置项（主题/动态色/音质/开发者模式/背景图等）基于 `DataStore` 的 `Flow` 暴露。
  - `NeteaseCookieRepository.kt`, `BiliCookieRepository.kt`: 各平台 Cookie 的持久化与流式观察。
  - `LocalPlaylistRepository.kt`: 本地歌单/收藏持久化（JSON 原子写），提供增删改查与排序、导出等操作。

- `moe/ouom/neriplayer/ui/`
  - `NeriApp.kt`: 应用骨架（`NavHost`、底部栏、迷你播放器、NowPlaying 过场），整合背景渲染与前台服务同步。
  - `theme/NeriTheme.kt`: 统一主题（动态色/自定义种子色）。
  - `component/`: 复用 UI 组件（`NeriMiniPlayer`、歌词组件、滑块等）。
  - `screen/`: 各页面（`NowPlayingScreen` 播放页、`tab/` 主 tab、`playlist/` 详情、`debug/` 调试工具）。
  - `view/HyperBackground.kt` + `view/BgEffectPainter.java`: Android 13+ RuntimeShader 动态背景，受 `AudioReactive` 驱动；片元着色器位于 `assets/hyper_background_effect.frag`。

- `moe/ouom/neriplayer/navigation/Destinations.kt`: 统一导航 route 定义与入参编码工具。
- `moe/ouom/neriplayer/util/`: 工具集（`NPLogger` 日志、`SearchManager`、`Formatters`、`Haptic`、`JsonUtil` 等）。
- `app/src/main/assets/`: 着色器与资源（`hyper_background_effect.frag`）。
- `app/src/main/res/`: 资源（图标、布局、字符串、多主题 values 目录）。

关键流程示意：
- 网易云取流：`PlayerManager.getNeteaseSongUrl()` → `NeteaseClient.getSongDownloadUrl()` → ExoPlayer 播放。
- B 站取流：`PlayerManager.getBiliAudioUrl()` → `BiliClient.getPlayInfoByBvid()` → `BiliPlaybackRepository.getBestPlayableAudio()` → `ConditionalHttpDataSourceFactory` 附加头 → ExoPlayer 播放。
- 搜索匹配：`NowPlayingViewModel` → `SearchManager` → `SearchApi` 实现 → `PlayerManager.replaceMetadataFromSearch()` 更新元数据/歌词/封面。
- 背景反应：`ReactiveRenderersFactory` → `AudioReactive.level/beat` → `HyperBackground` → `RuntimeShader`。

---

### 扩展指南 / How-To Extend

- 新增搜索平台：
  1. 在 `core/api/search/` 新建 `<Platform>NameSearchApi` 实现 `SearchApi`；
  2. 在 `AppContainer` 注册单例；
  3. 在 `MusicPlatform` 与 `SearchManager` 路由；
  4. UI 侧按需提供平台切换入口（如 NowPlaying “获取歌曲信息”）。

- 新增取流平台：
  1. 参考 `BiliClient`/`BiliPlaybackRepository` 设计客户端与仓库；
  2. 若需自定义 Header/Cookie，仿照 `ConditionalHttpDataSourceFactory`；
  3. 在 `PlayerManager.resolveSongUrl()` 接入；
  4. 资源标识最好内嵌于 `SongItem.album`（如 `"Bilibili|<cid>"`）。

- 新增设置项：
  1. 在 `SettingsKeys` 增加 key；
  2. 在 `SettingsRepository` 提供对应 `Flow` 与 setter；
  3. 在 UI 中通过 `collectAsState()` 订阅并落地交互。
  4. 在 `SettingsScreen` 中（`app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsScreen.kt`）
     - 在 `SettingsScreen(...)` 的参数列表中新增你的设置值与回调，例如：
       - 布尔型：`myFeatureEnabled: Boolean, onMyFeatureEnabledChange: (Boolean) -> Unit`
       - 数值型：`myFloatValue: Float, onMyFloatValueChange: (Float) -> Unit`
     - 在界面中添加对应的交互控件并调用回调：
```kotlin
// 布尔开关示例
ListItem(
    leadingContent = {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface
        )
    },
    headlineContent = { Text("示例开关") },
    supportingContent = { Text("这是一项新的设置") },
    trailingContent = {
        Switch(checked = myFeatureEnabled, onCheckedChange = onMyFeatureEnabledChange)
    },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
)

// 数值滑块示例
ListItem(
    headlineContent = { Text("示例滑块") },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    supportingContent = {
        Slider(
            value = myFloatValue,
            onValueChange = onMyFloatValueChange,
            valueRange = 0f..1f
        )
    }
)
```
  - 如需在切换时立刻触发系统/全局副作用（例如夜间模式），可在 onCheckedChange 内额外调用工具函数（参考强制深色使用的 `NightModeHelper.applyNightMode(...)`）。

- 5. 在 `NeriApp.kt` 中（`app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`）
  - 从 `SettingsRepository` 收集你的设置 `Flow`：
```kotlin
val myFeatureEnabled by repo.myFeatureEnabledFlow.collectAsState(initial = false)
// 数值型
val myFloatValue by repo.myFloatValueFlow.collectAsState(initial = 0.5f)
```
  - 将值与“setter”回调传给 `SettingsScreen`（使用 `scope.launch { repo.setXxx(...) }`）：
```kotlin
SettingsScreen(
    // ...
    myFeatureEnabled = myFeatureEnabled,
    onMyFeatureEnabledChange = { enabled ->
        scope.launch { repo.setMyFeatureEnabled(enabled) }
    },
    myFloatValue = myFloatValue,
    onMyFloatValueChange = { v ->
        scope.launch { repo.setMyFloatValue(v) }
    },
    // ...
)
```
  - 若设置影响全局主题或密度（如 `uiDensityScale`、主题色等），在 `NeriApp` 中与 `NeriTheme` 或 `CompositionLocal` 配置处一并生效（参考已有的 `uiDensityScale` 和 `seedColorHex` 用法）。

- 小结：增 key -> 暴露 Flow 与 setter -> `NeriApp` 收集并传参 -> `SettingsScreen` 增参数与控件 -> 必要时在回调或主题层处理副作用。

---

### 代码规范 / Code Conventions

#### 通用：
- **Kotlin 可读性**: 早返回、浅层分支；异常必须被记录或语义化处理；避免滥用 `!!`。
- **协程**: I/O 密集使用 `withContext(Dispatchers.IO)`；不要在 Compose Composable 内直接做阻塞；避免 `GlobalScope`。
- **流式状态**: 对外暴露 `StateFlow/SharedFlow`，内部使用 `MutableStateFlow/MutableSharedFlow`；UI 订阅用 `collectAsState()`。
- **错误处理**: 网络/解析异常统一用 `try/catch` 并 `NPLogger.e()`；必要时通过 `PlayerEvent` 通知 UI。
- **日志**: 统一 `NPLogger`（支持文件日志），避免在 UI 热路径打印大 JSON（`CloudMusicSearchApi.logLongJson` 有分片打印）。
- **依赖注入**: 统一在 `AppContainer` 管理；避免在组件内部重复创建网络客户端。
- **资源与本地化**: 文案建议放 `strings.xml`；新增图标放 `res/drawable(或 mipmap)` 并遵循命名规范（小写下划线）。
- **版本与依赖**: 使用 `gradle/libs.versions.toml` 的 Version Catalog；Compose 依赖使用 BOM。

#### Compose：
- 状态托管：组件对外暴露事件与状态，尽量无副作用；使用 `rememberSaveable` 存储可恢复状态。
- 性能：列表提供稳定 key；避免在组合期间创建重对象；动画与过渡使用 `AnimatedVisibility/AnimatedContent` 等。
- 主题：仅通过 `NeriTheme`/`MaterialTheme` 获取色彩与排版；背景混合时注意容器色透明策略。

#### 网络与平台：
- NetEase：新增接口优先复用 `NeteaseClient.request()` 与 `CryptoMode`；注意 `__csrf`、`os=pc`、`appver` 注入；响应压缩（br/gzip）需解码。
- BiliBili：WBI 接口统一通过 `getJsonWbi()`，会自动签名与缓存 mixin key；`ConditionalHttpDataSourceFactory` 仅对指定 host 注入 Cookie/Referer/UA。
- 代理：如需屏蔽系统代理，传 `bypassProxy=true`（默认）。

#### 播放器：
- ExoPlayer 实例在 `PlayerManager.initialize()` 创建；缓存采用 `SimpleCache` + LRU（10GB），自定义 CacheKey 避免不同音质冲突。
- 随机播放：`shuffleHistory/shuffleFuture/shuffleBag` 三栈模型，确保“上一首/下一首”在洗牌模式下可逆向。
- 连续失败保护：`MAX_CONSECUTIVE_FAILURES = 10`，达到阈值会停止播放并提示。

#### 提交规范：
- Commit 遵循 Conventional Commits：`feat|fix|refactor|perf|docs|test|build|chore(scope): message`。
- PR 描述需包含：动机、实现概述、风险、测试方式。

#### 安全与隐私：
- 不要在仓库中留下任何账号、Cookie、证书、下载链接等敏感信息。


