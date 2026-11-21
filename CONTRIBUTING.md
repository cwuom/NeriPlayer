[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer / 贡献指南

感谢你为 NeriPlayer 做出贡献。本文描述 Android 客户端和一起听 Worker 的当前实现，文档与源码、运行行为保持同步。

---

### 项目定位 / Scope

- NeriPlayer 是原生 Android 音频播放器，不提供公共云端曲库。
- 在线内容来自网易云音乐、Bilibili 与 YouTube Music。
- 播放页元数据与歌词补全使用网易云和 QQ 音乐，并接入 LRCLIB、酷狗和 AMLL TTML 外部歌词来源。
- 数据默认保存在本地。
- GitHub / WebDAV 同步为可选能力，同步歌单、收藏、最近播放和播放统计等元数据，不同步媒体文件。
- 一起听服务端位于 `np-submodule/NeriPlayer-LTW`，基于 Cloudflare Workers 与 Durable Objects。

---

### 文档地图 / Documentation Map

| 文档 | 用途 |
| --- | --- |
| [README](README.md) | 面向用户和新贡献者：项目定位、核心特性、能力边界、安装构建、同步与隐私。 |
| [实现概览](docs/implementation.md) | 构建版本、15 个自有库职责、入口与导航、播放与缓存、数据来源、下载备份。 |
| [贡献指南](CONTRIBUTING.md) | 面向开发者：模块边界、扩展路径、测试和提交要求。 |
| [Kotlin 辅助工具](docs/kotlin-helpers.md) | 请求代次、单位、播放器读取和协程结果等辅助工具的用法与边界。 |
| [音乐服务器 API](docs/music-server-api.md) | **预览版本，缺乏充分测试。** HTTP 接口、认证、来源身份与缓存约定。 |
| [质量门禁](tools_pub/quality/README.md) | CRAP、模块归属、依赖边界检查的范围、命令和验证限制。 |
| [Native 源码授权](modules/native/src/main/cpp/README.md) | Native 自有源码的替代授权范围、第三方排除项和外部贡献的双授权声明。 |
| [Native USB host 门禁](modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md) | USB host 门禁、CI 覆盖与真实设备验证边界。 |
| [USB 测试语料](modules/native/src/main/cpp/tests/usb/corpus/README.md) 与 [夹具](modules/native/src/main/cpp/tests/usb/fixtures/README.md) | 公开语料和夹具只用合成或可审计资料，真实设备证据留在私有目录。 |
| [一起听服务端](https://github.com/TheSmallHanCat/NeriPlayer-LTW#readme) | 面向服务端部署者：Worker API、事件模型、部署和本地检查。 |

文档维护约定：

- 影响用户理解的行为变更，更新 README。
- 影响扩展方式、测试方式或模块边界的变更，更新本文。

---

### 开发环境 / Development Environment

| 组件 | 要求 |
| --- | --- |
| Android Studio | 最新稳定版 |
| JDK | 编译目标 17；`:app` 与 `:playback:runtime` 的 Android 16 JVM 测试需 21 |
| Kotlin | 2.4.10，JVM target 17 |
| AGP | 9.4.1 |
| Gradle | 9.6.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 28 |
| NDK | `27.0.12077973` |
| CMake | `3.22.1`，由 Android 构建脚本指定 |
| Node.js | 22，用于一起听 Worker 检查 |
| 版本名格式 | `<git短哈希>.<MMddHHmm>` |
| Release APK 文件名 | `NeriPlayer-<versionName>[-abi].apk` |

环境要点：

- 仓库依赖 Git 子模块，克隆时使用 `--recursive`，或执行 `git submodule update --init --recursive`。
- 构建脚本读取 Git 短提交生成版本名，需要本机安装 Git。
- `build-logic` 的 Java / Kotlin 编译目标为 17，`ksp-annotations` 与 `ksp-processor` 使用 JDK 17 工具链，请保留 JDK 17。
- `:app` 与 `:playback:runtime` 的 Android 16 Robolectric sandbox 需要 JDK 21。
- 运行完整测试或 `verifyModularization` 时，将 Gradle JDK / `JAVA_HOME` 设为 21，并保留可被 Gradle 发现的 JDK 17；生产字节码目标仍为 17。
- 依赖版本由 `gradle/libs.versions.toml` 与各模块 `build.gradle.kts` 管理。
- 应用只保留 `zh` 与 `en` 资源，见 `build-logic` 的 locale filter。

---

### 质量护栏 / Quality Guardrails

NeriPlayer 功能面较宽，提交前优先保护以下链路。

- **播放**：`PlayerManager`、播放解析策略、缓存、失败刷新与兜底、长音频进度记忆、BilibiliSponsorBlock 跳过、状态恢复、响度均衡、声道平衡、高解析输出、USB 独占 Native 链路、启动看门狗、前后台健康审计。
- **下载**：`AudioDownloadManager`、`GlobalDownloadManager`、`DownloadTaskStore`、`DownloadLifecyclePolicies`、`ManagedDownloadStorage`、续传检查点、sidecar 文件、任务队列恢复、取消清理、SAF 目录迁移。
- **同步**：GitHub / WebDAV 三路合并、删除记录、播放统计、缺字段快照清洗、JSON / ProtoBuf / Base64 格式兼容、WebDAV 并发保护。
- **本地数据**：歌单 Room 事务、旧 JSON 升级与原子回退、本地元信息补全、配置导入导出、授权加密存储、DataStore 设置。
- **歌词与播放页 UI**：`AdvancedLyricsView`、`SyncedLyricsView`、`LyricShareSheet`、歌词音译、日语歌词翻译间距、歌词长按分享、Lyrics 全屏页。
- **导航与玻璃 UI**：`MainTabLayerHost`、详情页抽屉与连贯反馈、可打断主标签切换、页面状态保留、标准化 Snackbar 覆盖层、Advanced Glass owner 接力。
- **主题与资料面板**：Material / Clean 预览主题、深浅色切换、预测性返回取消、歌曲百科的身份校验、请求代次与附加板块降级。
- **系统入口与桌面外壳**：`LauncherShortcuts`、桌面小组件、`USB_DEVICE_ATTACHED` 处理开关、播放服务控制入口。
- **存储与缓存 UI**：`StorageUsageAnalyzer`、缓存清理选项、下载目录索引、SAF 快照。
- **一起听**：Android 客户端、Worker 协议字段、角色权限、队列、版本门控更新、会话候选共享开关、房主离线恢复。
- **诊断恢复**：安全模式、JVM / Native 崩溃日志、ANR 记录、Debug 探针。
- **本地持久化**：播放与流量统计的批量写入、生命周期 flush、原子文件替换、SAF / 本地歌单初始化就绪状态。

测试位于 app 与各库模块的 `src/test/` 和 `src/androidTest/`。修改上述链路时，先搜索同名目录或相邻测试类，再补充覆盖。

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
4. 首次启动进入免责声明与启动引导。通知和本地音乐权限会先说明用途，用户点按后才请求，也可跳过。
5. 如需调试入口，在设置页连续点击**版本号** 7 次，底栏出现独立 `Debug` 页。

---

### 构建发布版 / Release Build

发布版启用混淆与资源收缩。默认 `assembleRelease` 只打包 `arm64-v8a`，多 ABI 输出需额外参数。

1. 在 `~/.gradle/gradle.properties`、项目 Gradle properties 或命令行 `-P` 中提供签名信息：
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```

   签名规则：

   - `KEYSTORE_FILE` 使用相对路径时，按 `app/` 模块目录解析。
   - Release 构建不回退到 debug signing config。
   - Android Studio / IntelliJ 本地构建自动允许未签名 Release 通过打包校验。
   - 命令行和 CI 要求可用 keystore。
   - GitHub PR 自动构建未签名 Release 做打包校验；其他 CI / PR 环境可传入 `-PallowUnsignedRelease=true`。

2. 构建默认 Release：
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. 构建多 ABI Release：
   ```bash
   ./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true
   ```

4. 产物位于 `app/build/outputs/apk/release/`，文件名格式：
   ```text
   NeriPlayer-<git短哈希>.<MMddHHmm>[-abi].apk
   ```

安全要求：

- 不提交 keystore、密码、Cookie、Token 或其他敏感信息。
- 不在 Issue / PR 中粘贴完整授权信息。
- 配置导出默认不含登录态（`includeLoginState=false`）。
- 勾选登录态后，导出文件携带平台授权与 GitHub / WebDAV 同步凭据，禁止公开上传或作为测试附件。

---

### 项目结构与当前实现 / Project Layout

#### 根模块

自有库的入口与完整职责见 [docs/implementation.md](docs/implementation.md#模块结构)。

- 15 个库由 `gradle/owned-modules.txt` 统一登记，构建、lint 和结构检查读取同一份登记。
- 覆盖率只收集包含 Kotlin / Java 生产源码的库。
- `:native` 使用独立的 host 测试与四 ABI 编译。
- 一级目录表达业务领域，远程来源集中在 `:platform`。
- 播放和下载各有规则库与运行库两个构建边界。

各库职责：

- **基础能力**：`:common`、`:network`、`:model`、`:database`。
  - 模型不依赖项目实现。
  - 历史 Room schema 与完整升级链由数据库维护。
- **Native**：`:native` 维护 CMake 构建边界、崩溃处理、USB 协议与 PCM 计算、独占传输和第三方 libusb。
  - app 消费它的 AAR。
  - Kotlin 播放会话与 JNI 桥由 `:playback:runtime` 维护。
- **播放**：`:playback:logic` 集中 policy / runtime / audio / queue，`:playback:runtime` 维护引擎、服务、USB、音源解析与接入。
  - policy 的九个叶子包保持窄依赖，USB policy 归运行库。
  - 下载只能通过 `PlayerDownloadAccess` 调用。
- **下载**：`:download:logic` 维护可复用规则与基础存储工具，`:download:runtime` 维护 Room、SAF、恢复和服务。
  - 规则不依赖运行实现。
  - Worker 与 JobService 的类全名保持稳定。
- **歌词**：`:lyrics` 维护解析、转换、共用时间偏移和词幕输出。
  - `lyrics/lyricon` 放 SDK 与位置推送。
  - `lyrics/output` 管理异步请求代次、取消与偏移快照。
  - 播放器通过窄加载端口获取歌词和快照，歌词库不依赖平台或播放器实现。
- **平台**：`:platform` 维护 Bilibili、网易云、YouTube 及 Subsonic 自建音乐服务器（预览版，缺乏充分测试）的请求协议、账号、缓存与业务，以及评论、歌词来源和元数据搜索、匹配与回退。
  - `api` 是同库协议职责，禁止反向读取账号仓库、Room 和播放器。
  - 各平台按包保持依赖边界。
  - YouTube JS assets 和 consumer R8 规则随平台维护，解析能力通过 `api(:lyrics)` 对外提供。
- **本地数据**：`:local` 按设置、媒体、歌单、统计、备份、流量与同步接入分包。
  - 维护业务映射、宿主接口和 WorkManager 适配。
  - 设置 schema 在此运行 KSP，页面渲染在 app。
- **同步**：`:sync` 维护 GitHub / WebDAV 传输、加密凭据、设备身份、因果计数器、会话、清洗、合并与 Worker 策略。
  - 传输和计算组件受各自依赖白名单约束。
  - 本地仓库适配位于 `:local`。
  - 删除记录与 mutation version 原子提交。
- **一起听**：`:listentogether` 维护云端房间协议、传输、身份规则和客户端会话。
  - 协议只使用模型、序列化与标准库。
  - 业务通过宿主接口接入播放器、资源和 Android 服务。
- **局域网一起听**：`:ltwlan` 维护房间发现、音源共享和 BeatSync 同步调度。
- **应用与工具**：`:app` 维护 Android 入口、Compose 页面和依赖组装。
  - KSP 工具、上游歌词子模块、`build-logic` 和一起听服务端不计入 15 个库。
  - Miuix 的上游源码与文档树不参与主应用构建。

依赖规则：

- 所有库禁止引用 app、页面或 `AppContainer`，依赖不形成循环。
- `:playback:runtime` 内部可使用自己的 `PlayerManager`。
- 设置通过 provider 按需读取，HTTP 客户端、账号与设备令牌由宿主注入，不经全局容器绕过接口。
- Room 业务映射随本地或平台仓库维护，数据库和规则组件不反向引用运行实现。

构建与测试约定：

- 含 Kotlin / Java 生产源码的库使用 `build-logic.android.feature-library` convention。
- 纯 Native 的 `:native` 使用 `build-logic.android.library`，不接入 JaCoCo 或 JVM 测试。
- 自有库在 `gradle/owned-modules.txt` 登记真实 Gradle 路径，无需另行维护 JVM 覆盖率列表。
- 测试随实现放在该库 `src/test` / `src/androidTest`，宿主集成测试放在 app。
- 模块目录调整不改变生产包名或类全名，源码包与目录保持一致，路径契约、测试夹具、资源与 CRAP 选择器一起维护。

`verifyModuleBoundaries` 检查：

- 登记与实际目录是否一致
- 领域依赖与循环
- 禁止导入
- 源码所有者与包目录一致性
- 库主源码少于 2000 行
- 库和 `APP_FAMILIES` 区域每个目录最多 16 个直接源码文件

包级计算域由编译字节码门禁隔离。

#### Android 客户端关键路径

**`app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`**

- 应用初始化入口，负责语言、异常处理、`AppContainer`、Lyricon、全局下载管理和共享图片加载器。

**`app/src/main/java/moe/ouom/neriplayer/activity/`**

- `MainActivity.kt` 是主界面与音频分享 / 深链入口，负责安全模式、启动流程、免责声明、启动引导、外部音频导入、一起听深链和顶层 Compose 宿主。
- `activity/car/BluetoothMediaValidationActivity.kt` 是系统蓝牙媒体能力确认入口。
- USB 插入通过 Activity alias 转到 `MainActivity`。
- 车载浏览服务和封面 Provider 由播放运行库的 Manifest 声明，各入口按自身权限与调用方边界维护。
- 平台登录 Activity 位于 `activity/auth/`，在独立次进程中运行。
- `activity/sync/` 保存 Activity 侧的同步告警状态。
- `UsbDeviceAttachHandling.kt` 按设置启停 USB 设备插入 Activity alias，播放服务复用同一策略过滤 `USB_DEVICE_ATTACHED` 广播。
- `NeteaseWebLoginActivity.kt`、`NeteaseQrLoginActivity.kt`、`BiliWebLoginActivity.kt`、`BiliQrLoginActivity.kt`、`YouTubeWebLoginActivity.kt` 是内部平台登录页。

**`app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`**

- 顶层 Compose 应用骨架，负责 `NavHost`、动态底栏、`MiniPlayer`、`Now Playing` 覆盖层、Debug 路由、主题、缓存清理和播放服务同步。
- `MainTabLayerHost.kt` 为主标签页面保留出场 / 入场双场景，按标签顺序执行可打断横向转场，并为各场景保留 saveable state 和玻璃 owner。
- `ui/theme/NeriTheme.kt` 通过同一内容宿主切换 Material / Clean，主题切换不重建导航。
- `NeriDesign.kt` 提供样式几何，`CleanTheme.kt` 提供 Clean 配色和排版。
- `AppGlobalDialogHosts.kt` 展示全局下载流量确认，`globalDialogVisible` 通知播放页和更多面板避让。
- `ui/feedback/` 承载应用级 Snackbar / Toast 反馈策略。新增全局反馈前，先确认 `AppFeedback` 与 `ViewSnackbar` 是否已覆盖。

**`app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/`**

- `AdvancedLyricsView.kt` 与 `SyncedLyricsView.kt` 负责高级歌词排版、逐字 / 逐词高亮、翻译与音译显示、点击跳转和长按回调。
- `LyricShareSheet.kt` 负责歌词行选择、复制、歌曲分享和歌词卡片生成。
- LRC / YRC / TTML 解析和翻译对齐位于 `modules/lyrics` 的 `lyrics.parser` 包。
- 共享歌词数据位于 `:model` 的 `lyrics` 包。
- 旧 `AppleMusicLyric` 名称只存在于 `ui/component/LyricsCompatibility.kt` 的 `@Deprecated` 包装，新代码使用 `SyncedLyricsView`。

**`app/src/main/java/moe/ouom/neriplayer/ui/component/playback/`**

- `NeriMiniPlayer.kt` 负责底部迷你播放器、播放暂停和横向滑动切歌。
- 睡眠定时器面板在同一目录。
- 音效与倍速设置页位于 `ui/screen/tab/settings/audio/`。
- `ui/component/` 根目录的同名文件是旧包兼容入口，新增实现放入 `lyrics/`、`playback/`、`download/`、`navigation/` 等职责子包。

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/`**

- `LibraryScreen.kt` 负责媒体库顶层分类。
  - 本地内容可在歌单 / 歌手之间切换。
  - 收藏页展示歌单、已关注艺术家和热点歌单。
  - 提供自建音乐服务器入口（预览版）。
- `LocalArtistLibraryGrid.kt` 展示本地艺术家网格、空状态和艺术家卡片。

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/`**

- `LocalArtistDetailScreen.kt` 展示本地艺术家详情，支持播放全部、多选、导出歌单和批量下载可在线解析的歌曲。
- `curation/` 是批量词图管理。
  - `LocalCurationCoordinator` 持有进程级会话，通过 `LocalCurationPorts` 访问副作用。
  - 生产实现 `AppLocalCurationPorts` 调用 `PlayerManager.embedLocalSongAssets`。
  - 文件写入在全局元数据锁外并行，同一文件由本地媒体层按目标排队。
  - 锁内只基于最新副本合并歌词与封面字段。
  - `LocalCurationPolicy` 提供可单测的规划、自动勾选与空间估算规则。
- 文件侧的嵌入资产检查、封面压缩、文件夹封面定位与媒体写入授权位于 `modules/local` 的 `data/local/media/curation/`。
- 时间轴、现场版与 LRC 标签加权位于 `:platform` 的 `LyricMatchSupplementalSignals.kt`。

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/`**

- 网易云艺术家详情页，展示艺术家信息、热门歌曲、分页专辑和关注状态。

**`app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/`**

- 网易云艺术家摘要、JSON 解析和详情页状态管理。

**`app/src/main/java/moe/ouom/neriplayer/ui/onboarding/`**

- 首次启动引导，覆盖语言、平台账号、权限说明、播放控件、GitHub 同步和个性化设置。

**`modules/platform/src/main/java/moe/ouom/neriplayer/platform/`**

- `netease/api`：网易云客户端、加密、请求参数和二维码认证协议。
- `bilibili/api`：搜索、二维码登录、收藏夹、合集和播放信息。仓库与跳过规则位于 `platform/bilibili`。
- Explore 链接识别保留分 P、`cid` 和 `season_id` 上下文，修改时同步检查 `ExploreLinkRecognizer` 与 `ExploreViewModel`。
- `youtube/api`：YouTube Music 客户端、PoToken、JS Challenge、请求 / 响应解析。协议模型位于 `:model`，认证持久化与缓存位于 `platform/youtube`。
- `lyrics/api`：LrcLib、Kugou 和 AMLL 服务访问。来源仓库、匹配与回退位于 `platform/lyrics`。
- `search/api`：`SearchApi`、网易云和 QQ 元数据搜索服务。`SearchManager` 位于 `platform/lyrics`，共享音乐模型属于 `modules/model`。
- `netease/encyclopedia`：歌曲百科数据源、解析和有界内存缓存。共享模型位于 `:model` 的 `data/model/encyclopedia`，页面和请求状态在 app。
- `AppContainer` 组装客户端与路由，注入 HTTP、调试配置和实时设置 provider，库不读取应用容器或播放器单例。
- 纯文本歌词时间轴转换位于 `modules/lyrics` 的 `PlainLyrics.kt`，播放器与匹配器共享同一实现。

**`modules/platform/src/main/java/moe/ouom/neriplayer/platform/comments/`**

- 评论来源、解析、分页和缓存。
- 评论数据契约属于 `:model`。
- 客户端 provider 与缓存由 `AppContainer` 组装注入，缓存生命周期由宿主决定。
- 仓库测试位于该模块，ViewModel 集成测试位于 `app`。
- 歌曲来源标签属于 `:model`。
- Bilibili 历史播放身份解析位于 `platform/bilibili/playback/resolver`，不引用播放器单例。

**`modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/`**

- `PlayerManager.kt`：Media3 ExoPlayer 的统一管理层，负责音源解析、播放队列、缓存、状态恢复、失败重试和播放策略。
- `service/AudioPlayerService.kt`：前台播放服务、媒体通知、MediaSession 和媒体按钮。
- 下载执行归 `:download:runtime`，播放器只消费 `host/PlayerDownloadAccess.kt`，下载状态契约位于 `:model` 的 `playback/storage`。
- `effects/PlaybackEffectsController.kt`：倍速和音调。
- `engine/`：Media3 渲染器与数据源组装。
- `engine/datasource/ConditionalHttpDataSourceFactory.kt`：为特定域名动态附加 Header。
- `timer/SleepTimerManager.kt`：睡眠定时器。
- `metadata/`：歌词、元数据、外部蓝牙歌词等播放页数据处理。

**音效（`audio/effects/`）**

- `AudioEffectsAudioSink` 位于 USB / 系统输出 sink 之前，调用 `:native` 的 `dsp/` 引擎处理 16 / 24 / 32-bit 整数与 float PCM。
- 中性或关闭时原样旁路并允许 offload。
- USB 独占默认旁路以保持 bit-perfect。
- 音效配置、内置音效、编解码与 DSP 参数映射位于 `:model` 的 `playback/effects`，参数下标与 `dsp/neri_dsp_params.h` 一致。

**`:playback:logic` 的 runtime 与音频处理**

- PCM 声道平衡、响度归一化和音频可视化位于 `audio/processing` 与 `audio/reactive`。
- `runtime/stats`、`runtime/progress`、`runtime/transport`、`runtime/quality` 分别维护统计采集、播放进度、传输与音质控制，宿主通过对应 Port 提供副作用。
- 播放命令与队列推进位于 `playback/PlayerManagerPlaybackExtensions.kt`。

**队列状态（`:playback:logic` 的 `queue/state` 与 `queue/policy`）**

- 两个包分别维护状态所有权与编辑 / 导航规则，队列数据契约位于 `:model` 的 `playback/queue`。
- `PlayerQueueSnapshot` 持有列表与当前索引。
- `PlayerQueueSessionSnapshot` 将队列、随机播放模式和恢复顺序组成完整会话，由 `PlayerQueueStateStore` 统一发布。
- 会话入口在同一事务内更新队列与恢复信息：
  - `startPlayback`：开始播放新歌单。
  - `setLocalShuffle`：切换本地随机播放。
  - `restoreSession`：加载持久化会话。
- 队列更新入口：
  - `publishCurrentQueue`：远端整队更新。
  - `updateCurrentQueue`：移动、删除和插入。
  - `updateQueuedSong`：异步元数据写回，按歌曲身份更新最新队列并保留当前选曲。
- 这些操作在锁内基于最新快照计算，播放器与磁盘操作放在锁外。
- UI 重排只提供顺序，歌曲内容取自最新队列；重复歌曲无法准确对应时拒绝旧请求并刷新界面。
- 恢复洗牌前顺序时，若歌曲集合已改变或副本对应存在歧义，保留当前队列。
- 清空队列同时清除恢复顺序。
- 持久化读取一次会话快照，不混用不同版本的随机播放状态。
- 当前歌曲 Flow 和 Media3 副作用的线程边界需单独检查。
- `session/PlayerQueueSessionBindings` 适配新引擎初始化、本地 / 远端播放请求和持久化恢复。
- 队列计算包的直接 JVM 依赖由 `verifyDomainDependencies` 检查，禁止引用该宿主适配器。
- 随机播放展示状态使用 `PlayerQueueDisplayState`，乱序显示队列不退回只靠索引重映射的隐式语义。

**状态持久化（`runtime/persistence/PlaybackStatePersistenceCoordinator.kt`）**

- 统一管理保存请求与延迟任务。
- 在同步事件入口调用 `prepareStatePersist` 或 `scheduleStatePersist`，先捕获完整快照并签发请求，再等待统计落盘或其他异步工作。
- 写入串行执行，排队期间被替代的请求不再写入。
- `PlaybackStateWriter` 只确认实际成功的后端。
- JSON 回退后完整写回 Room，才恢复增量保存。
- 协程取消向上传播。
- 释放播放器会关闭保存请求入口。
- 存储完成回调不修改当前播放状态。
- `RestoredPlaybackState` 表达无恢复、保留暂停进度、待自动恢复三种状态，恢复进度与标记不独立修改。

**URL 刷新（`:playback:logic` 的 `runtime/refresh`）**

- 活动状态只由 `RefreshInFlightController` 持有，请求契约和副作用门控也在该包。
- 参数相同的请求复用活动任务。
- 写回、完成和取消使用任务的 `RefreshRequestHandle` 身份，旧任务不影响参数相同的新任务。
- 看门狗直接读取控制器，不另存“正在刷新”标记。
- 任务完成清理覆盖 lazy 协程尚未执行就被取消的情况。
- 播放意图通过 `updateResumePlaybackRequested` 修改，并撤销冲突刷新。
- 音质刷新在主线程内同步采集当前状态并创建请求，避免跨线程读取新代次。

**播放启动、兜底与兼容**

- `watchdog/PlayerManagerStartupWatchdogExtensions.kt`、`lifecycle/PlayerManagerLifecycleExtensions.kt`：启动看门狗、前后台健康审计、失败恢复和 USB 独占异常回退。
- `resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`：网易云无权限、无可用播放结果或试听片段时，切换到 Bilibili 播放兜底。
- YouTube Music 播放兼容策略：
  - `YouTubeGoogleVideoRangeSupport.kt` 位于 `:platform` 的 `platform/youtube/api/transport/range/`。
  - `resolver/youtube/YouTubeSeekRefreshPolicy.kt` 与 `prefetch/YouTubePrefetchRunner.kt` 位于播放器。

**播放状态契约**

- 播放器状态契约位于 `:model` 的 `playback` 包。
- 纯播放 policy 归 `:playback:logic`。
- 播放呈现适配与副作用由 `:playback:runtime` 和 app 承担。

**USB 独占（`usb/`）**

- 按 `device/`、`path/`、`session/`、`sink/`、`system/`、`transport/` 拆分会话、Native 桥、运行态快照和恢复控制。
- 支持 **UAC1.0** 与兼容 **UAC2.0 Type I PCM** 设备。
- 覆盖 32-bit PCM、PCM float 软件转换、UAC2 显式反馈、协调式 AudioSink 重配置、动态传输缩放和背压卡顿恢复。

**`modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/`**

- `GlobalDownloadManager.kt` 维护全局下载任务与本地已下载列表。
- `ManagedDownloadStorage.kt` 是应用目录 / SAF 目录的外观入口，实现拆在 `storage/` 下的 `commit/`、`delete/`、`lookup/`、`migration/`、`recovery/`、`snapshot/`、`tree/`、`working/` 子包。
- `task/DownloadTaskStore.kt` 管理内存中的任务展示、状态、进度和 attemptId。
- `execution/persistence/DownloadExecutionRoomStore.kt` 通过 Room 持久化下载操作和恢复状态。
- `policy/DownloadLifecyclePolicies.kt` 集中下载恢复、取消清理和快速结算策略。
- `naming/ManagedDownloadNaming.kt` 管理下载文件名模板和历史命名兼容。
- `metadata/DownloadedAudioTagWriter.kt` 写入音频标签。
- `catalog/` 管理已下载歌曲目录的读写与投影。

**`app/src/main/java/moe/ouom/neriplayer/core/startup/`**

- 启动阶段与决策按 `app/`、`crash/`、`download/`、`logging/`、`permission/`、`player/`、`safemode/`、`sync/`、`theme/` 拆分。
- `MainActivity` 只协调这些组件与 UI 生命周期。

**`modules/local/src/main/java/moe/ouom/neriplayer/data/`**

- `identity/`：歌曲身份转换。`SongIdentity` 与 `SongItem` 属于 `modules/model`。
- `settings/`：`DataStore` 设置、KSP schema 和偏好映射。快照契约位于 `:model` 的 `settings` 包。
- `auth/`：通用 Web 登录状态与 YouTube 凭据轮换 Worker。平台 Cookie / Auth 仓库位于 `:platform` 的 `data/auth` 和 `platform/youtube/auth`。
- 平台缓存归 `:platform`，本地歌单编排归 `:local`，共享缓存表 schema 归 `:database`。
- `storage/`：存储占用分析、缓存分组和额外缓存清理。
- `local/playlist/`：本地歌单 Room 读写、旧 JSON 升级与原子回退、系统歌单兼容、后台元信息补全和本地艺术家聚合。
- `local/audioimport/`、`local/media/`：本地音频导入、快速扫描、后台元信息补全、封面回退和分享。
- `playlist/favorite/`、`playlist/usage/`：收藏歌单、收藏艺术家和首页继续播放数据。
- `history/`、`stats/`：最近播放、播放统计和日 / 周 / 月 / 年 / 总计周期聚合。
- `backup/`：本地歌单 JSON 备份、导入与差异分析。
- `config/`：完整配置导入 / 导出。
- `sync/host/`：Android 仓库快照、资源解析与落库适配。`AndroidSyncMergeHost` 提供系统歌单身份与文案，会话和合并组件通过接口调用。
- `sync/cover/`：封面映射持久化与旧 JSON 导入。导入失败时保留旧数据，阻止后续清理。
- `sync/github/`、`sync/webdav/`：提供者后端与兼容 Worker 入口。
- `sync/work/`：WorkManager、网络验证和通知适配。
- app 无 `data/sync` 生产源码。

**`modules/sync/src/main/java/moe/ouom/neriplayer/`**

- `api/sync/`：GitHub / WebDAV 传输、响应读取和条件写入。
- `data/sync/store/`：加密凭据、偏好、设备身份、因果计数器和删除状态。
- `data/sync/merge/`：按 `engine`、`host`、`playlist`、`song`、`history`、`stats` 维护合并入口、宿主契约、冲突、排序和统计规则。
- `data/sync/` 下的 `runtime/`、`codec/`、`sanitize/`、`change/`、`mapping/stats/`、`remote/`、`retry/`、`schedule/`：会话、兼容编解码、清洗、变化检测、统计映射、远端保护、重试与 Worker 策略。
- 同步载荷和冲突模型位于 `:model` 的同步包。
- `verifyDomainDependencies` 为传输与计算域各设白名单，禁止直接引用 Android 仓库或播放器。

**`modules/listentogether/src/main/java/moe/ouom/neriplayer/api/ltw/`**

- HTTP、WebSocket、服务器地址校验和重连策略，依赖协议模型与注入的 HTTP 客户端。

**`modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/`**

- `ListenTogetherSessionManager` 组装会话组件，`playback/` 维护队列、权威播放候选和进度同步。
- `control/`、`session/`、`invite/`、`mapping/`、`validation/` 分别维护控制、会话、邀请、歌曲映射和输入边界。
- 房间、事件与传输模型位于 `modules/model` 的 `ltw` 包。
- `api/ltw` 维护传输，`listentogether/protocol` 维护有界读取与消息编解码。
- app 的 `core/di/ltw` 提供平台接口，播放器的 `core/player/ltw` 提供播放器和歌曲映射接口。
- `app/listentogether` 无生产源码。

**`modules/native/src/main/cpp/`**

- `crash/`：Native 崩溃处理。
- `dsp/`：PCM 音效引擎，host 测试位于 `tests/dsp/`，单独运行，不属于 USB host 门禁。
- `cmake/`：共享编译选项。
- `usb/` 按 `control/`、`exclusive/`、`feedback/`、`iso/`、`pcm/`、`uac1/`、`uac2/` 拆分，host 测试位于 `tests/usb/`。
- `third_party/libusb/`：第三方 libusb 和 Android 配置。
- CMake 目标按职责链接，`lib_neri.so` 由 `:native` AAR 提供。

**`modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`**

- 词幕适配（Lyricon Provider）与 SuperLyric 输出，同步歌曲、播放状态、进度、逐字歌词和翻译。
- `lyrics/output/` 维护输出控制器、异步请求代次、取消与偏移快照，位置锚点和协调器为内部可见。

**`app/src/main/java/moe/ouom/neriplayer/navigation/`**

- `LauncherShortcuts.kt` 将桌面图标快捷方式映射到导航 / 播放请求。

**`app/src/main/java/moe/ouom/neriplayer/widget/`**

- 桌面播放小组件的 provider、状态快照、封面取色视觉和 RemoteViews 更新逻辑。

---

### 当前能力边界 / Current Boundaries

#### 首页、搜索与平台

- `Explore` 由网易精选歌单、YouTube Music 歌单，以及网易 / Bilibili / YouTube Music 的按平台独立搜索组成，不做混合聚合搜索。
- `Home` 在中文默认模式下展示本地继续播放、网易云全部可用推荐源和雷达歌单，国际化模式下优先展示 YouTube Music 首页货架。
- `Library` 中的 QQ 音乐入口仍是占位。
- 本地艺术家分类由本地已导入 / 已保存歌曲的展示艺术家聚合而成，不是在线艺术家资料库。
- 网易云艺术家详情依赖网易云 artist 元数据和接口。
- 网易云、B 站和 YouTube 创作者的关注状态保存在本地收藏，按平台分类。
- 网易云和 YouTube Music 的远端关注由用户手动拉取，仅在完整分页成功且账号未变化时批量合并。
- 合并保留已有收藏和排序，加载期间取消的关注不会恢复，不向平台回写关注。
- Bilibili 支持搜索、收藏夹、音频播放 / 下载，以及评论阅读、楼中楼、复制和登录后的点赞、发评与回复。
- Bilibili 链接识别支持选中分 P、合集分享和 `season_id` 上下文。
- YouTube Music 支持登录、匿名播放、首页 / 歌单浏览、详情、搜索和播放兼容。
- YouTube 有效身份 Cookie 会保留并轮换。
- YouTube 播放解析复用 bootstrap / player.js / PoToken 与挑战结果缓存，签名或播放候选失败时回退 EJS / HLS。
- 缓存命中和本地单测不能证明真实账号与网络下的稳定性。
- 长音频大幅 Seek 使用更短的启动恢复窗口，真实网络体验需另行验证。

#### 歌词与视觉

- 状态栏歌词依赖厂商私有能力，仅适用于部分设备。
- `RuntimeShader` 流体 / 音频响应背景与高级模糊仅在 Android 13+ 启用，封面模糊支持 Android 9+，修改动效时保留低版本降级路径。
- 歌词音译来自平台返回的音译歌词或内嵌逐字歌词的 phonetic 字段，没有音译数据时不合成，也不显示空的第二行。
- 歌词分享通过 `FileProvider` 分享缓存目录中的歌词卡片文件，该文件属于可清理缓存，不是用户下载内容。
- Lyricon / SuperLyric 的位置 feed 独立按 200 ms 推送，使用 elapsed realtime 锚点。
- 修改播放器进度刷新间隔时，同步检查前后台歌词时序和 SuperLyric 对齐。

#### 播放兜底与缓存

- 网易云播放在当前音质不可用时自动尝试更低音质。
- Bilibili 和本地音频兜底默认关闭，用户在引导或设置中开启后，才会在无权限、无可用播放结果或仅返回试听片段时尝试。
- 网易云歌单详情缓存用于歌单详情页快速展示和失败回退，专辑详情保持实时刷新。
- 本地「我喜欢的音乐」可将可识别的网易云歌曲同步到网易云我喜欢的音乐，依赖网易云登录态，并跳过不支持或已存在的歌曲。
- 流媒体缓存使用 `SimpleCache`。
- 下载由 `AudioDownloadManager` 与 `ManagedDownloadStorage` 写入本地文件，与流媒体缓存是两套能力。

#### 下载

- 下载使用共享 `OkHttpClient` 写入应用目录或 SAF 目录，不使用系统 `DownloadManager`，支持自动断点续传与启动恢复。
- 下载任务队列、取消记录和 attemptId 参与恢复判断，修改恢复流程时避免旧请求清掉新请求的任务状态。
- 续传按传输类型处理：
  - 直接 HTTP 传输通过工作文件大小 + `Range` 头续传。
  - 需要显式 Range 的平台传输按字节偏移续传。
  - HLS 下载通过 `.hls.json` 检查点按 segment 恢复。
- 工作文件位于 `files/download_staging/`，并保存 `.resume.json` 恢复元数据。
- 应用启动和网络恢复后，自动找回未完成下载。
- 手动取消回滚半成品并删除工作文件，网络策略暂停与可恢复错误重试保留断点。
- 音频本体完整后，标签或 SAF 可写句柄失败时按无标签完成，保留已落盘音频。
- 应用私有下载目录通常比 SAF 自定义目录更快。
- SAF 快照和索引用于减少遍历，空目录扫描不覆盖已有索引，SAF 操作成本高于普通文件系统。
- 分享受控目录中的本地音频时优先暴露可读 URI，无法直接分享的 content URI 复制到缓存 staging。
- 分享暂存属于可清理缓存。
- 普通缓存清理只删除可再生成缓存和分享暂存。
- 下载工作文件与续传凭据由下载恢复管理，用户下载的音频、歌词和封面不经“清缓存”删除。

#### 同步与数据安全

- GitHub / WebDAV 只同步元数据，不同步音频缓存、下载文件、本地音频文件、Cookie 或播放 Token。
- 本地歌单与同步歌单用 `songOrderVersion` 区分顺序语义：`0` 为旧版顺序，`1` 为当前展示顺序，读取旧数据时先迁移。
- 同步快照可能来自旧版 JSON / ProtoBuf 或异常远端文件，读取时使用安全默认值。
- 同步读取过滤缺少可解析歌曲身份、无有效删除时间或歌单 ID 无效的记录。
- 缺失 `addedAt` 的歌曲排在有时间的歌曲之后。
- V4 格式：
  - 写入 Protobuf 记录流、ZSTD 内容分块和根清单。
  - 每个压缩对象不超过 2 MiB。
  - 清单不存在时，读取旧 `backup.json`、`backup-raw.bin` GZIP 与 `backup.bin` Base64。
  - 8 / 12 / 16 MiB 安全限制仅用于旧格式读取。
  - V3 仅用于读取迁移，未知未来版本明确拒绝。
  - 所有同步设备须支持 V4，传统数据或 V3 迁移须先确认升级。
- GitHub 快速路径：
  - 满足条件的提交通过 GraphQL `createCommitOnBranch` 与 `expectedHeadOid`，同时发布对象、清单和清理。
  - 其他提交通过 Git Data API 的 blob / tree / commit 写入，再以 GraphQL `updateRefs` 的 `beforeOid` 和 `force=false` 原子发布。
  - 上传请求的 Base64 仅用于传输封装，仓库正文为原始二进制。
  - 读取固定分支头，使用 raw 内容。
- WebDAV 并发保护：
  - 用 `If-None-Match: *` 创建不可变对象和首次清单。
  - 每次发布前重新核对清单指纹。
  - 有强 ETag 时同时使用 `If-Match`。
  - 无强 ETag 时，指纹一致后才发布并回读确认。
- 播放统计与流量统计延迟批量写入：
  - 播放统计在播放器 / Activity 关键生命周期 flush。
  - 流量累积器在请求或下载尝试结束时 flush。
  - 播放累计与每日桶不按数量淘汰。
  - 同步合并同时维护全量累计、每日桶和旧版 bucket-only 载荷提升，先提升再裁剪窗口。
- 平台 Cookie / 鉴权信息、GitHub Token、WebDAV 密码使用 `Android Keystore + EncryptedSharedPreferences` 加密保存。
- `DataStore` 只保存常规设置与非敏感状态。

#### 播放进度与高解析输出

- 长音频进度记忆：
  - 仅针对不少于 15 分钟的内容。
  - 小于 5 秒的位置不保存。
  - 距结尾 30 秒以内清零。
  - 显式播放位置优先。
  - 持久化字段为 `resumePositionMs`。
  - 它与第三方平台播放历史无关。
- BilibiliSponsorBlock 默认关闭，只发送当前 BV 号的 SHA-256 前缀，并在本地执行跳转。
- 一起听期间 BilibiliSponsorBlock 保持关闭，公开接口结果不写入同步或房间状态。
- 32-bit 高解析系统输出优先保留普通系统输出的高精度管线，并旁路响度均衡、声道平衡、音频可视化和应用内倍速。改动时同步确认设置文案和测试。
- 本地扫描先返回快速元数据，再由后台任务补全歌曲名、歌手、专辑和封面，首次扫描结果不是最终形态。

#### USB 独占

- 依赖兼容 **UAC1.0** 或 **UAC2.0 Type I PCM** 的 DAC、前台服务、唤醒锁和系统后台策略。
- 设置页的后台权限提示涉及息屏场景，修改相关逻辑时一并验证。
- 比特完美输出模式：
  - 按音源采样率和位深申请 DAC 格式。
  - 软件增益保持 0 dB。
  - 原生管线不做淡入淡出。
  - 音量由 DAC 硬件控制，区别于普通系统音量和应用内响度处理。
- 音量跟随普通媒体音量（`STREAM_MUSIC`），不使用 MediaSession 远程音量。后台锚点无法指定输出设备，Android 12+ 会把媒体音量设备切到扬声器。
- USB 设备插入响应是独立设置。关闭时，Activity alias 和播放服务广播入口都跳过 `USB_DEVICE_ATTACHED`。
- 前后台 USB runtime report 返回 `native_refresh_deferred` 时，播放器在有限次数内延迟重试，其他无效报告按 fail-closed 处理。

#### 一起听

- `shareAudioLinks=false` 时，房间快照和队列不暴露 `streamUrl`。
- 关闭该开关时立即清空已缓存的会话候选，`REQUEST_LINK` 直接拒绝。
- `streamUrl` 是历史协议字段名，Worker 不作为媒体代理或公共分发入口。
- 循环 / 随机模式通过 `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE` 同步。
- 成员控制校验目标 stable track key，过滤 `clientInstanceId`、`clientSequence`、`clientTimeMs` 之前的事件。
- `REQUEST_SET_TRACK` 只能选择当前队列曲目。
- 当前曲目最多保留 3 条去重 HTTP(S) 候选。
- 听众先按本机音质策略解析，候选只作为本次会话的失败回退，不写入普通歌曲或离线缓存。
- 服务端位置按曲目时长推算，单曲循环按时长取模。
- 同一成员凭成员密钥重连，不触发新成员自动暂停。

---

### 常见扩展路径 / Extension Paths

#### 1. 新增 Explore 搜索源

适用于把新平台接入 `Explore` 页的搜索或发现流。

1. 在 `:platform` 的 `platform/<平台>/api` 包实现客户端，缓存和业务编排放在同库对应平台职责包，维护协议与业务之间的包级依赖规则。
2. 在 `ExploreViewModel` 增加请求、分页和状态映射。
3. 在 `ExploreScreen` / Host 页面补充平台标签和结果 UI。
4. 需要播放时，接入 `PlayerManager` 的音源解析链路。
5. 需要下载时，补齐 `AudioDownloadManager` 和下载元数据映射。

#### 2. 新增播放页元数据补全源

适用于补封面、歌词和曲目信息。

1. 在 `:platform` 的 `platform/search/api` 包实现 `SearchApi` 接口，共享音乐 DTO 保留在 `:model`。
2. 在 `AppContainer` 注册单例。
3. 在 `AppContainer.searchManager` 的 provider 登记路由，匹配和降级规则在 `:platform` 的 `platform/lyrics` 包维护并测试。
4. 按需补充 `MusicPlatform`、字符串资源和调试探针。

#### 3. 新增在线播放平台

1. 参考 `:platform` 的 `platform/bilibili/` 或 `platform/youtube/` 设计客户端与播放仓库。
2. 需要特殊 Header 时，扩展 `core/player/engine/datasource/ConditionalHttpDataSourceFactory.kt`。
3. 在 `core/player/url/` 与对应 `resolver/` 的 URL 解析链路接入平台。
4. 下载、歌词、封面和播放统计保持边界清晰，缓存与永久下载使用各自的实现。
5. 需要同步到网易云我喜欢的音乐时，提供稳定的网易云歌曲 ID 或可验证映射，并复用 `LocalPlaylistRepository` 的候选校验逻辑。

#### 4. 修改网易云播放兜底

1. 入口在 `core/player/url/PlayerManagerUrlExtensions.kt` 的网易云 URL 解析流程。
2. 匹配与打分逻辑在 `core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`。
3. 兜底只处理网易云无权限、无可用播放结果或试听片段，不扩展为跨平台聚合搜索。
4. 调整匹配策略时，同时考虑歌名、歌手、分 P、时长误差和缓存 key 稳定性。

#### 5. 新增设置项

1. 在 `data/settings/AutoSettingsSchema.kt` 登记 key、默认值、类型和展示元数据。
2. 简单开关使用 KSP 生成的 `AutoSettingsRepository` 和 `AutoSettingsSwitchItems`。
3. 有副作用、互斥逻辑、权限或启动快照需求的设置，使用手写 setter。
4. 设置影响启动早期行为时，同步更新对应 snapshot：`BootstrapSettingsSnapshot`、`ThemePreferenceSnapshot` 或 `PlaybackPreferenceSnapshot`。
5. UI 入口放在 `SettingsScreen.kt` 对应的 `SettingsPage` 或 `ui/screen/tab/settings/component/` 下。
6. 设置页相关职责分布：
   - `SettingsDownloadDirectoryPreflight.kt`：下载目录的超时探测和 Provider 失败分类，设置页只消费探测结果。
   - `SettingsNavigationSearch.kt`：设置页切换和搜索入口。
   - `SettingsThemeControls.kt`：主题模式和调色选项，含切换请求与动画起点的决策。
   - `SettingsPlaybackControlLayout.kt`：播放控件位置和尺寸的对话框状态与偏好变更决策。
   - `SettingsPersonalizationContent.kt`：个性化和歌词外观卡片，按卡片独立订阅设置流。
7. 新增或改名设置时，同步补齐：
   - 中英文字符串
   - `SettingsSearchIndex.kt` 搜索关键词
   - 设置页可见性 / 过滤测试
   - `AutoSettingsGeneratedTest`
8. 设置控制 Activity alias、播放服务、系统入口或启动前行为时，验证生成 key、手写 setter、启动 snapshot 和实际入口读取同一份偏好。
9. 新增复杂偏好模型时，把归一化、边界裁剪和布局计算拆成可单测函数，不在 Compose 组件内隐式处理。
10. 探索页搜索历史由 `ExploreSearchHistoryRepository` 保存。`explore_search_history_enabled` 关闭时，探索页隐藏历史并停止新增记录，已有记录保留。
11. 歌词字号分为封面页和歌词页两组，歌词和翻译各自独立。修改时同步更新 `SettingsRepository.lyricFontScalesFlow`、`setLyricFontScale(target, scale)` 和对应的预览 / 播放调用点。

#### 6. 修改 USB 独占播放

1. 先阅读：
   - `core/player/usb/sink/UsbExclusiveAudioSink.kt`
   - `core/player/usb/transport/`
   - `core/player/usb/session/`
   - `core/player/policy/usb/UsbAudioSinkReconfigurationCoordinator.kt`
   - `core/player/watchdog/PlayerManagerStartupWatchdogExtensions.kt`
   - `core/player/lifecycle/PlayerManagerLifecycleExtensions.kt`
   - 相关测试
2. 当前实现覆盖 **UAC1.0** 和兼容 **UAC2.0 Type I PCM** 设备。扩展到更复杂的 UAC2.0 拓扑或非 Type I PCM 设备时，同步更新文档、能力边界、诊断和兼容性假设。
3. 同时考虑：
   - 设备选择、采样率 / 位深策略
   - 32-bit PCM、PCM float 软件转换
   - 比特完美音量
   - UAC2 时钟拓扑、显式反馈端点（隐式反馈尚不是可用候选）
   - 前后台缓冲区、唤醒锁、后台权限提示和系统回退链路
4. 修改自动恢复、keep-alive 或后台审计时，验证前台播放、息屏后台、USB 拔插和 system fallback 四条路径。
5. 改动反馈时钟、长调度间隙重捕获、协调式重配置、动态传输缩放、背压恢复或候选位深回退时，同步检查：
   - `UsbExclusiveOutputFormatResolverTest`
   - `UsbExclusivePcmWritePlannerTest`
   - `UsbExclusiveSessionControllerReusePolicyTest`
   - `UsbAudioSinkReconfigurationCoordinatorTest`
   - native USB feedback / PCM / UAC 测试
6. Runtime Report v2 字段按 fail-closed 解析。修改反馈端点、状态、holdover、恢复 action 或代次字段时，同步更新 Kotlin parser 和边界测试。
7. 错误语义或恢复策略变化时，同步更新设置页 / Debug 页诊断展示和对应测试。
8. 改动 USB 设备插入响应时，同时检查：
   - `UsbDeviceAttachHandling.kt`
   - `AudioPlayerService.kt`
   - `AndroidManifest.xml` 中的 Activity alias
   - `AutoSettingsSchema.kt` 设置生成链路
9. Native 变更至少运行三组 host gate 和四 ABI Android 编译。host 模型、ABI 编译和真实 DAC 验证是三个独立的通过条件。

#### 7. 修改 GitHub / WebDAV 同步

1. 先理解 `data/model/sync/SyncDataModels.kt` 与 `:sync` 中 `data/sync/codec/SyncDataSerializer.kt` 的兼容策略。共享载荷模型放在 `:model`，不放在 GitHub provider 包。
2. 同步对象包括歌单、收藏歌单、最近播放、删除记录、播放统计和独立歌词修改记录。
3. 格式约定：
   - V4 使用 Protobuf 记录流、ZSTD 内容分块与根清单，发布点沿用 `neriplayer-sync-v3.manifest` 文件名。
   - V3 仅用于读取迁移。
   - 仅在清单不存在时读取旧 JSON、原始 GZIP 与 Base64。
   - GitHub 二进制上传使用 Base64 传输封装，仓库内保存原始正文。
   - 歌词迁移完整保留旧数据，不提供有损选择，详见 `modules/sync/LYRIC_SYNC.md`。
4. `songOrderVersion=0` 为旧版顺序，`songOrderVersion=1` 为当前展示顺序。序列化、合并和落回本地歌单时保留旧数据迁移。
5. 歌单成员用 `syncMembershipTokens` / `removedMembershipTokens` 表达 observed-remove 语义：
   - 新增字段兼容旧 JSON 与 ProtoBuf 的缺字段载荷。
   - 带 token 的成员使用 token 裁决删除，不退回只比较 `addedAt` / `deletedAt`。
   - 一起验证删除后撤销、备份恢复和跨设备同步，防止旧删除记录再次移除已恢复成员。
6. 缺字段或畸形快照先清洗再合并：
   - `SyncSong` 至少有 id、audioId 或 mediaUri 之一。
   - 删除记录需要有效删除时间。
   - 缺失 `addedAt` 的歌曲只作低优先级展示项。
7. `CoverUrlMapper.kt` 位于与 provider 无关的 `data/sync/`。
8. GitHub 与 WebDAV 的首次上传和双端合并统一调用 `data/sync/merge/engine/SyncDataMerger.kt`。
9. 业务规则在共享组件维护，宿主负责资源文案与系统歌单解析。`SyncSession` 统一执行冲突重试、本地 mutation version 校验和落库确认，后端提供传输与远端版本接口。
10. 保持 `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt` 的延迟同步、周期同步、validated network 检查和失败重试行为。
11. GitHub 清单发布规则：
    - 按固定远端分支头发布。
    - 批量提交校验 `expectedHeadOid`，分阶段发布用 `beforeOid` 与 `force=false` 原子更新，冲突时失败。
    - 快速路径预算见 `modules/sync/README.md`。
    - 已发送批量提交后的错误不切换到另一条写入流程。
12. WebDAV 后续清单写入先核对指纹，有强 ETag 时使用 `If-Match`，没有时发布后回读确认，发现变化按冲突重新合并。
13. 敏感信息统一走 `SecureTokenStorage.kt` 或 `WebDavStorage.kt`，不放入 `DataStore` 或明文 JSON。
14. 存储文件名、键名、Worker 类全名、任务名与输入键是升级兼容边界，迁移模块时保持不变。
15. 设备 ID 的读取和首次创建共享同一把锁，因果计数器与删除状态保持原子提交。
16. 新的同步文件自动进入整目录 CRAP 门禁，任意方法分数大于 9 即失败。

验证命令：

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :local:verifyCrap :local:lintDebug
```

#### 8. 修改下载存储

1. 先阅读 `ManagedDownloadStorage.kt`、`naming/ManagedDownloadNaming.kt`、`task/DownloadTaskStore.kt`、`policy/DownloadLifecyclePolicies.kt` 和相关单元测试。
2. 同时考虑默认应用目录、SAF 自定义目录、迁移、历史命名、元数据文件和 `.nomedia`。
3. 下载任务先写入 `files/download_staging/`，再提交到正式目录。`.resume.json` 与 `.hls.json` 属于续传恢复，不作普通临时文件清理。
4. 默认下载并发是 **6**，设置允许 **1-8**。修改并发、重试或网络恢复时，同步检查 `DownloadParallelism.kt`、`AudioDownloadManager.kt` 和 `GlobalDownloadManager.kt`。
5. 修改目录迁移、删除语义、续传检查点或 sidecar 写入时，补充或更新对应单元测试。
6. 移动网络高风险下载确认通过 `GlobalDownloadManager.trafficRiskDownloadRequests` 交给 `NeriApp` 的全局对话框宿主。改动时检查播放页、更多面板的遮挡与输入，并保留 `MainActivityTrafficRiskDownloadTest` 的实际入口覆盖。

#### 9. 修改歌词显示、分享或音译

1. 播放页歌词主要在 `ui/component/lyrics/AdvancedLyricsView.kt`、`ui/component/lyrics/SyncedLyricsView.kt` 和 `NowPlayingScreen.kt`。
2. 全屏歌词页在 `LyricsScreen.kt`，歌词分享入口复用 `LyricShareSheet.kt`。
3. 音译显示由 `lyric_translation_use_phonetic` 设置控制，需先开启翻译且当前歌词存在音译数据。
4. 日语歌词翻译间距区分假名和普通 CJK 文本。修改时同时检查 app 侧 `resolveLyricTranslationExtraGap` 与 `:accompanist-lyrics-ui` 子模块的 `resolveJapaneseLyricTranslationTopPadding`。
5. 长按歌词打开分享面板。修改手势时，同时检查点击跳转、手动歌词偏移和高级歌词视口滚动。
6. 歌词卡片通过 `FileProvider` 分享缓存文件。修改输出位置时，同步检查 `file_paths.xml` 和缓存清理。

#### 10. 修改存储占用与缓存清理

1. 入口在 `data/local/storage/StorageUsageAnalyzer.kt` 和 `SettingsStorageCacheSection.kt`。
2. 新增缓存目录时，先判定它属于可清理缓存、下载内容、诊断文件还是应用数据。
3. 清理只覆盖可再生成内容，下载歌曲、下载歌词、下载索引和授权数据不被普通清缓存删除。
4. 下载暂存包含工作文件、续传凭据和 HLS 检查点，普通缓存清理保留它们。相关文件由下载恢复流程处理，不根据 UI 的任务状态快照整目录删除。

#### 11. 修改网易云歌单详情缓存

1. 缓存入口是 `NeteasePlaylistCacheRepository.kt`，页面状态在 `NeteaseCollectionDetailViewModel.kt`。
2. 缓存签名基于曲目数量和最近曲目 ID，用于判断是否复用曲目列表。
3. 网络失败或解析失败时回退缓存，手动刷新保留强制刷新语义。
4. 专辑详情不使用歌单缓存，避免不同数据模型互相污染。

#### 12. 修改词幕适配

1. 词幕 SDK 接入位于 `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`，输出控制器位于 `lyrics/output/`。播放器只提供歌词、偏好、进度与生命周期快照，不直接管理 SDK。
2. 开关由设置项 `lyricon_enabled` 控制，并由播放器生命周期同步。
3. 歌词数据使用 `LyricEntry`，逐字信息来自 `WordTiming`，翻译行按时间容差匹配到原文行。
4. Lyricon / SuperLyric 位置 feed 独立按 200 ms 推送，使用 elapsed realtime 做死区重算。修改进度节奏时保持前后台时序一致。
5. 保持 Lyricon、SuperLyric、状态栏歌词、播放页高级歌词和外部蓝牙歌词的歌词结构兼容。
6. 蓝牙歌词的原文与翻译是独立开关。同时输出时，通过同一个原子快照更新标题 / 艺术家字段，并保留曲目信息、字段长度限制、空白清洗和重复提交去重测试。
7. `bluetooth_metadata_mode` 只改变实际蓝牙输出时的展示，未连接蓝牙时保留灵动岛行为。
8. 车载浏览入口位于播放运行库的 `service/car`：
   - 通过私有服务 Binder 复用现有 framework 会话。
   - 浏览不提升前台、不自动恢复播放，明确起播时才启动私有播放服务。
   - 浏览 ID 在当前曲库重新验证。
   - 封面使用受限只读 Provider 和有界 JPEG 缓存，不接受客户端传入的任意文件或远程地址。
   - Compat 仅提供现有会话的浏览搜索桥接。

#### 13. 修改一起听

1. 客户端会话、HTTP / WebSocket 传输、编解码与有界读取位于 `modules/listentogether`，分别由 `data/ltw`、`api/ltw` 和 `listentogether/protocol` 包维护，协议模型在 `modules/model`。
2. app 的 `core/di/ltw` 只绑定平台能力，播放器适配在 `modules/playback/runtime` 的 `core/player/ltw`。
3. 服务端逻辑在 `np-submodule/NeriPlayer-LTW`。
4. 协议字段变更同时兼容客户端和 Worker，并更新测试。
5. `shareAudioLinks=false` 时，HTTP / WS 房间快照都不暴露 `track.streamUrl` 与 `queue[*].streamUrl`，关闭开关时立即清空房间里已缓存的会话候选。
6. `REQUEST_LINK` / `LINK_READY`、成员控制、房主离线恢复和版本门控更新一起检查，避免旧状态覆盖新状态。
7. 循环 / 随机模式使用 `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE`。成员请求与 `LINK_READY` 校验目标 stable track key，避免异步结果落到错误歌曲。
8. 首次加入需要 `joinSecret`，已有成员重连需要 `memberSecret`。这些密钥不写入脱敏房间状态或日志，邀请 URI 的 `secret` 参数按敏感输入处理。
9. 控制事件可携带 `clientInstanceId`、`clientSequence` 和 `clientTimeMs`。Worker 拒绝过期顺序，`REQUEST_SET_TRACK` 只能选择当前队列已有曲目，客户端兼容路径保持一致。
10. 成员显式离开时调用 `/api/rooms/:roomId/leave`，Worker 删除成员并广播 `MEMBER_LEFT`。
11. 普通 WebSocket 断线视为传输波动，保留成员凭据以支持重连。控制者显式离开则关闭房间。
12. 房间号 6 位、昵称 1-24、队列上限 2000 和请求去重是协议边界，客户端与服务端校验同时修改。
13. 设置页支持自定义服务端地址和可用性测试，不硬编码单一地址。
14. HTTP 控制回退按服务端、房间、用户和凭据校验回包。离开、断开或更换会话时取消旧任务，协程取消不进入错误或重连流程。
15. 队列变更的构建与应用保留重复曲目的 occurrence 身份、连续插入顺序和当前歌曲，修改后运行模块往返测试。

验证命令：

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
```

#### 14. 修改主导航与玻璃转场

1. 主标签生产路径由 `NeriApp.kt` 与 `MainTabLayerHost.kt` 共同承载，标签顺序与方向由 `resolveMainTabTransitionDirection` 决定。
2. 转场期间同时保留出场和入场 scene，通过 `SaveableStateHolder` 保留各标签状态。快速反向或连续点击时，不先清空旧 scene。
3. 每个 scene 有独立 `MainTabGlassOwner`。修改 Advanced Glass 时，只有当前可见 owner 参与合成，详情页 handoff 由导航层接管。
4. `coherent_feedback_enabled` 默认关闭，详情页使用抽屉式前景上升和背景轻微下沉。开启后，背景与详情页同步接力。
5. 修改动效时覆盖正向、反向、中断、重复请求、状态恢复和启动首帧。几何测试不把尚未布局的 `Rect(0, 0, 0, 0)` scene 当作真实重叠。
6. 同步检查：
   - `NeriAppMainTabTransitionPolicyTest`
   - `AdvancedGlassNavigationTransitionTest`
   - `NeriAppNavigationTransitionTest`
   - `HostNavigationTransitionGeometryTest`
7. 主题适配复用 `ui/theme/NeriDesign.kt` 的几何与 `MaterialTheme` 的配色、字体，弹窗使用 `ui/component/overlay/` 的现有入口。修改主题选择时，同时维护 `SettingsThemeControls.kt`、主题快照与预览提示记录。
8. Material 与 Clean 的深浅色、手机与平板分别验证。涉及返回与拖拽时，覆盖系统返回、预测性返回完成 / 取消 / 反向、面板拖动和内容滚动。
9. 截图、录屏、模拟器和实体设备证据分别记录，静态截图不能证明触摸与导航正确。

#### 15. 修改桌面小组件、启动器快捷方式或全局反馈

1. 小组件入口在 `widget/PlaybackWidgetProviders.kt`，状态和取色策略在 `PlaybackWidgetState.kt` 与 `PlaybackWidgetVisuals.kt`。
2. RemoteViews 布局同时有普通资源和 API 31 专用资源。视觉、裁剪或预览图变更时，检查 `layout/`、`layout-v31/`、`xml/` 与 `xml-v31/` 是否一致。
3. 播放控制动作最终进入 `AudioPlayerService`。新增动作时检查前台服务启动策略、媒体会话状态刷新和无歌曲 / 缓冲状态下的反馈。
4. 启动器快捷方式由 `navigation/LauncherShortcuts.kt` 映射。新增快捷方式时同步更新 `res/xml/shortcuts.xml`、中英文字符串和 `LauncherShortcutsTest`。
5. 全局 Snackbar 优先走 `AppFeedback` / `ViewSnackbar`，页面不各自持有无法跨覆盖层显示的 SnackbarHost。
6. 歌单批量导出、删除撤销和同步删除记录相互关联。修改任一项时，同时检查：
   - `PlaylistExportSheetTest`
   - `AppFeedbackPolicyTest`
   - `LocalPlaylistRepositoryTest`
   - `SyncPlaylistDeletionPolicyTest`

#### 16. 修改歌曲百科

1. 共享契约在 `:model` 的 `data/model/encyclopedia/`，网易云实现与解析在 `:platform` 的 `platform/netease/encyclopedia/`。仅对可识别的网易云歌曲请求资料。
2. `SongEncyclopediaRepository` 区分成功、不支持、暂无资料和失败。取消向上传播，创作团队、艺人等附加请求失败时只降级对应板块。
3. `NowPlayingViewModel` 使用 `RequestGeneration` 丢弃旧请求，`SongEncyclopediaUiState.forSongKey` 在渲染前再次校验歌曲身份。切歌、关闭和重试一起检查。
4. `ui/screen/nowplaying/actions/SongEncyclopediaContent.kt` 组合板块，`NowPlayingSecondaryActions.kt` 处理面板及艺人、歌单、评论导航，不在百科上继续堆叠弹层。
5. 修改时检查仓库、解析、缓存和 ViewModel 单测，以及百科板块的布局、评论入口与手势设备测试。

---

### 调试与日志 / Debugging & Logs

- 开发者模式：在设置页连续点击**版本号** 7 次开启，底栏出现独立 `Debug` 页。
- 普通文件日志仅在开发者模式开启时启用。
- 崩溃日志由 `ExceptionHandler` / `NativeCrashHandler` 独立落盘，与开发者模式无关。
- Debug 页包含 YouTube、Bili、Netease、Search、Listen Together 探针，以及普通日志和崩溃日志查看器。

Windows：

```bash
adb logcat | findstr NeriPlayer
```

Linux / macOS：

```bash
adb logcat | grep NeriPlayer
```

---

### 测试与提交流程 / Testing & PR

#### CI 与发布流程

自动 CI：

- 候选 APK 与测试并行构建，打包前运行 Native Release host 检查。
- 最终发布等待 JVM、lint、Worker 与模拟器作业完成，并核验候选产物身份和测试报告。

手动 Release：

- 针对 `target_ref` 实际检出的源码，依次运行 JVM 测试、lint、可用的模块边界检查与 Native Release host 检查。
- 验证报告以目标提交 SHA 命名。
- `test_release` 默认为 `true`，生成未签名测试 APK 与草稿预发布。
- `test_release` 设为 `false` 时，构建签名 APK 并正式发布。
- 不含完整主 CI 的模拟器、Worker 与 CRAP 矩阵。
- 历史版本使用对应的新旧 Native 测试入口，存在测试设施但缺少配置时拒绝发布。

额外验证：

- 模拟器、sanitizer 和真实 DAC 验证按变更风险执行。
- 真实硬件不是每次发布的前置条件。

#### 提交前检查

提交前至少完成以下检查。

1. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. 单元测试与 CRAP 门禁：
   ```bash
   ./gradlew :app:verifyCrap
   ```
3. 修改登录态、播放解析链路或回归风险较高的集成行为时，按需执行 smoke test：
   ```bash
   ./gradlew :app:testDebugUnitTest -DrunNeteaseSmoke=true
   ./gradlew :platform:testDebugUnitTest \
     -DrunYouTubePlaybackSmoke=true \
     -DyoutubeSmokeVideoId=VIDEO_ID
   ```
   - 将 `VIDEO_ID` 替换为实际视频 ID。
   - 可追加 `-DyoutubeSmokeForceRefresh=true` 或 `-DyoutubeSmokeCookieFile=/absolute/path/to/cookies.json`。
4. 修改资源、UI、导航、设置、同步或存储逻辑时，执行 lint：
   ```bash
   ./gradlew :app:lintDebug
   ```
5. 涉及 Compose UI、权限、Activity 或登录流程时，在设备或模拟器上执行：
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
6. 修改 Native USB 时，对齐独立 Android Native CI：
   ```bash
   for profile in release-werror-asserts asan-ubsan tsan; do
     tools_pub/usb-async-lab host-test \
       --manifest modules/native/src/main/cpp/tests/usb/config/run-manifest.example.yaml \
       --profile "$profile"
   done

   ./gradlew :native:externalNativeBuildDebug \
     --no-daemon \
     --warning-mode all \
     --stacktrace
   ```
   - host gate 固定执行同一组 CTest。
   - Android 编译需确认 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 均生成非空 `lib_neri.so`。
7. 修改 DSP 时，单独运行其 host 测试：
   ```bash
   cmake -S modules/native/src/main/cpp/tests/dsp -B .tmp/dsp-host -DCMAKE_BUILD_TYPE=Release
   cmake --build .tmp/dsp-host --parallel
   ctest --test-dir .tmp/dsp-host --output-on-failure
   ```
   - 内存 / 未定义行为检查使用独立构建目录，并传入 `-DDSP_TEST_SANITIZER=asan-ubsan`。
   - USB 的 sanitizer profile 不运行 DSP 测试。
8. 修改一起听 Worker 时：
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
   - `npm run check` 依次执行 Node.js 版本检查、`node --check`、协议测试和 `wrangler deploy --dry-run`。
   - 协议或房间状态改动还需实际验证 create / join / ws 流程。
9. 测试放置位置：
   - 单元测试放在被测代码所在模块的 `src/test/`，宿主集成测试放在 `app/src/test/`。
   - 设备或 Compose UI 测试放在对应模块的 `src/androidTest/`，应用宿主集成测试放在 `app/src/androidTest/`。
10. 行为变更涉及 README、设置文案、用户流程或同步格式时，同步更新文档。

#### CRAP 质量门禁

```bash
./gradlew verifyModularization
```

该任务执行：

- 模块边界检查
- app 与自有库的 lint 和 JVM 测试
- 合并 JaCoCo 覆盖率，输出 app 与库主源码的全部 JVM 方法分数，并单独列出 CRAP > 8 的条目

门禁规则：

- `config/quality/crap-scope.json` 的 `source_patterns` 覆盖完整源文件，`method_scopes` 覆盖原入口中受拆分影响的方法。
- 范围内任意方法 CRAP > 9 即失败，范围外的方法保留在完整报告中。
- 分数用 JaCoCo 复杂度覆盖率近似路径覆盖率，不衡量 Native 代码，不替代耦合度审查。
- `:app:check` 和 Android CI 均执行该门禁。
- 完整评分和范围报告位于 `app/build/reports/crap/`，口径与依赖见 [质量检查说明](tools_pub/quality/README.md)。

播放库门禁：

- `:playback:logic` 和 `:playback:runtime` 通过 `build-logic.android.module-quality` 提供 `verifyCrap`。
- 独立门禁使用模块自己的覆盖率和共享配置中的对应范围，范围为空时失败。
- 播放策略、运行协调、PCM、宿主接口、USB policy 和小组件呈现包自动包含新增文件。
- app 的播放器适配器参与聚合门禁。
- 迁移到运行库的歌词渲染、Room 队列、解码器和播放 Range 设备测试，通过 `:playback:runtime:connectedDebugAndroidTest` 执行。

行数预算：

- `OwnedMainSourceLineBudgetTest` 要求受检自有主源码及组件严格少于 2000 物理行，USB `exclusive/` 下的自有 `.cpp` / `.h` 同在范围内。新增组件时同步维护测试中的文件清单。
- `LocalManagementLineBudgetTest` 对下载、本地数据、媒体库及相关测试执行同样的限制。移动入口文件或拆分目录时，同步更新测试中的必需文件路径和扫描范围。
- 第三方 libusb 和测试文件不受该上限约束。

#### 拆分约定

- 状态、异步任务和释放逻辑交给对应职责的组件，通过小接口接入外部能力。
- `PlayerManager` 的播放器和全局服务访问集中在对应的 `PlayerManager*Port` 适配器。
- 一起听的成员操作、房间状态、连接恢复和控制结果由 `:listentogether` 的 `session/` 组件分别维护，平台与播放器实现通过宿主接口注入。
- 唤醒锁随会话释放，异步任务由所属组件取消，不回读全局容器。
- `NowPlayingScreen`、`SettingsScreen` 和 `NeriApp` 组合页面与功能组件，编辑会话、目录选择、设置领域绑定和导航副作用在对应组件处理。
- 不把原入口作为 receiver 搬进扩展文件，新组件不回读原入口的内部状态。
- `:local` 的本地存储按 `source`、`scan`、`accounting`、`cleanup`、`policy` 分类，数据契约集中在 `:model` 的 `storage` 包。
- 存储统计链路：
  - `StorageUsageScanner` 通过数据源接口采集快照。
  - `StorageUsagePresenter` 只读取快照和字符串资源。
  - `StorageCacheCleaner` 通过文件和平台清理端口执行操作。
  - Room 和全局服务访问集中在 `StorageUsageAndroid.kt`。
- 新增存储组件保持这个单向依赖，并纳入完整文件门禁。

#### 测试覆盖重点

- **YouTube**：登录、Cookie 轮换、匿名会话、挑战解析、PoToken、播放解析、Range / Seek 策略与预取（含长音频大幅 Seek 的快速恢复）。
- **网易云**：歌词、本地 smoke test、播放兜底和播放响应解析。
- **USB 独占**：
  - keep-alive、启动看门狗、前后台恢复
  - 32-bit / float 输出
  - UAC2 显式反馈、长调度间隙重捕获、协调式重配置
  - Runtime Report v2、背压恢复、延迟 runtime 刷新重试
  - 比特完美音量、USB 插入响应开关、音频焦点策略
- **导航**：主标签双 scene 转场、快速反向切换、抽屉 / 连贯详情反馈、玻璃 owner 隔离、未布局 scene 几何过滤。
- **主题与资料**：Material / Clean 主题与预览提示、预测性返回、百科请求身份与板块呈现、播放页覆盖层上的移动网络下载确认。
- **系统入口**：桌面小组件状态 / 取色 / RemoteViews 资源、启动器快捷方式映射、全局 Snackbar 覆盖层。
- **下载**：元数据、命名、目录迁移、快照缓存、`.nomedia`、删除语义和启动恢复。
- **启动**：启动阶段、通知权限、播放服务启动、历史记录与安全模式恢复规划。
- **本地数据**：本地扫描、元信息补全、封面回退、系统歌单去重和歌单顺序稳定性。
- **同步**：
  - GitHub / WebDAV 序列化、缺字段快照清洗、旧歌单顺序迁移、删除策略
  - 播放统计滚动窗口、全量累计合并、旧版 bucket-only 兼容
  - WebDAV 并发回退、原子文件写入和上传重试
- **播放策略**：长音频进度阈值、显式位置优先、BilibiliSponsorBlock 本地跳转与一起听禁用策略。
- **一起听**：
  - 地址校验、版本门控、循环 / 随机模式、stable track key 目标校验
  - 播放同步规划、会话候选回退、邀请 / 成员密钥
  - 显式离开 / 重连、事件排序、Session 控制 / 取消与协议兼容
- **歌词与音效**：歌词视图、日语假名翻译间距、逐词时间、外部蓝牙歌词、播放音效和播放策略。
- **配置与诊断**：配置备份、设置生成、安全守卫、崩溃日志文件和安全模式。

#### PR 要求

PR 描述包含：

- 变更动机
- 关键实现点
- 风险与兼容性影响
- 测试方式
- UI 变更的截图或录屏

不提交：

- APK、签名文件、IDE 本地配置
- 缓存、日志、临时构建产物
- 授权 Cookie、Token、完整配置备份、个人数据

Commit 信息遵循 Conventional Commits，例如 `feat: ...`、`fix: ...`、`docs: ...`。

---

### 法律与许可 / Legal & License

- 项目仅供学习与研究使用，请勿用于非法用途。
- 本项目使用 **GPL-3.0** 协议。
- 提交贡献即表示你同意至少以 GPL-3.0 分发你的修改。
- `modules/native/src/main/cpp/README.md` 中的替代授权只覆盖其中列出的 NeriPlayer 自有 Native 源码，不含第三方代码和其他仓库内容。
- Native PR 本身不授予替代授权。同意双授权的贡献者，需在 PR、commit 或版权持有人接受的其他可审计记录中加入该 README 提供的声明。
- 未提供显式双授权的外部 Native 贡献按 GPL-3.0 接受，不进入“满足署名条件即可闭源使用”的例外范围。

---

### 沟通方式 / Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues)：缺陷、功能请求与改进建议，每个 Issue 只提交一个问题。
- [Discussions Q&A](https://github.com/cwuom/NeriPlayer/discussions/categories/q-a)：使用方法、设置与兼容性咨询。
- [README.md](./README.md)：功能与使用说明。
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)：社区行为准则。

较大的结构性改动，建议先开 Issue 对齐方向。
