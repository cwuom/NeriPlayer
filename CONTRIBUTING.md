[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer / 贡献指南

感谢你愿意为 NeriPlayer 做出贡献。
本文只描述**当前 Android 端真实实现**，并尽量与源码保持一致。

---

### 项目定位 / Scope

- NeriPlayer 是一个**原生 Android 音频播放器**，不是公共云端曲库服务。
- 当前在线内容能力主要来自 **网易云音乐**、**Bilibili** 与 **YouTube Music**。
- 播放页的元数据/歌词补全链路目前使用 **网易云 + QQ 音乐**。
- 数据默认保存在本地；GitHub 同步是**可选**能力，且同步对象是歌单、
  收藏、播放历史等元数据，不是媒体文件本身。

---

### 开发环境 / Development Environment

- **Android Studio**：最新稳定版
- **compileSdk**：36
- **targetSdk**：36
- **minSdk**：28
- **Java / Kotlin**：Java 17，Kotlin `jvmTarget = 17`
- **版本名格式**：`<git短哈希>.<MMddHHmm>`
- **Release APK 文件名**：`NeriPlayer-<versionName>.apk`

补充说明：

- 构建脚本会读取 Git 短提交生成版本名，本地请确保已安装 Git。
- 依赖版本由 `gradle/libs.versions.toml` 与各模块 `build.gradle.kts` 管理。

---

### 快速开始 / Quick Start

1. 克隆仓库：
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. 安装到设备：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. 首次启动时，应用会先进入免责声明阶段；Android 13+ 设备会请求通知权限。
5. 如需调试入口，在设置页连续点击**版本号** 7 次，底栏会出现独立 `Debug` 页。

---

### 构建发布版 / Release Build

发布版默认启用混淆与资源收缩。

1. 在 `~/.gradle/gradle.properties` 或环境变量中提供签名信息：
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```
2. 执行：
   ```bash
   ./gradlew :app:assembleRelease
   ```
3. 产物位于 `app/build/outputs/apk/release/`，文件名格式为：
   ```text
   NeriPlayer-<git短哈希>.<MMddHHmm>.apk
   ```

安全提醒：

- 不要提交 keystore、密码、Cookie、Token 或其他敏感信息。
- 不要在 Issue / PR 中粘贴完整授权信息。

---

### 项目结构与当前实现 / Project Layout

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - 应用初始化入口，负责语言、异常处理、`AppContainer`、
    全局下载管理和共享图片加载器。

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt` 是唯一对外入口，负责启动流程、免责声明、
    外部音频导入与顶层 Compose 宿主。
  - `NeteaseWebLoginActivity.kt`、`BiliWebLoginActivity.kt`
    与 `YouTubeWebLoginActivity.kt` 是内部登录页，不是对外主入口。

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - 顶层 Compose 应用骨架，负责 `NavHost`、动态底栏、
    `MiniPlayer`、`Now Playing` 覆盖层与 Debug 路由。

- `app/src/main/java/moe/ouom/neriplayer/core/api/`
  - `netease/`：网易云接口与账号能力。
  - `bili/`：Bilibili 搜索、播放信息与音频拉流相关实现。
  - `youtube/`：YouTube Music 客户端（基于 NewPipe Extractor）、
    播放仓库、PoToken 与 JS Challenge 相关支持。
  - `search/`：播放页元数据/歌词补全接口，
    当前实现为 `CloudMusicSearchApi` 与 `QQMusicSearchApi`。
  - `lyrics/`：歌词外部来源，当前实现为 `LrcLibClient`。

- `app/src/main/java/moe/ouom/neriplayer/util/SearchManager.kt`
  - 统一封装播放页元数据匹配逻辑。
  - 注意：它与 `Explore` 页的 UI 搜索不是同一套链路。

- `app/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`：Media3 ExoPlayer 的统一管理层，
    负责音源解析、播放队列、缓存、状态恢复与失败重试。
  - `AudioPlayerService.kt`：前台播放服务、媒体通知与媒体按钮控制。
  - `ConditionalHttpDataSourceFactory.kt`：
    为特定域名动态附加 `Referer / User-Agent / Cookie`。
  - `ReactiveRenderersFactory.kt`、`AudioReactive.kt`：
    为 Now Playing 的音频反应式动态背景提供实时音频能量。
  - `YouTubeGoogleVideoRangeSupport.kt`、`YouTubeSeekRefreshPolicy.kt`：
    YouTube Music 播放时处理 Google Video Range 请求与 Seek 刷新策略。
  - `SleepTimerManager.kt`：睡眠定时器管理。
  - `AudioDownloadManager.kt`：音频下载核心链路，解析多平台流获取直链，并保存到本地。

- `app/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt` 维护全局下载任务与本地已下载列表。

- `app/src/main/java/moe/ouom/neriplayer/data/`
  - `SettingsDataStore.kt`：设置与常规状态。
  - `LocalPlaylistRepository.kt`：本地歌单 JSON 原子写入。
  - `BackupManager.kt`：本地 JSON 导入/导出与差异分析。
  - `LocalAudioImportManager.kt`：导入外部音频、扫描本地音频，
    并复制附近歌词/封面 sidecar 文件。

- `app/src/main/java/moe/ouom/neriplayer/data/github/`
  - `GitHubSyncManager.kt`：同步编排与三路合并。
  - `GitHubSyncWorker.kt`：基于 `WorkManager` 的延迟/周期同步。
  - `SecureTokenStorage.kt`：GitHub Token 本地加密存储。

---

### 当前能力边界 / Current Boundaries

- `Explore` 当前是**网易精选歌单 + YouTube Music 歌单 +
  网易/Bilibili/YouTube Music 搜索**（YouTube Music 搜索暂未实现），
  Bilibili 还不是完整的发现流页面。
- `Library` 中的 QQ 音乐入口仍为占位状态，不要按“完整平台已接入”理解。
- `YouTube Music` 已实现登录、歌单浏览/详情、播放与下载；
  Explore 中已作为搜索源注册，但搜索功能暂未实现。
- 下载使用共享 `OkHttpClient` 写入应用专属目录，
  **不是**系统 `DownloadManager`、**不是**前台下载服务，
  当前也**没有断点续传**。
- 流媒体缓存与下载是两套能力：
  缓存使用 `SimpleCache`，下载则由 `AudioDownloadManager` 写入本地文件。
- GitHub 同步只同步元数据，不同步音频缓存、下载文件、Cookie 或播放 Token。
- 平台 Cookie 当前是本地 `DataStore` 持久化；
  只有 GitHub Token 使用 `Android Keystore + EncryptedSharedPreferences` 加密保存。

---

### 常见扩展路径 / Extension Paths

#### 1. 新增 Explore 搜索源

适用于要把新平台接到 `Explore` 页搜索或发现流时。

大致步骤：

1. 在 `core/api/` 下实现对应客户端或仓库。
2. 在 `ExploreViewModel` 中增加数据请求与状态映射。
3. 在 `ExploreScreen` / Host 页面中补充 UI 入口。
4. 如需播放，继续接入 `PlayerManager` 的音源解析链路。

#### 2. 新增播放页元数据补全源

适用于补封面、歌词、曲目信息，而不是扩展 `Explore` 页。

大致步骤：

1. 在 `core/api/search/` 下实现新的 `SearchApi`。
2. 在 `AppContainer` 中注册单例。
3. 在 `SearchManager` 中增加路由与匹配逻辑。
4. 视需要补充 `MusicPlatform` 等枚举或 UI 文案。

#### 3. 新增取流平台

1. 参考 `bili/` 目录设计客户端与播放仓库。
2. 如需特殊 Header，参考 `ConditionalHttpDataSourceFactory.kt`。
3. 在 `PlayerManager` 的 URL 解析链路中接入新平台。
4. 补充下载、歌词、封面等配套能力时，保持边界清晰，
   不要把“缓存”和“下载”混成一套实现。

#### 4. 新增设置项

1. 在 `SettingsDataStore.kt` 中增加 key、Flow 与 setter。
2. 在 `NeriApp.kt` 中收集并下发到对应页面。
3. 在 `SettingsScreen.kt` 中补充交互控件。
4. 如果设置影响全局主题、导航或播放层，
   需要同步调整 `NeriApp.kt` 的顶层状态。

#### 5. 修改 GitHub 同步

1. 优先理解 `GitHubSyncManager.kt` 的三路合并流程。
2. 注意同步对象包含歌单、收藏、最近播放与删除记录。
3. 不要破坏 `GitHubSyncWorker.kt` 的延迟同步、周期同步、
   validated network 检查与自动重试行为。
4. 涉及敏感信息时，统一走 `SecureTokenStorage.kt`，
   不要把 Token 放回 `DataStore` 或明文 JSON。

---

### 调试与日志 / Debugging & Logs

- 开发者模式开启方式：设置页连续点击**版本号** 7 次。
- 开启后底栏会出现独立 `Debug` 页

日志说明：

- 普通文件日志仅在开发者模式开启时启用。
- 崩溃日志由 `ExceptionHandler` 独立落盘，不依赖开发者模式。

常用命令：

```bash
adb logcat | findstr NeriPlayer
```

Linux / macOS 可改用：

```bash
adb logcat | grep NeriPlayer
```

---

### 测试与提交流程 / Testing & PR

提交前建议至少完成以下检查：

1. 能成功执行：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. 如修改了资源、UI、导航、设置或同步逻辑，建议再执行：
   ```bash
   ./gradlew :app:lintDebug
   ```
3. 如新增单元测试，放到 `app/src/test/`；
   如新增设备或 Compose UI 测试，放到 `app/src/androidTest/`。
4. 行为变更涉及 README、设置文案或用户流程时，请同步更新文档。

PR 建议包含：

- 变更动机
- 关键实现点
- 风险与兼容性影响
- 测试方式
- 如涉及 UI，附截图或录屏

不要提交：

- APK、签名文件、IDE 本地配置
- 缓存、日志、临时构建产物
- 授权 Cookie、Token、个人数据

Commit 信息建议遵循 Conventional Commits，
例如 `feat: ...`、`fix: ...`、`docs: ...`。

---

### 法律与许可 / Legal & License

- 项目仅供学习与研究使用，请勿用于非法用途。
- 本项目使用 **GPL-3.0** 协议。
- 提交贡献即表示你同意以 GPL-3.0 分发你的修改。

---

### 沟通方式 / Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues)：缺陷、功能建议、讨论
- [README.md](./README.md)：功能与使用说明

如你准备提交较大的结构性改动，建议先开 Issue 对齐方向。
