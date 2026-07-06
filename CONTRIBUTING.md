[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer / 贡献指南

感谢你愿意为 NeriPlayer 做出贡献。
本文描述**当前 Android 客户端和一起听 Worker 的真实实现**，
请以源码和运行行为为准同步维护文档。

---

### 项目定位 / Scope

- NeriPlayer 是一个**原生 Android 音频播放器**，不是公共云端曲库服务。
- 在线内容能力主要来自 **网易云音乐**、**Bilibili** 与 **YouTube Music**。
- 播放页元数据/歌词补全链路目前使用 **网易云 + QQ 音乐**，
  并接入 LRCLIB 外部歌词来源。
- 数据默认保存在本地；GitHub / WebDAV 同步是**可选能力**，
  同步对象是歌单、收藏、最近播放、播放统计等元数据，不是媒体文件本身。
- 一起听服务端在 `np-submodule/NeriPlayer-LTW`，基于 Cloudflare Workers
  与 Durable Objects。

---

### 开发环境 / Development Environment

- **Android Studio**：最新稳定版
- **JDK**：17
- **Kotlin**：2.2.x，JVM target 17
- **AGP**：8.13.x
- **compileSdk / targetSdk / minSdk**：36 / 36 / 28
- **NDK**：`27.0.12077973`
- **CMake**：`3.22.1`
- **Node.js**：20，用于一起听 Worker 检查
- **版本名格式**：`<git短哈希>.<MMddHHmm>`
- **Release APK 文件名**：`NeriPlayer-<versionName>[-abi].apk`

补充说明：

- 仓库依赖 Git 子模块，首次克隆请使用 `--recursive`，或手动执行
  `git submodule update --init --recursive`。
- 构建脚本会读取 Git 短提交生成版本名，本地请确保已安装 Git。
- 依赖版本由 `gradle/libs.versions.toml` 与各模块 `build.gradle.kts` 管理。
- 应用只保留 `zh` 与 `en` 资源，见 `build-logic` 的 locale filter。

---

### 快速开始 / Quick Start

1. 克隆仓库：
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
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
4. 首次启动会进入免责声明与启动引导；Android 13+ 会请求通知权限。
5. 如需调试入口，在设置页连续点击**版本号** 7 次，底栏会出现独立 `Debug` 页。

---

### 构建发布版 / Release Build

发布版默认启用混淆与资源收缩。
普通 `assembleRelease` 默认只构建 `arm64-v8a`，手动多 ABI 构建需要额外参数。

1. 在 `~/.gradle/gradle.properties`、项目 Gradle properties 或命令行 `-P`
   中提供签名信息：
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```

   如果 `KEYSTORE_FILE` 使用相对路径，会按 `app/` 模块目录解析。
   本地未提供 keystore 时，Release 构建会回退到 debug signing config，
   仅适合测试安装，不适合作为正式发布产物。

2. 构建默认 Release：
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. 构建多 ABI Release：
   ```bash
   ./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true
   ```

4. 产物位于 `app/build/outputs/apk/release/`，文件名格式为：
   ```text
   NeriPlayer-<git短哈希>.<MMddHHmm>[-abi].apk
   ```

安全提醒：

- 不要提交 keystore、密码、Cookie、Token 或其他敏感信息。
- 不要在 Issue / PR 中粘贴完整授权信息。
- 完整配置导出文件会包含平台授权和同步凭据，不能作为公开测试附件。

---

### 项目结构与当前实现 / Project Layout

#### 根模块

- `:app`
  - Android 主应用。
- `:ksp-annotations` / `:ksp-processor`
  - 设置项 schema、key、备份白名单和设置 UI 元数据的 KSP 生成链路。
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`
  - 歌词解析和 Compose 歌词 UI 子模块。
- `build-logic`
  - Android/Kotlin/Compose convention plugins。
- `buildSrc`
  - 保留的辅助 Gradle 构建逻辑模块。
- `np-submodule/NeriPlayer-LTW`
  - 一起听 Cloudflare Workers 服务端。
- `np-submodule/miuix`
  - 仓库内附带的上游 Miuix 源码/文档树，当前不参与主应用模块构建。

#### Android 客户端关键路径

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - 应用初始化入口，负责语言、异常处理、`AppContainer`、
    Lyricon、全局下载管理和共享图片加载器。

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt` 是唯一对外入口，负责安全模式、启动流程、免责声明、
    启动引导、外部音频导入、一起听深链和顶层 Compose 宿主。
  - `NeteaseWebLoginActivity.kt`、`NeteaseQrLoginActivity.kt`、
    `BiliWebLoginActivity.kt` 与 `YouTubeWebLoginActivity.kt`
    是内部平台登录页。

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - 顶层 Compose 应用骨架，负责 `NavHost`、动态底栏、
    `MiniPlayer`、`Now Playing` 覆盖层、Debug 路由、主题和播放服务同步。

- `app/src/main/java/moe/ouom/neriplayer/ui/onboarding/`
  - 首次启动引导，覆盖语言、平台账号、GitHub 同步和个性化设置。

- `app/src/main/java/moe/ouom/neriplayer/core/api/`
  - `netease/`：网易云接口、加密和账号能力。
  - `bili/`：Bilibili 搜索、收藏夹、播放信息和音频拉流。
  - `youtube/`：YouTube Music 客户端（NewPipe Extractor）、
    首页/歌单/搜索/播放、PoToken 和 JS Challenge 支持。
  - `search/`：播放页元数据/歌词补全接口，
    当前实现为 `CloudMusicSearchApi` 与 `QQMusicSearchApi`。
  - `lyrics/`：外部歌词来源，当前实现为 `LrcLibClient`。

- `app/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`：Media3 ExoPlayer 的统一管理层，
    负责音源解析、播放队列、缓存、状态恢复、失败重试和播放策略。
  - `AudioPlayerService.kt`：前台播放服务、媒体通知、MediaSession 和媒体按钮。
  - `AudioDownloadManager.kt`：下载核心链路，解析多平台流并写入本地。
  - `PlaybackEffectsController.kt`：倍速、音调、响度增强和均衡器。
  - `PlaybackStatsTracker.kt`：播放统计采集。
  - `SleepTimerManager.kt`：睡眠定时器。
  - `ConditionalHttpDataSourceFactory.kt`：为特定域名动态附加 Header。
  - `PlayerManagerNeteaseAutoSourceSwitch.kt`：网易云无权限、
    无直链或试听片段时的 Bilibili 自动换源兜底。
  - `YouTubeGoogleVideoRangeSupport.kt`、`YouTubeSeekRefreshPolicy.kt`、
    `prefetch/YouTubePrefetchRunner.kt`：YouTube Music 播放兼容策略。
  - `metadata/`：歌词、元数据、外部蓝牙歌词等播放页数据处理。

- `app/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt` 维护全局下载任务与本地已下载列表。
  - `ManagedDownloadStorage.kt` 管理应用目录/SAF 目录、迁移、快照和 `.nomedia`。
  - `ManagedDownloadNaming.kt` 管理下载文件名模板和历史命名兼容。
  - `DownloadedAudioTagWriter.kt` 写入音频标签。

- `app/src/main/java/moe/ouom/neriplayer/data/`
  - `settings/`：`DataStore` 设置、KSP schema、启动快照、主题快照和播放偏好快照。
  - `auth/`：网易云、Bilibili、YouTube 的 Cookie / Auth 本地存储与校验。
  - `local/playlist/`：本地歌单 JSON 原子写入和系统歌单兼容。
  - `local/audioimport/`、`local/media/`：本地音频导入、扫描、元数据读取和分享。
  - `playlist/favorite/`、`playlist/usage/`：收藏歌单和首页继续播放数据。
  - `history/`、`stats/`：最近播放与播放统计。
  - `backup/`：本地歌单 JSON 备份、导入与差异分析。
  - `config/`：完整配置导入/导出。
  - `sync/github/`：GitHub 同步、三路合并、序列化、省流模式和安全存储。
  - `sync/webdav/`：WebDAV 同步、远端配置、Worker 和 WebDAV API。

- `app/src/main/java/moe/ouom/neriplayer/listentogether/`
  - 一起听协议、WebSocket 客户端、Session 管理、邀请链接、同步规划和服务端地址校验。

- `app/src/main/java/moe/ouom/neriplayer/core/lyricon/`
  - 词幕适配（Lyricon Provider），同步歌曲、播放状态、进度、逐字歌词和翻译。

---

### 当前能力边界 / Current Boundaries

- `Explore` 是网易精选歌单 + YouTube Music 歌单 + 网易/Bilibili/YouTube Music
  按平台独立搜索，不是混合聚合搜索。
- `Home` 在中文默认模式下主要展示本地继续播放与网易推荐；
  国际化模式下优先展示 YouTube Music 首页货架。
- `Library` 中 QQ 音乐入口仍为占位，不代表完整平台接入。
- `Bilibili` 已支持搜索、收藏夹和音频播放/下载，但不是完整视频发现流或评论区。
- `YouTube Music` 已支持登录、首页/歌单浏览、详情、搜索、播放与下载。
- 网易云播放会在当前音质不可用时自动尝试更低音质；
  无权限、无直链或仅返回试听片段时，可按设置自动匹配 Bilibili 音源兜底。
- 本地「我喜欢的音乐」支持将可识别的网易云歌曲同步到网易云我喜欢的音乐；
  该能力依赖网易云登录态，并会跳过不支持或已存在的歌曲。
- 下载使用共享 `OkHttpClient` 写入应用目录或 SAF 目录，
  **不是**系统 `DownloadManager`；当前已支持自动断点续传与启动恢复。
- 续传按传输类型分别处理：
  - 直链下载通过工作文件大小 + `Range` 头续传
  - 显式分块下载按字节偏移续传
  - HLS 下载通过 `.hls.json` 检查点按 segment 恢复
- 工作文件位于 `cache/download_staging/`，并额外保存 `.resume.json`
  恢复元数据；应用启动和网络恢复后会尝试自动找回未完成下载。
- 手动取消会回滚半成品并删除工作文件；只有网络策略暂停与可恢复错误重试
  才会保留断点。
- 流媒体缓存与下载是两套能力：
  缓存使用 `SimpleCache`，下载由 `AudioDownloadManager` 与
  `ManagedDownloadStorage` 写入本地文件。
- GitHub / WebDAV 同步只同步元数据，不同步音频缓存、下载文件、
  本地音频文件、Cookie 或播放 Token。
- 平台 Cookie / 鉴权信息、GitHub Token、WebDAV 密码使用
  `Android Keystore + EncryptedSharedPreferences` 加密保存。
- `DataStore` 只承担常规设置与非敏感状态，不承载平台登录凭据。

---

### 常见扩展路径 / Extension Paths

#### 1. 新增 Explore 搜索源

适用于把新平台接到 `Explore` 页搜索或发现流。

1. 在 `core/api/` 下实现对应客户端或仓库。
2. 在 `ExploreViewModel` 中增加请求、分页和状态映射。
3. 在 `ExploreScreen` / Host 页面中补充平台标签和结果 UI。
4. 如需播放，继续接入 `PlayerManager` 的音源解析链路。
5. 如需下载，补齐 `AudioDownloadManager` 和下载元数据映射。

#### 2. 新增播放页元数据补全源

适用于补封面、歌词、曲目信息，而不是扩展 `Explore` 页。

1. 在 `core/api/search/` 下实现新的 `SearchApi`。
2. 在 `AppContainer` 中注册单例。
3. 在 `SearchManager` 中增加路由、匹配和降级逻辑。
4. 视需要补充 `MusicPlatform`、字符串资源和调试探针。

#### 3. 新增取流平台

1. 参考 `bili/` 或 `youtube/` 设计客户端与播放仓库。
2. 如需特殊 Header，扩展 `ConditionalHttpDataSourceFactory.kt`。
3. 在 `PlayerManager` 的 URL 解析链路接入平台。
4. 下载、歌词、封面和播放统计要保持边界清晰，
   不要把缓存和永久下载混成一套实现。
5. 如需支持同步到网易云我喜欢的音乐，必须提供稳定的网易云歌曲 ID
   或可验证映射，并复用 `LocalPlaylistRepository` 的候选校验逻辑。

#### 4. 修改网易云自动换源

1. 入口在 `PlayerManagerUrlExtensions.kt` 的网易云 URL 解析流程。
2. 匹配与打分逻辑在 `PlayerManagerNeteaseAutoSourceSwitch.kt`。
3. 自动换源只处理网易云无权限、无可用直链或试听片段兜底；
   不要把它扩展成跨平台聚合搜索。
4. 调整匹配策略时要同时考虑歌名、歌手、分 P、时长误差和缓存 key 稳定性。

#### 5. 新增设置项

1. 优先在 `data/settings/AutoSettingsSchema.kt` 登记 key、默认值、类型和展示元数据。
2. 简单开关可用 KSP 生成的 `AutoSettingsRepository` 和 `AutoSettingsSwitchItems`。
3. 有副作用、互斥逻辑、权限或启动快照需求的设置，应保留手写 setter。
4. 如果设置影响启动早期行为，还要同步更新对应 snapshot：
   `BootstrapSettingsSnapshot`、`ThemePreferenceSnapshot` 或 `PlaybackPreferenceSnapshot`。
5. UI 入口通常放在 `SettingsScreen.kt` 对应 `SettingsPage` 或
   `ui/screen/tab/settings/component/` 下。

#### 6. 修改 GitHub / WebDAV 同步

1. 先理解 `SyncDataModels.kt` 与 `SyncDataSerializer.kt` 的兼容策略。
2. 同步对象包含歌单、收藏歌单、最近播放、删除记录和播放统计。
3. 合并策略主要在 `GitHubSyncManager.kt`，WebDAV 复用同一套数据模型和多数合并逻辑。
4. 不要破坏 `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt` 的延迟同步、
   周期同步、validated network 检查和失败重试行为。
5. 涉及敏感信息时统一走 `SecureTokenStorage.kt` 或 `WebDavStorage.kt`，
   不要放回 `DataStore` 或明文 JSON。

#### 7. 修改下载存储

1. 先阅读 `ManagedDownloadStorage.kt`、`ManagedDownloadNaming.kt`
   和相关单元测试。
2. 同时考虑默认应用目录、SAF 自定义目录、迁移、历史命名、元数据文件和 `.nomedia`。
3. 修改目录迁移或删除语义时，必须补充/更新对应单元测试。

#### 8. 修改词幕适配

1. 词幕适配入口在 `core/lyricon/LyriconManager.kt`。
2. 开关状态由设置项 `lyricon_enabled` 控制，并由播放器生命周期同步。
3. 歌词数据使用 `LyricEntry`，逐字信息来自 `WordTiming`；
   翻译行按时间容差匹配到原文行。
4. 修改时要保持 Lyricon、播放页高级歌词和外部蓝牙歌词的歌词结构兼容。

#### 9. 修改一起听

1. Android 客户端逻辑在 `listentogether/`。
2. 服务端逻辑在 `np-submodule/NeriPlayer-LTW`。
3. 协议字段变更必须同时兼容客户端和 Worker，并更新测试。
4. 设置页支持自定义服务端地址和可用性测试，不要硬编码单一地址。

---

### 调试与日志 / Debugging & Logs

- 开发者模式开启方式：设置页连续点击**版本号** 7 次。
- 开启后底栏会出现独立 `Debug` 页。
- 普通文件日志仅在开发者模式开启时启用。
- 崩溃日志由 `ExceptionHandler` / `NativeCrashHandler` 独立落盘，不依赖开发者模式。
- Debug 页包含 YouTube、Bili、Netease、Search、Listen Together 探针，
  以及普通日志和崩溃日志查看器。

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

1. 能成功构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. 单元测试：
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
3. 如修改资源、UI、导航、设置、同步或存储逻辑，建议执行：
   ```bash
   ./gradlew :app:lintDebug
   ```
4. 如涉及 Compose UI、权限、Activity 或登录流程，建议在设备/模拟器上执行：
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
5. 如修改一起听 Worker：
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
6. 新增单元测试放到 `app/src/test/`；
   新增设备或 Compose UI 测试放到 `app/src/androidTest/`。
7. 行为变更涉及 README、设置文案、用户流程或同步格式时，请同步更新文档。

PR 建议包含：

- 变更动机
- 关键实现点
- 风险与兼容性影响
- 测试方式
- 如涉及 UI，附截图或录屏

不要提交：

- APK、签名文件、IDE 本地配置
- 缓存、日志、临时构建产物
- 授权 Cookie、Token、完整配置备份、个人数据

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
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)：社区行为准则

如你准备提交较大的结构性改动，建议先开 Issue 对齐方向。
