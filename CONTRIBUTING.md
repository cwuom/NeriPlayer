## Contributing to NeriPlayer / 贡献指南

感谢你愿意为 NeriPlayer 做出贡献！本指南旨在帮助你了解项目定位、快速搭建开发环境、理解核心结构，并高效地提交修改。请在创建 PR 前完整阅读本页内容。

---

### 目标与原则 / Goals & Principles
- **学习与研究优先**：项目仅供学习与研究使用，请勿将其用于任何非法用途。
- **隐私与合规**：不上传、不分发第三方媒体；不收集个人信息。
- **可维护性**：保持代码清晰、可读、可测试，避免无谓复杂度。
- **GPL-3.0**：所有贡献均将以 GPL-3.0 协议发布。
---

### 开发环境 / Development Environment
- **Android Studio**：最新稳定版。
- **Android Gradle Plugin (AGP)**：8.13.0（版本由 `gradle/libs.versions.toml` 管理）。
- **Android SDK**：`compileSdk = 36`，`targetSdk = 36`，`minSdk = 28`。
- **Java/Kotlin 目标**：Java 17、Kotlin `jvmTarget = 17`。
- **UI**：Jetpack Compose（通过 BOM 对齐）与 Material 3。

提示：构建版本名会基于 Git 短提交与时间戳生成，请确保本地已安装 Git 并在仓库目录内执行构建命令。

---

### 快速开始 / Quick Start
1. 克隆并打开项目：
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 使用 Android Studio 同步依赖（首次打开会自动执行）。
3. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. 安装到 Android 9+ 设备：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. 启用调试入口：在应用设置界面连续点击“版本号”7 次开启开发者模式，用于自测与问题反馈。
---

### 构建发布版 / Release Build
发布版默认启用代码混淆与资源收缩。

1. 在 `~/.gradle/gradle.properties` 或环境变量中提供签名信息：
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```
2. 执行命令：
   ```bash
   ./gradlew :app:assembleRelease
   ```
3. 产物位于 `app/build/outputs/apk/release/NeriPlayer-<git>.<MMddHHmm>.apk`。

请勿将证书或密码提交到仓库，也不要在 Issue/PR 中公开相关敏感信息。

---

### 项目结构与扩展点 / Project Structure & Extension Points
- `app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt`
  - 全局 Service Locator：新增单例、仓库、API 客户端或播放器拓展时在此注册。
- `core/api` 平台 API
  - `netease/`：网易云客户端 `NeteaseClient`。
  - `bili/`：B 站客户端 `BiliClient` 与播放仓库 `BiliPlaybackRepository`。
  - `search/`：跨平台搜索接口 `SearchApi` 及网易云、QQ 音乐等实现。

- `core/player` 播放层
  - `PlayerManager`：播放队列、状态、缓存策略、失败重试与事件聚合。
  - `AudioPlayerService`：前台服务、通知、媒体按钮交互。
  - `ConditionalHttpDataSourceFactory`：为特定域名动态附加 UA/Cookie/Referer。
  - `ReactiveRenderersFactory` / `AudioReactive`：实时音频能量分析驱动动态背景。

- `data` 数据与持久化
  - `SettingsRepository`、`NeteaseCookieRepository`、`BiliCookieRepository`：基于 DataStore 的设置与 Cookie 持久化。
  - `LocalPlaylistRepository`：本地歌单/收藏持久化（JSON 原子写）。

- `ui`
  - Jetpack Compose 构建的导航、Now Playing、调试工具等页面。
  - `view/HyperBackground.kt` + `assets/hyper_background_effect.frag`：音频驱动的 RuntimeShader 背景。


常见扩展路径：
- **新增搜索平台**：实现 `SearchApi` → 在 `AppContainer` 注册 → 更新 `MusicPlatform`/`SearchManager` → UI 侧提供入口。
- **新增取流平台**：参考 B 站实现客户端与仓库 → 在 `PlayerManager.resolveSongUrl()` 接入 → 按需扩展数据源或 Header。
- **新增设置项**：增添 `SettingsKeys` → 在 `SettingsRepository` 暴露 Flow 与 setter → `NeriApp` 收集 → `SettingsScreen` 提供交互。
---

### 编码规范 / Code Style
- **Kotlin / 协程**：
  - I/O 与网络调用放在 `Dispatchers.IO` 或 `withContext(Dispatchers.IO)` 中执行，避免阻塞主线程。
  - 倾向早返回与浅层分支，谨慎使用 `!!`，异常需记录或语义化处理。
  - 对外暴露的异步状态推荐使用 `StateFlow/SharedFlow`，内部使用可变版本管理状态。
- **Compose**：
  - 状态向上托管，组件通过参数传入状态与回调；可恢复状态使用 `rememberSaveable`。
  - 列表提供稳定 key，合理拆分组合，避免在组合期间创建大对象或执行耗时逻辑。
- **状态与资源管理**：
  - 文案放入 `strings.xml`，资源命名使用小写加下划线，保持一致性。
  - 避免在 UI 热路径打印大 JSON 或分配昂贵对象。
- **日志与调试**：统一使用 `NPLogger`；如需文件日志，可在调试模式下开启 `NPLogger.init(..., enableFileLogging = true)`。
- **依赖注入**：统一通过 `AppContainer` 管理单例，避免在组件内部重复创建网络客户端或仓库。
- **网络与平台细节**：
  - **NetEase**：复用 `NeteaseClient.request()` 及 `CryptoMode`，注意 `__csrf`、`os=pc`、`appver` 注入与压缩编码处理。
  - **BiliBili**：通过 `getJsonWbi()` 自动签名并缓存 mixin key；`ConditionalHttpDataSourceFactory` 仅对指定 host 注入 Header。
  - **代理**：如需绕过系统代理，传入 `bypassProxy = true`。
- **播放器**：
  - ExoPlayer 实例由 `PlayerManager.initialize()` 创建，缓存使用 `SimpleCache` + LRU（10GB）。
  - 随机播放采用 `shuffleHistory/shuffleFuture/shuffleBag` 模型，确保“上一首/下一首”可逆向。
  - 连续失败保护阈值为 `MAX_CONSECUTIVE_FAILURES = 10`，超过后会停止播放并提示用户。
- **隐私数据**：禁止硬编码 Cookie、Token、账号等敏感信息，统一通过登录流程与 DataStore 处理。
- **提交信息**：建议遵循 Conventional Commits（如 `feat: ...`、`fix: ...`、`docs: ...`）。
---

### 调试与日志 / Debugging & Logs
- 过滤应用日志：
```bash
  adb logcat | grep "[NeriPlayer]"
  ```
- 调试模式可在设置页连点“版本号”7 次启用，启用后默认打开文件日志（`NPLogger.init(..., enableFileLogging = true)`）。
- 反馈问题时附上复现步骤、日志截取以及设备/系统信息。
---

### 提交前自检 / Pre-PR Checklist
- 功能在 Android 9+ 设备或模拟器上验证通过。
- 未引入未使用的依赖、资源或构建产物。
- 未提交任何敏感信息（证书、Cookie、令牌、个人数据等）。
- 关键逻辑已编写必要注释，保持良好可读性。

---

### Git 与 PR 工作流 / Git & PR Workflow
- 基于 `master` 创建功能分支（如 `feat/<name>`、`fix/<name>`）。
- Commit 信息遵循 Conventional Commits 格式。
- PR 描述需包含：变更动机、实现概述、风险与影响、测试方式以及相关截图（若涉及 UI）。
- 不要提交生成的 APK、签名文件、IDE 配置或缓存日志等构建产物。
- 大型变更可先创建 Draft PR 以便早期讨论。

---

### 问题反馈 / Bug Reports
- 在 Issues 中按照模板填写问题，提供：系统版本、机型、应用版本、复现步骤、关键日志。
- 若涉及第三方平台接口异常，请说明使用的账号类型与网络环境（如代理配置）。

---

### 法律与许可 / Legal & License
- 本项目使用 **GPL-3.0** 许可。
- 提交贡献即表示同意以 GPL-3.0 许可分发你的修改。

---

### 沟通与支持 / Communication
- Issues：用于缺陷报告、功能建议与一般讨论。
- 更多背景与项目介绍请参阅 [README.md](./README.md)。

欢迎你的想法、建议与 PR！谢谢！

---

### 目录结构与关键实现 / Directory Layout & Key Implementations

以下列出常用目录与核心文件，帮助你快速定位扩展点：

- `app/src/main/AndroidManifest.xml`
  - 应用入口、权限与前台服务（`AudioPlayerService`）声明；`NeriPlayerApplication` 为 `Application` 类。

- `moe/ouom/neriplayer/activity/`
  - `MainActivity.kt`：宿主 Activity，负责动态色、免责声明、媒体弹窗与顶层 `NeriApp()` 组合。
  - `NeteaseWebLoginActivity.kt`、`BiliWebLoginActivity.kt`：第三方平台 Web 登录流程，登录成功后由 DataStore 持久化 Cookie。 `BiliWebLoginActivity.kt`: 第三方平台 Web 登录流程（注：使用后 Cookie 由 `DataStore` 持久化）。

- `moe/ouom/neriplayer/core/di/`
  - `AppContainer.kt`：Service Locator，集中管理 `SettingsRepository`、Cookie 仓库、`NeteaseClient`、`BiliClient`、`BiliPlaybackRepository`、搜索 API 等。新增平台/仓库/客户端时请在此注册。

- `moe/ouom/neriplayer/core/api/`
  - `netease/`
    - `NeteaseClient.kt`：封装网易云接口访问与加密（`CryptoMode`: WEAPI/EAPI/Linux/API），维护 Cookie 注入、CSRF 处理、歌曲/歌单/歌词等业务接口。
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
  - `NeriApp.kt`：应用骨架（`NavHost`、底部栏、迷你播放器、Now Playing 过场），整合背景渲染与前台服务同步。
  - `theme/NeriTheme.kt`：统一主题（动态色/自定义种子色）。
  - `component/`：复用 UI 组件（`NeriMiniPlayer`、歌词组件、滑块等）。
  - `screen/`：各页面（`NowPlayingScreen` 播放页、`tab/` 主 tab、`playlist/` 详情、`debug/` 调试工具）。
  - `view/HyperBackground.kt` + `view/BgEffectPainter.java`：Android 13+ RuntimeShader 动态背景实现。

- `moe/ouom/neriplayer/navigation/Destinations.kt`：统一导航 route 定义与参数编码工具。
- `moe/ouom/neriplayer/util/`：工具集（`NPLogger`、`SearchManager`、`Formatters`、`Haptic`、`JsonUtil` 等）。
- `app/src/main/assets/`：着色器与静态资源（如 `hyper_background_effect.frag`）。
- `app/src/main/res/`：资源目录（图标、字符串、多主题 values 等）。
关键流程示意：
- **网易云取流**：`PlayerManager.getNeteaseSongUrl()` → `NeteaseClient.getSongDownloadUrl()` → ExoPlayer 播放。
- **B 站取流**：`PlayerManager.getBiliAudioUrl()` → `BiliClient.getPlayInfoByBvid()` → `BiliPlaybackRepository.getBestPlayableAudio()` → `ConditionalHttpDataSourceFactory` 附加 Header → ExoPlayer 播放。
- **跨平台搜索**：`NowPlayingViewModel` → `SearchManager` → `SearchApi` 实现 → `PlayerManager.replaceMetadataFromSearch()` 更新元数据/歌词/封面。
- **背景反应**：`ReactiveRenderersFactory` → `AudioReactive.level/beat` → `HyperBackground` → RuntimeShader 渲染。
---

### 扩展指南 / How-To Extend

- **新增搜索平台**：
  1. 在 `core/api/search/` 新建 `<Platform>NameSearchApi` 实现 `SearchApi`。
  2. 在 `AppContainer` 注册单例。
  3. 在 `MusicPlatform` 与 `SearchManager` 中接入路由。
  4. 根据需要在 UI 层提供入口（例如 Now Playing 的“获取歌曲信息”对话框）。

- **新增取流平台**：
  1. 参考 `BiliClient`/`BiliPlaybackRepository` 设计客户端与仓库。
  2. 如需自定义 Header/Cookie，仿照 `ConditionalHttpDataSourceFactory`。
  3. 在 `PlayerManager.resolveSongUrl()` 中接入解析逻辑。
  4. 平台标识建议统一写入 `SongItem.album`（如 `"Bilibili|<cid>"`）。

- **新增设置项**：
  1. 在 `SettingsKeys` 增加 key。
  2. 在 `SettingsRepository` 暴露对应 Flow 与 setter。
  3. 在 `NeriApp.kt` 中收集设置值并传递给 `SettingsScreen`。
  4. 在 `SettingsScreen` 中添加参数与 UI 控件，回调中使用 `scope.launch { repo.setXxx(...) }` 持久化。
  5. 若设置影响全局主题或密度，需在 `NeriApp` 中同步处理（可参考 `uiDensityScale` 与 `seedColorHex`）。