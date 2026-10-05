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

### 文档地图 / Documentation Map

维护文档时建议按用途拆开看：

- [README.md](README.md)
  - 面向用户和新贡献者，说明项目定位、能力边界、安装构建、同步与隐私。
- [CONTRIBUTING.md](CONTRIBUTING.md)
  - 面向开发者，说明真实模块边界、扩展路径、测试和提交要求。
- [docs/kotlin-helpers.md](docs/kotlin-helpers.md)
  - 说明请求代次、单位、播放器读取和协程结果等辅助工具的用法与边界。
- [tools_pub/quality/README.md](tools_pub/quality/README.md)
  - 说明 CRAP、模块归属、依赖边界检查的范围、命令和验证限制。
- [modules/native/src/main/cpp/README.md](modules/native/src/main/cpp/README.md)
  - 说明 NeriPlayer 自有 Native 源码的替代授权范围、第三方排除项和
    外部贡献所需的显式双授权声明。
- [modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md](modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md)
  - 说明公开 Native USB host 门禁、CI 覆盖与真实设备验证边界。
- [modules/native/src/main/cpp/tests/usb/corpus/README.md](modules/native/src/main/cpp/tests/usb/corpus/README.md) 与
  [modules/native/src/main/cpp/tests/usb/fixtures/README.md](modules/native/src/main/cpp/tests/usb/fixtures/README.md)
  - 说明公开测试语料/夹具只能使用合成或可审计资料，真实设备证据留在私有目录。
- [np-submodule/NeriPlayer-LTW/README.md](https://github.com/TheSmallHanCat/NeriPlayer-LTW#readme)
  - 面向一起听服务端部署者，说明 Worker API、事件模型、部署和本地检查。

行为变更如果影响用户理解，请同步更新 README；
如果影响扩展方式、测试方式或模块边界，请同步更新 CONTRIBUTING。

---

### 开发环境 / Development Environment

- **Android Studio**：最新稳定版
- **JDK**：17
- **Kotlin**：2.4.10，JVM target 17
- **AGP**：9.4.1
- **Gradle**：9.6.1
- **compileSdk / targetSdk / minSdk**：37 / 36 / 28
- **NDK**：`27.0.12077973`
- **CMake**：`3.22.1`，由 Android 构建脚本固定指定
- **Node.js**：22，用于一起听 Worker 检查
- **版本名格式**：`<git短哈希>.<MMddHHmm>`
- **Release APK 文件名**：`NeriPlayer-<versionName>[-abi].apk`

补充说明：

- 仓库依赖 Git 子模块，首次克隆请使用 `--recursive`，或手动执行
  `git submodule update --init --recursive`。
- `buildSrc`、`ksp-annotations` 与 `ksp-processor` 使用 JDK 17 工具链；
  请安装 JDK 17，建议 Android Studio 的 Gradle JDK 和命令行 `JAVA_HOME` 也指向它。
- 构建脚本会读取 Git 短提交生成版本名，本地请确保已安装 Git。
- 依赖版本由 `gradle/libs.versions.toml` 与各模块 `build.gradle.kts` 管理。
- 应用只保留 `zh` 与 `en` 资源，见 `build-logic` 的 locale filter。

---

### 质量护栏 / Quality Guardrails

这个项目功能面比较宽，提交前请优先保护这些链路：

- **播放链路**：`PlayerManager`、播放解析策略、缓存、失败刷新、播放兜底、
  长音频进度记忆、BilibiliSponsorBlock 跳过策略、状态恢复、响度均衡、
  声道平衡、高解析输出、USB 独占 Native 链路、启动看门狗和前后台健康审计。
- **下载链路**：`AudioDownloadManager`、`GlobalDownloadManager`、
  `DownloadTaskStore`、`DownloadLifecyclePolicies`、`ManagedDownloadStorage`、
  续传检查点、sidecar 文件、任务队列恢复、取消清理和 SAF 目录迁移。
- **同步链路**：GitHub / WebDAV 的三路合并、删除记录、播放统计、
  缺字段快照清洗、JSON/ProtoBuf/Base64 格式兼容和 WebDAV 并发保护。
- **本地数据**：歌单 Room 事务、旧 JSON 升级与原子回退、本地元信息补全、配置导入导出、
  授权加密存储和 DataStore 设置。
- **歌词与播放页 UI**：`AdvancedLyricsView`、`SyncedLyricsView`、
  `LyricShareSheet`、歌词音译显示、日语歌词翻译间距、歌词长按分享和 Lyrics 全屏页。
- **导航与玻璃 UI**：`MainTabLayerHost`、详情页抽屉/连贯反馈、
  可打断主标签切换、页面状态保留、标准化 Snackbar 覆盖层和 Advanced Glass owner 接力。
- **系统入口与桌面外壳**：`LauncherShortcuts`、桌面小组件、
  `USB_DEVICE_ATTACHED` 处理开关和播放服务控制入口。
- **存储与缓存 UI**：`StorageUsageAnalyzer`、缓存清理选项、下载目录索引和 SAF 快照。
- **一起听**：Android 客户端、Worker 协议字段、角色权限、队列、
  版本门控更新、会话候选共享开关和房主离线恢复。
- **诊断恢复**：安全模式、JVM/Native 崩溃日志、ANR 记录和 Debug 探针。
- **本地持久化**：播放/流量统计的批量写入、生命周期 flush、原子文件替换和
  SAF/本地歌单初始化就绪状态。

对应测试分布在 app 与各库模块的 `src/test/` 和 `src/androidTest/`。
修改上述链路时，优先搜索同名目录或相邻测试类，再补新的覆盖。

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
4. 首次启动会进入免责声明与启动引导；通知和本地音乐权限会先说明用途，
   仅在用户主动点按后请求，也可以跳过。
5. 如需调试入口，在设置页连续点击**版本号** 7 次，底栏会出现独立 `Debug` 页。

---

### 构建发布版 / Release Build

发布版默认启用混淆与资源收缩。
普通 `assembleRelease` 默认在 APK 中只打包 `arm64-v8a`，手动多 ABI 输出需要额外参数。

1. 在 `~/.gradle/gradle.properties`、项目 Gradle properties 或命令行 `-P`
   中提供签名信息：
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```

   如果 `KEYSTORE_FILE` 使用相对路径，会按 `app/` 模块目录解析。
   当前 Release 构建**不会**回退到 debug signing config；
   Android Studio / IntelliJ 本地构建会自动允许未签名 Release 通过打包校验，
   便于 IDE 里的 `Build APK` / `assemble` 使用。命令行和 CI 仍然默认要求
   可用 keystore；GitHub PR 会自动构建未签名 Release 做打包校验，其他
   CI/PR 环境可以显式传入 `-PallowUnsignedRelease=true`。

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

自有库的入口与完整职责见根目录 [README.md](README.md#模块结构)。14 个库由 `gradle/owned-modules.txt` 统一登记，构建、lint 和结构检查读取同一份登记；覆盖率只收集包含 Kotlin/Java 生产源码的库，`:native` 使用独立的 host 测试与四 ABI 编译。一级目录表达业务领域，远程来源集中在 `:platform`；播放和下载保留规则与运行实现两个构建边界。

- 基础能力：`:common`、`:network`、`:model`、`:database`。模型不依赖项目实现；历史 Room schema 和完整升级链由数据库维护。
- Native：`:native` 维护 CMake 构建边界、崩溃处理、USB 协议与 PCM 计算、独占传输和第三方 libusb；app 消费它的 AAR，Kotlin 播放会话与 JNI 桥仍由 `:playback:runtime` 维护。
- 播放：`:playback:logic` 集中 policy/runtime/audio/queue，`:playback:runtime` 维护引擎、服务、USB、音源解析与接入。policy 的九个叶子包保持窄依赖，USB policy 归运行库；下载只能通过 `PlayerDownloadAccess` 调用。
- 下载：`:download:logic` 维护可复用规则与基础存储工具，`:download:runtime` 维护 Room、SAF、恢复和服务。规则不得依赖运行实现，Worker 与 JobService 的类全名保持稳定。
- 歌词：`:lyrics` 维护解析、转换、共用时间偏移和词幕输出。SDK 和位置推送位于同库 `lyrics/lyricon`，`lyrics/output` 管理异步请求代次、取消与偏移快照；播放器通过窄加载端口提供歌词和快照。歌词库不依赖平台或播放器实现。
- 平台：`:platform` 维护 Bilibili、网易云和 YouTube 的请求协议、账号、缓存与业务，以及评论、歌词来源和元数据搜索、匹配与回退。`api` 是同库协议职责，禁止反向读取账号仓库、Room 和播放器；各平台按包保持依赖边界。YouTube JS assets 和 consumer R8 规则随平台维护，解析能力通过 `api(:lyrics)` 对外提供。
- 本地数据：`:local` 按设置、媒体、歌单、统计、备份、流量与同步接入分包，维护业务映射、宿主接口和 WorkManager 适配。设置 schema 在此运行 KSP，页面渲染留在 app。
- 同步：`:sync` 同时维护 GitHub/WebDAV 传输、加密凭据、设备身份、因果计数器、会话、清洗、合并与 Worker 策略。传输和计算组件仍受各自依赖白名单约束，本地仓库适配位于 `:local`；删除记录与 mutation version 原子提交。
- 一起听：`:listentogether` 维护协议、传输、身份规则和客户端会话。协议只使用模型、序列化与标准库；业务通过宿主接口接入播放器、资源和 Android 服务。
- 应用与工具：`:app` 维护 Android 入口、Compose 页面和依赖组装；KSP 工具、上游歌词子模块、`build-logic`、`buildSrc` 和一起听服务端不计入 14 个库。Miuix 的上游源码与文档树不参与主应用构建。

所有库禁止引用 app、页面或 `AppContainer`，依赖不得形成循环；`:playback:runtime` 内部可以使用自己的 `PlayerManager`。设置通过 provider 按需读取，HTTP 客户端、账号与设备令牌由宿主注入，不通过全局容器绕过接口。Room 业务映射随本地或平台仓库维护；数据库和规则组件不得反向引用运行实现。

包含 Kotlin/Java 生产源码的库使用 `build-logic.android.feature-library` convention；纯 Native 的 `:native` 使用 `build-logic.android.library`，不接入 JaCoCo 或 JVM 测试。自有库均在 `gradle/owned-modules.txt` 登记真实 Gradle 路径，无需另行维护 JVM 覆盖率列表。测试随实现放在该库 `src/test` / `src/androidTest`，宿主集成测试放在 app。模块目录归属调整不改变既有生产包名或类全名；源码内部包与目录保持一致，相关路径契约、测试夹具、资源与 CRAP 选择器一起维护。

`verifyModuleBoundaries` 检查登记与实际目录、领域依赖、循环、禁止导入、源码所有者和包目录一致性。库主源码少于 2000 行，库和 `APP_FAMILIES` 区域每个目录最多 16 个直接源码文件。包级计算域由编译字节码门禁继续隔离。

#### Android 客户端关键路径

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - 应用初始化入口，负责语言、异常处理、`AppContainer`、
    Lyricon、全局下载管理和共享图片加载器。

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt` 是唯一对外入口，负责安全模式、启动流程、免责声明、
    启动引导、外部音频导入、一起听深链和顶层 Compose 宿主。
  - 平台登录 Activity 位于 `activity/auth/`，并在独立次进程中运行；
    `activity/sync/` 保存 Activity 侧的同步告警状态。
  - `UsbDeviceAttachHandling.kt` 负责按设置启停 USB 设备插入 Activity alias，
    播放服务也会复用同一策略过滤 `USB_DEVICE_ATTACHED` 广播。
  - `NeteaseWebLoginActivity.kt`、`NeteaseQrLoginActivity.kt`、
    `BiliWebLoginActivity.kt`、`BiliQrLoginActivity.kt` 与 `YouTubeWebLoginActivity.kt`
    是内部平台登录页。

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - 顶层 Compose 应用骨架，负责 `NavHost`、动态底栏、
    `MiniPlayer`、`Now Playing` 覆盖层、Debug 路由、主题、缓存清理和播放服务同步。
  - 主标签页面由 `MainTabLayerHost.kt` 保留出场/入场双场景，
    按标签顺序执行可打断横向转场，并为各场景保留 saveable state 和玻璃 owner。
  - `ui/feedback/` 承载应用级 Snackbar/Toast 反馈策略；新增全局反馈前先确认
    `AppFeedback` 与 `ViewSnackbar` 是否已经覆盖所需场景。

- `app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/`
  - `AdvancedLyricsView.kt` 与 `SyncedLyricsView.kt` 负责高级歌词排版、
    逐字/逐词高亮、翻译/音译显示、点击跳转和长按回调。
  - `LyricShareSheet.kt` 负责歌词行选择、复制、歌曲分享和歌词卡片生成。
  - LRC/YRC/TTML 解析和翻译对齐位于 `modules/lyrics` 的 `lyrics.parser` 包；共享歌词数据位于 `:model` 的 `lyrics` 包。
  - 旧 `AppleMusicLyric` 名称只存在于 `ui/component/LyricsCompatibility.kt`
    的 `@Deprecated` 包装中，新代码统一使用 `SyncedLyricsView`。

- `app/src/main/java/moe/ouom/neriplayer/ui/component/playback/`
  - `NeriMiniPlayer.kt` 负责底部迷你播放器、播放暂停和横向滑动切歌；
    播放音效与睡眠定时器面板也在该目录。
  - `ui/component/` 根目录中的同名文件主要是旧包兼容入口，新增实现应放入
    `lyrics/`、`playback/`、`download/`、`navigation/` 等职责子包。

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/`
  - `LibraryScreen.kt` 负责媒体库顶层分类，本地内容可在歌单/歌手之间切换，
    收藏页可展示歌单、已关注艺术家和热点歌单。
  - `LocalArtistLibraryGrid.kt` 展示本地艺术家网格、空状态和艺术家卡片。

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/`
  - `LocalArtistDetailScreen.kt` 展示本地艺术家详情，支持播放全部、
    多选、导出歌单和批量下载可在线解析的歌曲。

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/`
  - 网易云艺术家详情页，展示艺术家信息、热门歌曲、分页专辑和关注状态。

- `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/`
  - 网易云艺术家摘要、JSON 解析和详情页状态管理。

- `app/src/main/java/moe/ouom/neriplayer/ui/onboarding/`
  - 首次启动引导，覆盖语言、平台账号、权限说明、播放控件、GitHub 同步和个性化设置。

- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/`
  - `netease/api`：网易云客户端、加密、请求参数和二维码认证协议。
  - `bilibili/api`：搜索、二维码登录、收藏夹、合集和播放信息；仓库与跳过规则位于同库 `platform/bilibili`。
    Explore 链接识别会保留分 P、`cid` 和 `season_id` 上下文，改动时同步检查 `ExploreLinkRecognizer` 与 `ExploreViewModel`。
  - `youtube/api`：YouTube Music 客户端、PoToken、JS Challenge、请求/响应解析；协议模型位于 `:model`，认证持久化与缓存位于同库 `platform/youtube`。
  - `lyrics/api`：LrcLib、Kugou 和 AMLL 服务访问；来源仓库、匹配与回退位于同库 `platform/lyrics`。
  - `search/api`：`SearchApi`、网易云和 QQ 元数据搜索服务；`SearchManager` 位于同库 `platform/lyrics`，共享音乐模型属于 `modules/model`。
  - `AppContainer` 组装客户端与路由，注入 HTTP、调试配置和实时设置 provider；库不读取应用容器或播放器单例。
  - 纯文本歌词时间轴转换位于 `modules/lyrics` 的 `PlainLyrics.kt`，播放器与匹配器共享同一实现。

- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/comments/`
  - 评论来源、解析、分页和缓存；评论数据契约属于 `:model`；客户端 provider 与缓存由 `AppContainer` 组装注入。
  - 缓存实例的生命周期由宿主决定，库内不读取全局容器；仓库测试位于该模块，ViewModel 集成测试位于 `app`。
  - 歌曲来源标签属于 `:model`，Bilibili 历史播放身份解析属于同库 `platform/bilibili/playback/resolver`，无需引用播放器单例。

- `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`：Media3 ExoPlayer 的统一管理层，
    负责音源解析、播放队列、缓存、状态恢复、失败重试和播放策略。
  - `service/AudioPlayerService.kt`：前台播放服务、媒体通知、MediaSession 和媒体按钮。
  - 下载执行实现归 `:download:runtime`；播放器只消费 `host/PlayerDownloadAccess.kt`，下载状态契约位于 `:model` 的 `playback/storage`。
  - `effects/PlaybackEffectsController.kt`：倍速、音调、响度增强和均衡器。
  - `engine/`：Media3 渲染器与数据源组装；PCM 声道平衡、响度归一化和音频可视化位于 `:playback:logic` 的 `audio/processing` 和 `audio/reactive`。
  - `:playback:logic` 的 `runtime/stats`、`runtime/progress`、`runtime/transport` 和 `runtime/quality` 分别维护统计采集、播放进度、传输及音质控制；宿主通过对应 Port 提供副作用。
    播放命令与队列推进仍在 `playback/PlayerManagerPlaybackExtensions.kt`。
  - `:playback:logic` 的 `queue/state` 和 `queue/policy` 维护状态所有权与编辑/导航规则；队列数据契约位于 `:model` 的 `playback/queue` 包。
    `PlayerQueueSnapshot` 持有列表与当前索引；`PlayerQueueSessionSnapshot` 将队列、
    随机播放模式和恢复顺序组成完整会话，由 `PlayerQueueStateStore` 统一发布。
    开始播放新歌单、切换本地随机播放和加载持久化会话分别使用 `startPlayback`、
    `setLocalShuffle` 和 `restoreSession`，在同一事务内更新队列与恢复信息。
    远端整队更新使用 `publishCurrentQueue`；移动、删除和插入使用 `updateCurrentQueue`。
    这些操作在锁内基于最新快照计算，播放器与磁盘操作放在锁外。
    异步歌曲元数据写回使用 `updateQueuedSong`，按歌曲身份更新最新队列，保留当前选曲。
    UI 重排只提供顺序，歌曲内容取自最新队列；重复歌曲无法准确对应时拒绝旧请求并刷新界面。
    恢复洗牌前顺序时若歌曲集合已改变或副本对应存在歧义，保留当前队列。
    清空队列同时清除恢复顺序；持久化读取一次会话快照，避免混用不同版本的随机播放状态。
    当前歌曲 Flow 和 Media3 副作用的线程边界仍需单独检查。
    `session/PlayerQueueSessionBindings` 适配新引擎初始化、本地/远端播放请求和持久化恢复。
    队列计算包的直接 JVM 依赖由 `verifyDomainDependencies` 检查，禁止引用该宿主适配器。
  - `:playback:logic` 的 `runtime/persistence/PlaybackStatePersistenceCoordinator.kt` 统一管理保存请求与延迟任务。
    在同步事件入口调用 `prepareStatePersist` 或 `scheduleStatePersist`，先捕获完整快照并签发请求，
    再等待统计落盘或其它异步工作；写入串行执行，排队期间被替代的请求不再写入。
    `PlaybackStateWriter` 只确认实际成功的后端；JSON 回退后必须完整写回 Room，才能恢复增量保存。
    协程取消继续向上传播，释放播放器会关闭保存请求入口；存储完成回调不修改当前播放状态。
    `RestoredPlaybackState` 表达无恢复、保留暂停进度、待自动恢复三种状态，禁止独立修改恢复进度与标记。
  - URL 刷新的活动状态只由 `RefreshInFlightController` 持有。
    请求契约、副作用门控和活动任务控制位于 `:playback:logic` 的 `runtime/refresh`。
    参数相同可以复用活动任务；写回、完成和取消使用该任务的 `RefreshRequestHandle` 身份，
    避免旧任务影响参数相同的新任务。看门狗直接读取控制器，不另存“正在刷新”标记。
    任务完成清理要覆盖 lazy 协程尚未执行就被取消的情况。
    播放意图通过 `updateResumePlaybackRequested` 修改并撤销冲突刷新；音质刷新在主线程内
    同步采集当前状态和创建请求，避免捕获旧意图后跨线程读取新代次。
  - `timer/SleepTimerManager.kt`：睡眠定时器。
  - `engine/datasource/ConditionalHttpDataSourceFactory.kt`：为特定域名动态附加 Header。
  - `watchdog/PlayerManagerStartupWatchdogExtensions.kt`、
    `lifecycle/PlayerManagerLifecycleExtensions.kt`：
    播放启动看门狗、前后台健康审计、失败恢复和 USB 独占异常回退。
  - `resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`：网易云无权限、
    无可用播放结果或试听片段时的 Bilibili 播放兜底。
  - `resolver/youtube/YouTubeGoogleVideoRangeSupport.kt`、`YouTubeSeekRefreshPolicy.kt`、
    `prefetch/YouTubePrefetchRunner.kt`：YouTube Music 播放兼容策略。
  - `metadata/`：歌词、元数据、外部蓝牙歌词等播放页数据处理。
  - 播放器状态契约统一位于 `:model` 的 `playback` 包；纯播放 policy 归 `:playback:logic`，播放呈现适配与副作用由 `:playback:runtime` 和 app 承担。
    随机播放展示状态通过 `PlayerQueueDisplayState` 表达；不要把乱序显示队列退回
    只靠索引重映射的隐式语义。
  - `usb/`：按 `device/`、`path/`、`session/`、`sink/`、`system/` 与
    `transport/` 拆分 USB 独占会话、Native 桥、运行态快照和恢复控制，
    当前实现覆盖 **UAC1.0** 和兼容 **UAC2.0 Type I PCM** 设备，
    并已经包含 32-bit PCM、PCM float 软件转换、UAC2 显式反馈、
    协调式 AudioSink 重配置、动态传输缩放和背压卡顿恢复。

- `modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt` 维护全局下载任务与本地已下载列表。
  - `ManagedDownloadStorage.kt` 是应用目录/SAF 目录的外观入口；具体实现已拆到
    `storage/commit/`、`delete/`、`lookup/`、`migration/`、`recovery/`、
    `snapshot/`、`tree/` 与 `working/` 等子包。
  - `task/DownloadTaskStore.kt` 管理内存中的任务展示、状态、进度和 attemptId。
  - `execution/persistence/DownloadExecutionRoomStore.kt` 通过 Room 持久化下载操作和恢复状态。
  - `policy/DownloadLifecyclePolicies.kt` 集中封装下载恢复、取消清理和快速结算策略。
  - `naming/ManagedDownloadNaming.kt` 管理下载文件名模板和历史命名兼容。
  - `metadata/DownloadedAudioTagWriter.kt` 写入音频标签；`catalog/` 管理已下载歌曲目录的读写与投影。

- `app/src/main/java/moe/ouom/neriplayer/core/startup/`
  - 启动阶段与决策已按 `app/`、`crash/`、`download/`、`logging/`、
    `permission/`、`player/`、`safemode/`、`sync/` 和 `theme/` 拆分；
    `MainActivity` 只负责协调这些组件与 UI 生命周期。

- `modules/local/src/main/java/moe/ouom/neriplayer/data/`
  - `identity/`：歌曲身份转换；`SongIdentity` 与 `SongItem` 属于 `modules/model`。
  - `settings/`：`DataStore` 设置、KSP schema 和偏好映射；快照契约位于 `:model` 的 `settings` 包。
  - `auth/`：通用 Web 登录状态与 YouTube 凭据轮换 Worker；平台 Cookie / Auth 仓库位于 `:platform` 的 `data/auth` 和 `platform/youtube/auth`。
  - 平台缓存归 `:platform`，本地歌单编排和共享缓存表 schema 分别归 `:local` 与 `:database`。
  - `storage/`：存储占用分析、缓存分组和额外缓存清理。
  - `local/playlist/`：本地歌单 Room 读写、旧 JSON 升级与原子回退、系统歌单兼容、
    后台元信息补全和本地艺术家聚合。
  - `local/audioimport/`、`local/media/`：本地音频导入、快速扫描、
    后台元信息补全、封面回退和分享。
  - `playlist/favorite/`、`playlist/usage/`：收藏歌单、收藏艺术家和首页继续播放数据。
  - `history/`、`stats/`：最近播放、播放统计和日/周/月/年/总计周期聚合。
  - `backup/`：本地歌单 JSON 备份、导入与差异分析。
  - `config/`：完整配置导入/导出。
  - `sync/host/`：Android 仓库快照、资源解析与落库适配；`AndroidSyncMergeHost` 提供系统歌单身份与文案，会话和合并组件通过接口调用这些实现。
  - `sync/cover/`：封面映射持久化与旧 JSON 导入；导入失败时保留旧数据，阻止后续清理。
  - `sync/github/`、`sync/webdav/`：提供者后端与兼容 Worker 入口；`sync/work/` 维护 WorkManager、网络验证和通知适配。
  - app 不保留 `data/sync` 生产源码。

- `modules/sync/src/main/java/moe/ouom/neriplayer/`
  - `api/sync/`：GitHub/WebDAV 传输、响应读取和条件写入。
  - `data/sync/store/`：加密凭据、偏好、设备身份、因果计数器和删除状态。
  - `data/sync/merge/`：按 `engine`、`host`、`playlist`、`song`、`history`、`stats` 维护合并入口、宿主契约、冲突、排序和统计规则。
  - `data/sync/runtime/`、`codec/`、`sanitize/`、`change/`、`mapping/stats/`、`remote/`、`retry/` 和 `schedule/`：会话、兼容编解码、清洗、变化检测、统计映射、远端保护、重试与 Worker 策略。
  - 同步载荷和冲突模型位于 `:model` 的同步包。`verifyDomainDependencies` 保持传输与计算域各自的白名单，禁止直接引用 Android 仓库或播放器。

- `modules/listentogether/src/main/java/moe/ouom/neriplayer/api/ltw/`
  - HTTP、WebSocket、服务器地址校验和重连策略，依赖协议模型与注入的 HTTP 客户端。

- `modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/`
  - `ListenTogetherSessionManager` 组装会话组件；`playback/` 维护队列、权威播放候选和进度同步。
  - `control/`、`session/`、`invite/`、`mapping/`、`validation/` 分别维护控制、会话、邀请、歌曲映射和输入边界。
  - 房间、事件与传输模型位于 `modules/model` 的 `ltw` 包；`modules/listentogether` 内的 `api/ltw` 维护传输，`listentogether/protocol` 维护有界读取与消息编解码。
  - app 的 `core/di/ltw` 提供平台接口，播放器的 `core/player/ltw` 提供播放器和歌曲映射接口；`app/listentogether` 不得保留生产源码。

- `modules/native/src/main/cpp/`
  - Native 崩溃处理位于 `crash/`；USB 实现按 `usb/exclusive/`、
    `usb/feedback/`、`usb/iso/`、`usb/pcm/`、`usb/uac1/`、`usb/uac2/`
    拆分，对应 host 测试位于 `tests/usb/`；第三方 libusb 和 Android 配置位于
    `third_party/libusb/`。CMake 目标按职责链接，`lib_neri.so` 由 `:native` AAR 提供。

- `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`
  - 词幕适配（Lyricon Provider）与 SuperLyric 输出，同步歌曲、播放状态、进度、逐字歌词和翻译。
  - 同库 `lyrics/output/` 维护输出控制器、异步请求代次、取消与偏移快照；位置锚点和协调器保持内部可见。

- `app/src/main/java/moe/ouom/neriplayer/navigation/`
  - `LauncherShortcuts.kt` 负责桌面图标快捷方式到导航/播放请求的映射。

- `app/src/main/java/moe/ouom/neriplayer/widget/`
  - 桌面播放小组件的 provider、状态快照、封面取色视觉和 RemoteViews 更新逻辑。

---

### 当前能力边界 / Current Boundaries

- `Explore` 是网易精选歌单 + YouTube Music 歌单 + 网易/Bilibili/YouTube Music
  按平台独立搜索，不是混合聚合搜索。
- `Home` 在中文默认模式下展示本地继续播放、网易云全部可用推荐源和雷达歌单；
  国际化模式下优先展示 YouTube Music 首页货架。
- `Library` 中 QQ 音乐入口仍为占位，不代表完整平台接入。
- 本地艺术家分类来自本地已导入/已保存歌曲的展示艺术家聚合，
  不是在线艺术家资料库。
- 网易云艺术家详情依赖网易云 artist 元数据和接口；
  网易云、B 站和 YouTube 创作者的关注状态保存在本地收藏，按平台分类。
  网易云和 YouTube Music 远端关注由用户手动拉取，完整分页成功且账号未变化时才批量合并；
  合并保留已有收藏和排序，本次加载期间取消的关注不会被恢复，不向平台回写关注。
- `Bilibili` 已支持搜索、收藏夹和音频播放/下载，以及评论阅读、楼中楼、复制和登录后的点赞、发评与回复。
  链接识别支持选中分 P、合集分享和 `season_id` 上下文，但仍不代表完整 B 站客户端。
- `YouTube Music` 已支持登录、匿名播放、首页/歌单浏览、详情、搜索和播放兼容；
  有效身份 Cookie 会保留并支持轮换，播放解析会复用 bootstrap/player.js/PoToken 与挑战
  结果缓存，签名或播放候选失败时可回退 EJS/HLS，
  不能把缓存命中或本地单测当作真实账号/网络稳定性证明；长音频大幅 Seek 会走
  更短的启动恢复窗口，仍需验证真实网络下的体验。
- 状态栏歌词依赖厂商私有能力，当前仅适用于部分支持设备。
- `RuntimeShader` 流体/音频响应背景只在 Android 13+ 启用；封面模糊与高级模糊
  只在 Android 12+ 启用，修改动效时必须保留低版本降级路径。
- 歌词音译显示依赖平台返回的音译歌词或内嵌逐字歌词的 phonetic 字段；
  当前没有音译数据时，不应强行合成或展示空的第二行。
- 歌词分享会通过 `FileProvider` 分享缓存目录中的歌词卡片文件；
  这类分享产物属于可清理缓存，不是用户下载内容。
- Lyricon/SuperLyric 的位置 feed 独立按 200 ms 推送并使用 elapsed realtime 锚点；
  修改播放器进度刷新间隔时，必须同时检查前后台歌词时序和 SuperLyric 对齐。
- 网易云播放会在当前音质不可用时自动尝试更低音质；
  Bilibili 和本地音频兜底默认关闭，只有用户在引导或设置中主动开启后，才会在无权限、无可用播放结果或仅返回试听片段时尝试。
- 网易云歌单详情缓存只服务歌单详情页快速展示和失败回退；
  专辑详情仍保持实时刷新，避免和歌单缓存混用。
- 本地「我喜欢的音乐」支持将可识别的网易云歌曲同步到网易云我喜欢的音乐；
  该能力依赖网易云登录态，并会跳过不支持或已存在的歌曲。
- 下载使用共享 `OkHttpClient` 写入应用目录或 SAF 目录，
  **不是**系统 `DownloadManager`；当前已支持自动断点续传与启动恢复。
- 下载任务队列、取消记录和 attemptId 都参与恢复判断；
  修改恢复流程时要避免旧请求把新请求的任务状态清掉。
- 续传按传输类型分别处理：
  - 直接 HTTP 传输通过工作文件大小 + `Range` 头续传
  - 需要显式 Range 的平台传输按字节偏移续传
  - HLS 下载通过 `.hls.json` 检查点按 segment 恢复
- 工作文件位于 `files/download_staging/`，并额外保存 `.resume.json`
  恢复元数据；应用启动和网络恢复后会尝试自动找回未完成下载。
- 手动取消会回滚半成品并删除工作文件；只有网络策略暂停与可恢复错误重试
  才会保留断点。
- 音频本体完整后，标签或 SAF 可写句柄失败也按无标签完成并保留已落盘音频，
  不应为标签重试而删除音频。
- 应用私有下载目录通常比 SAF 自定义目录更快；
  SAF 快照和索引用于减少遍历；空目录扫描不能覆盖已有索引，且不能假设 SAF 操作成本
  和普通文件系统一致。
- 分享受控目录中的本地音频时优先直接暴露可读 URI；无法直接分享的 content URI
  才复制到缓存 staging，分享暂存属于可清理缓存。
- 普通缓存清理只能删除可再生成缓存和分享暂存；下载工作文件与续传凭据由下载恢复管理。
  不要通过“清缓存”删除这些恢复文件或用户主动下载的音频、歌词和封面。
- 流媒体缓存与下载是两套能力：
  缓存使用 `SimpleCache`，下载由 `AudioDownloadManager` 与
  `ManagedDownloadStorage` 写入本地文件。
- GitHub / WebDAV 同步只同步元数据，不同步音频缓存、下载文件、
  本地音频文件、Cookie 或播放 Token。
- 本地歌单与同步歌单通过 `songOrderVersion` 区分顺序语义：`0` 是旧版顺序，
  `1` 是当前展示顺序；读取旧数据时要兼容迁移，不能直接按新版顺序解释。
- 同步快照可能来自旧版 JSON/ProtoBuf 或异常远端文件；读取时要用安全默认值，
  过滤缺少可解析歌曲身份、无有效删除时间或无效歌单 ID 的记录，
  缺失 `addedAt` 的歌曲不能排到已有时间歌曲之前。
- V4 写入 Protobuf 记录流、ZSTD 内容分块和根清单，每个压缩对象不超过 2 MiB。
  清单不存在时才读取旧 `backup.json`、`backup-raw.bin` GZIP 与 `backup.bin` Base64；
  8/12/16 MiB 安全限制仅适用于旧格式读取。V3 仅供读取迁移，未知未来版本须明确拒绝；
  所有同步设备须支持 V4，传统数据或 V3 迁移须先确认升级。
- GitHub 通过 Git Data API 的 blob/tree/commit 写入仓库，以 GraphQL `updateRefs`
  的 `beforeOid` 和 `force=false` 原子发布清单。blob 请求的 Base64 只是传输封装，
  仓库正文仍为原始二进制；读取固定分支头并使用 raw 内容。
- WebDAV 使用 `If-None-Match: *` 创建不可变对象和首次清单；后续清单必须使用强 ETag
  的 `If-Match`。缺少强条件时停止发布，不能回退无条件写入。
- 播放统计与流量统计采用延迟批量写入；播放统计在播放器/Activity 关键生命周期
  flush，流量累积器在请求或下载尝试结束时 flush，播放累计与每日桶不再按数量淘汰。
  同步合并需要同时维护全量累计、每日桶和旧版 bucket-only 载荷提升，不能先裁剪窗口再抬升。
- 平台 Cookie / 鉴权信息、GitHub Token、WebDAV 密码使用
  `Android Keystore + EncryptedSharedPreferences` 加密保存。
- `DataStore` 只承担常规设置与非敏感状态，不承载平台登录凭据。
- 长音频进度记忆只针对不少于 15 分钟的内容；小于 5 秒的位置不保存，
  距离结尾 30 秒以内清零，显式播放位置优先，持久化字段为 `resumePositionMs`。
  这不是第三方平台播放历史。
- BilibiliSponsorBlock 默认关闭，只发送当前 BV 号的 SHA-256 前缀并在本地执行跳转；
  一起听期间保持关闭，不能把公开接口结果写入同步或房间状态。
- 32-bit 高解析系统输出会优先保留普通系统输出的高精度管线，并旁路响度均衡、
  声道平衡、音频可视化和应用内倍速等处理；改动时必须同时确认设置文案和测试。
- USB 独占依赖兼容 **UAC1.0** 或 **UAC2.0 Type I PCM** 的 DAC、
  前台服务、唤醒锁和系统后台策略；
  设置页的后台权限提示不是装饰，改动相关逻辑时要同时考虑息屏场景。
- USB 设置还包含比特完美音量模式；启用后软件增益保持 0 dB，音量由 DAC 硬件控制，
  不能把它与普通系统音量或应用内响度处理混为一谈。
- USB 设备插入响应是独立设置；关闭时 Activity alias 和播放服务广播入口都必须跳过
  `USB_DEVICE_ATTACHED`，不能只隐藏设置项或只改其中一个入口。
- 前后台 USB runtime report 若返回 `native_refresh_deferred`，播放器只在有限次数内
  延迟重试；其他无效报告仍按 fail-closed 处理。
- 本地扫描结果可能先用快速元数据返回，再由后台任务补全歌曲名、歌手、
  专辑和封面；不要假设首次扫描结果已经是最终形态。
- 一起听在 `shareAudioLinks=false` 时，房间快照和队列不应暴露 `streamUrl`；
  关闭该开关时还要立即清空已缓存会话候选，`REQUEST_LINK` 也应直接拒绝。
  `streamUrl` 是历史协议字段名，不表示 Worker 可作为媒体代理或公共分发入口。
- 一起听循环/随机模式通过 `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE` 同步；
  成员控制必须校验目标 stable track key，过滤 `clientInstanceId`、`clientSequence`、
  `clientTimeMs` 之前的事件，且 `REQUEST_SET_TRACK` 只能选当前队列曲目。
- 一起听当前曲目最多保留 3 条去重 HTTP(S) 候选；听众先按本机音质策略解析，
  候选只作为本次会话的失败回退，不能写入普通歌曲或离线缓存。服务端位置按曲目
  时长推算，单曲循环按时长取模；同一成员凭成员密钥重连不应触发新成员自动暂停。

---

### 常见扩展路径 / Extension Paths

#### 1. 新增 Explore 搜索源

适用于把新平台接到 `Explore` 页搜索或发现流。

1. 在 `:platform` 的 `platform/<平台>/api` 包实现客户端，缓存和业务编排放在同库对应平台职责包中，并维护协议与业务之间的包级依赖规则。
2. 在 `ExploreViewModel` 中增加请求、分页和状态映射。
3. 在 `ExploreScreen` / Host 页面中补充平台标签和结果 UI。
4. 如需播放，继续接入 `PlayerManager` 的音源解析链路。
5. 如需下载，补齐 `AudioDownloadManager` 和下载元数据映射。

#### 2. 新增播放页元数据补全源

适用于补封面、歌词、曲目信息，而不是扩展 `Explore` 页。

1. 在 `:platform` 的 `platform/search/api` 包实现 `SearchApi` 接口，共享音乐 DTO 保留在 `:model`。
2. 在 `AppContainer` 中注册单例。
3. 在 `AppContainer.searchManager` 的 provider 中登记路由；匹配和降级规则在 `:platform` 的 `platform/lyrics` 包维护并测试。
4. 视需要补充 `MusicPlatform`、字符串资源和调试探针。

#### 3. 新增在线播放平台

1. 参考 `:platform` 中的 `platform/bilibili/` 或 `platform/youtube/` 设计客户端与播放仓库。
2. 如需特殊 Header，扩展
   `core/player/engine/datasource/ConditionalHttpDataSourceFactory.kt`。
3. 在 `core/player/url/` 与对应 `resolver/` 的 URL 解析链路接入平台。
4. 下载、歌词、封面和播放统计要保持边界清晰，
   不要把缓存和永久下载混成一套实现。
5. 如需支持同步到网易云我喜欢的音乐，必须提供稳定的网易云歌曲 ID
   或可验证映射，并复用 `LocalPlaylistRepository` 的候选校验逻辑。

#### 4. 修改网易云播放兜底

1. 入口在 `core/player/url/PlayerManagerUrlExtensions.kt` 的网易云 URL 解析流程。
2. 匹配与打分逻辑在
   `core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`。
3. 播放兜底只处理网易云无权限、无可用播放结果或试听片段；
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
   下载目录的超时探测和 Provider 失败分类集中在
   `SettingsDownloadDirectoryPreflight.kt`，设置页只消费探测结果。
   设置页切换和搜索入口由 `SettingsNavigationSearch.kt` 持有。
   主题模式和调色选项由 `SettingsThemeControls.kt` 持有，包括切换请求和动画起点的决策。
   播放控件位置和尺寸的对话框状态与偏好变更决策由 `SettingsPlaybackControlLayout.kt` 持有。
   个性化和歌词外观卡片由 `SettingsPersonalizationContent.kt` 按卡片独立订阅设置流。
6. 新增或改名设置时，同步补齐中英文字符串、`SettingsSearchIndex.kt`
   搜索关键词、设置页可见性/过滤测试和 `AutoSettingsGeneratedTest`。
7. 设置控制 Activity alias、播放服务、系统入口或启动前行为时，必须同时验证
   生成 key、手写 setter、启动 snapshot 和实际入口是否使用同一份偏好。
8. 新增复杂偏好模型时，优先把归一化、边界裁剪和布局计算拆成可单测函数，
   不要只在 Compose 组件内隐式处理。
9. 探索页搜索历史由 `ExploreSearchHistoryRepository` 保存；
   `explore_search_history_enabled` 关闭时，探索页必须隐藏历史并停止新增记录，
   不要在开关切换时静默删除已有记录。
10. 歌词字号现在分成封面页和歌词页两组，且歌词和翻译各自独立；
   修改相关 UI 时同时更新 `SettingsRepository.lyricFontScalesFlow`、
   `setLyricFontScale(target, scale)` 和对应的预览/播放调用点。

#### 6. 修改 USB 独占播放

1. 先阅读 `core/player/usb/sink/UsbExclusiveAudioSink.kt`、
   `core/player/usb/transport/`、`core/player/usb/session/`、
   `core/player/policy/usb/UsbAudioSinkReconfigurationCoordinator.kt`、
   `core/player/watchdog/PlayerManagerStartupWatchdogExtensions.kt`、
   `core/player/lifecycle/PlayerManagerLifecycleExtensions.kt` 和相关测试。
2. 当前 USB 独占实现覆盖 **UAC1.0** 和兼容 **UAC2.0 Type I PCM** 设备；
   如要扩到更复杂的 UAC2.0 拓扑或非 Type I PCM 设备，需要把文档、能力边界、
   诊断和兼容性假设一起更新。
3. 同时考虑设备选择、采样率/位深策略、32-bit PCM、PCM float 软件转换、
   比特完美音量、UAC2 时钟拓扑、显式反馈端点、前后台缓冲区、唤醒锁、
   后台权限提示和系统回退链路；隐式反馈目前仍不是可用候选。
4. 修改自动恢复、keep-alive 或后台审计时，要验证前台播放、息屏后台、
   USB 拔插和 system fallback 四条路径。
5. 改动反馈时钟、长调度间隙重捕获、协调式重配置、动态传输缩放、
   背压恢复或候选位深回退时，要同步检查
   `UsbExclusiveOutputFormatResolverTest`、`UsbExclusivePcmWritePlannerTest`、
   `UsbExclusiveSessionControllerReusePolicyTest`、
   `UsbAudioSinkReconfigurationCoordinatorTest` 和 native USB feedback/PCM/UAC 测试。
6. Runtime Report v2 字段必须保持 fail-closed 解析；修改反馈端点、状态、
   holdover、恢复 action 或代次字段时，要同步更新 Kotlin parser 和边界测试。
7. 错误语义或恢复策略变化时，要同步更新设置页 / Debug 页诊断展示和对应测试。
8. 如改动 USB 设备插入响应，必须同时检查 `UsbDeviceAttachHandling.kt`、
   `AudioPlayerService.kt`、`AndroidManifest.xml` 中的 Activity alias 和
   `AutoSettingsSchema.kt` 设置生成链路。
9. Native 变更至少运行三组 host gate 和四 ABI Android 编译；
   host 模型、ABI 编译和真实 DAC 验证是三个不同的通过条件。

#### 7. 修改 GitHub / WebDAV 同步

1. 先理解 `data/model/sync/SyncDataModels.kt` 与
   `:sync` 中 `data/sync/codec/SyncDataSerializer.kt` 的兼容策略；共享载荷模型不得
   重新放回 GitHub provider 包。
2. 同步对象包含歌单、收藏歌单、最近播放、删除记录、播放统计和独立歌词修改记录。
   V4 使用 Protobuf 记录流、ZSTD 内容分块与根清单，发布点保留 `neriplayer-sync-v3.manifest` 文件名。
   V3 仅供读取迁移；仅清单不存在时读取旧 JSON、原始 GZIP 与 Base64。GitHub Git Data API 的 blob 请求使用 Base64 传输封装，
   仓库内仍保存原始正文。歌词迁移完整保留旧数据，不再提供有损选择，详见 `modules/sync/LYRIC_SYNC.md`。
3. `songOrderVersion=0` 表示旧版顺序，`songOrderVersion=1` 表示当前展示顺序；
   序列化、合并和落回本地歌单时必须保留旧数据迁移。
4. 歌单成员使用 `syncMembershipTokens` / `removedMembershipTokens` 表达
   observed-remove 语义；新增字段必须兼容旧 JSON 与 ProtoBuf 的缺字段载荷，
   带 token 的成员不能退回只比较 `addedAt/deletedAt` 的删除裁决。
   删除后撤销、备份恢复和跨设备同步要一起验证，避免旧删除记录再次移除已恢复成员。
5. 缺字段或畸形快照必须先清洗再合并；`SyncSong` 至少要有 id、audioId 或 mediaUri
   之一，删除记录还需要有效删除时间，缺失 `addedAt` 的歌曲只能作为低优先级展示项。
6. `CoverUrlMapper.kt` 位于 provider 无关的 `data/sync/`；
   GitHub 与 WebDAV 的首次上传和双端合并统一调用 `data/sync/merge/engine/SyncDataMerger.kt`。
   业务规则在共享组件中维护，宿主负责资源文案与系统歌单解析；`SyncSession` 统一
   执行冲突重试、本地 mutation version 校验和落库确认，后端提供传输与远端版本接口。
7. 不要破坏 `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt` 的延迟同步、
   周期同步、validated network 检查和失败重试行为。GitHub 清单发布必须按固定远端
   分支头做 `beforeOid` 原子非强制更新，冲突时失败；WebDAV 后续清单写入必须使用
   强 ETag 的 `If-Match`，不支持安全条件写入时停止发布。
8. 涉及敏感信息时统一走 `SecureTokenStorage.kt` 或 `WebDavStorage.kt`，
   不要放回 `DataStore` 或明文 JSON。
9. 存储文件名、键名、Worker 类全名、任务名与输入键属于升级兼容边界，迁移模块时必须保持。
   设备 ID 的读取和首次创建共享同一把锁，因果计数器与删除状态保留原子提交。
   新的同步文件自动进入整目录 CRAP 门禁，任意方法分数大于 9 即失败。

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :local:verifyCrap :local:lintDebug
```

#### 8. 修改下载存储

1. 先阅读 `ManagedDownloadStorage.kt`、`naming/ManagedDownloadNaming.kt`、
   `task/DownloadTaskStore.kt`、`policy/DownloadLifecyclePolicies.kt`
   和相关单元测试。
2. 同时考虑默认应用目录、SAF 自定义目录、迁移、历史命名、元数据文件和 `.nomedia`。
3. 下载任务先写入 `files/download_staging/`，再提交到正式目录；
   `.resume.json` 与 `.hls.json` 是续传恢复的一部分，不能当普通临时文件随意清理。
4. 默认下载并发是 **6**，设置允许调整到 **1-8**；
   修改并发、重试或网络恢复时，请同步检查 `DownloadParallelism.kt`、
   `AudioDownloadManager.kt` 和 `GlobalDownloadManager.kt`。
5. 修改目录迁移、删除语义、续传检查点或 sidecar 写入时，
   必须补充/更新对应单元测试。

#### 9. 修改歌词显示、分享或音译

1. 播放页歌词主要在 `ui/component/lyrics/AdvancedLyricsView.kt`、
   `ui/component/lyrics/SyncedLyricsView.kt`
   和 `NowPlayingScreen.kt`。
2. 全屏歌词页在 `LyricsScreen.kt`，歌词分享入口复用 `LyricShareSheet.kt`。
3. 音译显示通过 `lyric_translation_use_phonetic` 设置控制，
   需要先开启翻译且当前歌词存在音译数据。
4. 日语歌词翻译间距需要区分假名和普通 CJK 文本；修改时同时检查
   app 侧 `resolveLyricTranslationExtraGap` 与 `:accompanist-lyrics-ui`
   子模块的 `resolveJapaneseLyricTranslationTopPadding`。
5. 长按歌词用于打开分享面板；修改手势时要同时检查点击跳转、
   手动歌词偏移和高级歌词视口滚动。
6. 歌词卡片通过 `FileProvider` 分享缓存文件；
   修改输出位置时要同步检查 `file_paths.xml` 和缓存清理。

#### 10. 修改存储占用与缓存清理

1. 入口在 `data/local/storage/StorageUsageAnalyzer.kt`
   和 `SettingsStorageCacheSection.kt`。
2. 新增缓存目录时，要决定它属于可清理缓存、下载内容、诊断文件还是应用数据。
3. 清理操作只能覆盖可再生成内容；
   下载歌曲、下载歌词、下载索引和授权数据不能被普通清缓存误删。
4. 下载暂存包含工作文件、续传凭据和 HLS 检查点，普通缓存清理必须保留；
   相关文件由下载恢复流程处理，不能根据 UI 的任务状态快照决定整目录删除。

#### 11. 修改网易云歌单详情缓存

1. 缓存入口是 `NeteasePlaylistCacheRepository.kt`，
   页面状态在 `NeteaseCollectionDetailViewModel.kt`。
2. 缓存签名基于曲目数量和最近曲目 ID，主要用于判断是否复用曲目列表。
3. 网络失败或解析失败可以回退缓存，但手动刷新应保留强制刷新语义。
4. 专辑详情不使用这套歌单缓存，避免不同数据模型互相污染。

#### 12. 修改词幕适配

1. 词幕 SDK 接入位于 `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`，输出控制器位于同库 `lyrics/output/`。播放器只提供歌词、偏好、进度与生命周期快照，不直接管理 SDK。
2. 开关状态由设置项 `lyricon_enabled` 控制，并由播放器生命周期同步。
3. 歌词数据使用 `LyricEntry`，逐字信息来自 `WordTiming`；
   翻译行按时间容差匹配到原文行。
4. Lyricon/SuperLyric 位置 feed 独立按 200 ms 推送，使用 elapsed realtime
   做死区重算；修改进度节奏时，必须保持前后台时序一致。
5. 修改时要保持 Lyricon、SuperLyric、状态栏歌词、播放页高级歌词
   和外部蓝牙歌词的歌词结构兼容。
6. 蓝牙歌词的原文与翻译是独立开关；同时输出时必须通过同一个原子快照更新
   标题/艺术家字段，并保留曲目信息、字段长度限制、空白清洗和重复提交去重测试。
7. `bluetooth_metadata_mode` 只改变实际蓝牙输出时的展示，未连接蓝牙时保留灵动岛行为。
   车载浏览入口位于播放运行库的 `service/car`，通过私有服务 Binder 复用现有 framework 会话；
   浏览不提升前台、不自动恢复播放，明确起播时才启动私有播放服务。
   浏览 ID 必须在当前曲库重新验证，封面使用受限只读 Provider 和有界 JPEG 缓存，
   不允许客户端传入任意文件或远程地址。Compat 仅用于提供现有会话的浏览搜索桥接。

#### 13. 修改一起听

1. 客户端会话、HTTP/WebSocket 传输、编解码与有界读取统一位于 `modules/listentogether`，分别由既有 `data/ltw`、`api/ltw` 和 `listentogether/protocol` 包维护；协议模型在 `modules/model`。app 的 `core/di/ltw` 只绑定平台能力，播放器适配在 `modules/playback/runtime` 的 `core/player/ltw`。
2. 服务端逻辑在 `np-submodule/NeriPlayer-LTW`。
3. 协议字段变更必须同时兼容客户端和 Worker，并更新测试。
4. `shareAudioLinks=false` 时，HTTP/WS 房间快照都不能暴露
   `track.streamUrl` 与 `queue[*].streamUrl`；关闭该开关时要立即清空
   房间里已缓存的会话候选。
5. `REQUEST_LINK` / `LINK_READY`、成员控制、房主离线恢复和版本门控更新
   要一起看，避免旧状态覆盖新状态。
6. 循环/随机模式使用 `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE`；成员请求与
   `LINK_READY` 都要校验目标 stable track key，避免异步结果落到错误歌曲。
7. 首次加入需要 `joinSecret`，已有成员重连需要 `memberSecret`；这些密钥不得写入
   脱敏房间状态或日志，邀请 URI 的 `secret` 参数也要按敏感输入处理。
8. 控制事件可携带 `clientInstanceId`、`clientSequence` 和 `clientTimeMs`；Worker
   会拒绝过期顺序，`REQUEST_SET_TRACK` 只能选择当前队列已有曲目，客户端兼容路径
   也必须保持一致。
9. 成员显式离开时调用 `/api/rooms/:roomId/leave`，Worker 删除成员并广播
   `MEMBER_LEFT`；普通 WebSocket 断线属于传输波动，必须保留成员凭据以支持重连；
   控制者显式离开则关闭房间。
10. 房间号 6 位、昵称 1-24、队列上限 2000 和请求去重要视为协议边界，
   不要只改 UI 校验而忘记服务端约束。
11. 设置页支持自定义服务端地址和可用性测试，不要硬编码单一地址。
12. HTTP 控制回退必须按服务端、房间、用户和凭据校验回包；离开、断开或更换会话时取消旧任务，协程取消不能进入错误或重连流程。
13. 队列变更构建与应用必须保留重复曲目的 occurrence 身份、连续插入顺序和当前歌曲；修改后运行模块往返测试。

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
```

#### 14. 修改主导航与玻璃转场

1. 主标签生产路径由 `NeriApp.kt` 与 `MainTabLayerHost.kt` 共同承载；
   标签顺序与方向统一通过 `resolveMainTabTransitionDirection` 决定。
2. 转场期间必须同时保留出场和入场 scene，并通过
   `SaveableStateHolder` 保留各标签状态；快速反向或连续点击不能先清空旧 scene。
3. 每个 scene 都有独立 `MainTabGlassOwner`。修改 Advanced Glass 时，
   要保证只有当前可见 owner 参与合成，详情页 handoff 仍由导航层接管。
4. `coherent_feedback_enabled` 默认关闭：详情页使用抽屉式前景上升和背景轻微下沉；
   开启后才使用背景与详情页同步接力的连贯反馈。
5. 修改动效时要覆盖正向、反向、中断、重复请求、状态恢复和启动首帧；
   几何测试不得把尚未布局的 `Rect(0, 0, 0, 0)` scene 当成真实重叠。
6. 至少同步检查 `NeriAppMainTabTransitionPolicyTest`、
   `AdvancedGlassNavigationTransitionTest`、`NeriAppNavigationTransitionTest`
   和 `HostNavigationTransitionGeometryTest`。

#### 15. 修改桌面小组件、启动器快捷方式或全局反馈

1. 桌面小组件入口在 `widget/PlaybackWidgetProviders.kt`，
   状态和取色策略在 `PlaybackWidgetState.kt` 与 `PlaybackWidgetVisuals.kt`。
2. RemoteViews 布局同时有普通资源和 API 31 专用资源；视觉、裁剪或预览图变更要检查
   `layout/`、`layout-v31/`、`xml/` 与 `xml-v31/` 是否都保持一致。
3. 播放控制动作最终进入 `AudioPlayerService`，新增动作必须检查前台服务启动策略、
   媒体会话状态刷新和无歌曲/缓冲状态下的反馈。
4. 启动器快捷方式由 `navigation/LauncherShortcuts.kt` 映射；
   新增快捷方式时同步更新 `res/xml/shortcuts.xml`、中英文字符串和
   `LauncherShortcutsTest`。
5. 全局 Snackbar 优先走 `AppFeedback` / `ViewSnackbar`，不要让各页面各自持有
   无法跨覆盖层显示的 SnackbarHost。
6. 歌单批量导出、删除撤销和同步删除记录有关联；修改其中任一项时同时检查
   `PlaylistExportSheetTest`、`AppFeedbackPolicyTest`、`LocalPlaylistRepositoryTest`
   和 `SyncPlaylistDeletionPolicyTest`。

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

自动 APK 构建等待已有 JVM、lint、Worker 与模拟器作业，并在打包前运行 Native Release host 检查。手动 Release 对 `target_ref` 实际检出的源码先运行 JVM 测试、lint、可用的模块边界检查与 Native Release host 检查，再构建签名 APK；验证报告以该提交 SHA 命名。历史版本使用自身的新旧 Native 测试入口，存在测试设施但缺少配置时拒绝发布。额外模拟器、sanitizer 和真实 DAC 验证按相关变更风险执行，不把真实设备作为每次发布的前置条件。

提交前建议至少完成以下检查：

1. 能成功构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. 单元测试：
   ```bash
   ./gradlew :app:verifyCrap
   ```
3. 如修改登录态、播放解析链路或回归风险较高的集成行为，可按需执行 smoke test：
   ```bash
   ./gradlew :app:testDebugUnitTest -DrunNeteaseSmoke=true
   ./gradlew :platform:testDebugUnitTest \
     -DrunYouTubePlaybackSmoke=true \
     -DyoutubeSmokeVideoId=VIDEO_ID
   ```
   将 `VIDEO_ID` 替换为实际视频 ID；需要时追加 `-DyoutubeSmokeForceRefresh=true`
   或 `-DyoutubeSmokeCookieFile=/absolute/path/to/cookies.json`。
4. 如修改资源、UI、导航、设置、同步或存储逻辑，建议执行：
   ```bash
   ./gradlew :app:lintDebug
   ```
5. 如涉及 Compose UI、权限、Activity 或登录流程，建议在设备/模拟器上执行：
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
6. 如修改 Native USB，实现需对齐独立 Android Native CI：
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
   host gate 会固定执行同一组 CTest；Android 编译还要确认
   `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 均生成非空 `lib_neri.so`。
7. 如修改一起听 Worker：
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
   这里的 `npm run check` 会依次执行 Node.js 版本检查、`node --check`、协议测试和
   `wrangler deploy --dry-run`；协议或房间状态改动还需要实际验证 create/join/ws 流程。
8. 新增单元测试放到被测代码所在模块的 `src/test/`，宿主集成测试放在 `app/src/test/`；
   新增设备或 Compose UI 测试放到对应模块的 `src/androidTest/`，应用宿主集成测试放到 `app/src/androidTest/`。
9. 行为变更涉及 README、设置文案、用户流程或同步格式时，请同步更新文档。

CRAP 质量门禁与职责拆分：

```bash
./gradlew verifyModularization
```

此任务执行模块边界检查、app 与自有库的 lint 和 JVM 测试，合并 JaCoCo 覆盖率，
输出可映射到 app 与库主源码的全部 JVM 方法分数，
并单独列出 CRAP > 8 的条目。`config/quality/crap-scope.json` 的 `source_patterns`
覆盖完整源文件，`method_scopes` 覆盖原入口中受本次拆分影响的方法；
范围内任意方法 CRAP > 9 即失败，范围外的方法仍保留在完整报告中。
分数使用 JaCoCo 复杂度覆盖率近似路径覆盖率；不衡量 Native 代码，也不能代替耦合度审查。
`:app:check` 和 Android CI 均执行门禁；完整评分和范围报告位于
`app/build/reports/crap/`，口径与依赖见 [质量检查说明](tools_pub/quality/README.md)。

`:playback:logic` 和 `:playback:runtime` 通过 `build-logic.android.module-quality` 提供 `verifyCrap`。
独立门禁使用模块自己的覆盖率和共享配置中的对应范围，所选范围为空时失败；
播放策略、运行协调、PCM、宿主接口、USB policy 和小组件呈现包自动包含新增文件。
app 的播放器适配器参与聚合门禁。迁移到运行库的歌词渲染、Room 队列、解码器和播放 Range
设备测试通过 `:playback:runtime:connectedDebugAndroidTest` 执行。

`OwnedMainSourceLineBudgetTest` 约束受检自有主源码及组件严格少于 2000 物理行，
USB `exclusive/` 下的自有 `.cpp` / `.h` 也在检查范围内。新增组件时应同步维护测试中的文件清单；
第三方 libusb 和测试文件不属于这个行数上限。

`LocalManagementLineBudgetTest` 对下载、本地数据、媒体库及相关测试执行同样的行数限制。
移动入口文件或拆分目录时，应同步更新测试中的必需文件路径和扫描范围。

拆分时应把状态、异步任务和释放逻辑交给负责该职责的组件，通过小接口接入外部能力。
`PlayerManager` 的播放器和全局服务访问集中在对应的 `PlayerManager*Port` 适配器；
一起听的成员操作、房间状态、连接恢复和控制结果由 `:listentogether` 的 `session/` 组件分别维护，平台与播放器实现通过宿主接口注入。唤醒锁随会话释放，异步任务由所属组件取消，不能回读全局容器。
`NowPlayingScreen`、`SettingsScreen` 和 `NeriApp` 组合页面与功能组件，具体编辑会话、目录选择、
设置领域绑定和导航副作用在对应组件中处理。不要把原入口作为 receiver 搬进扩展文件，
也不要让新组件回读原入口的内部状态。
`:local` 中的本地存储按 `source`、`scan`、`accounting`、`cleanup` 和 `policy` 分类，数据契约集中在 `:model` 的 `storage` 包。
存储统计由 `StorageUsageScanner` 通过数据源接口采集快照，`StorageUsagePresenter`
只读取快照和字符串资源；`StorageCacheCleaner` 通过文件和平台清理端口执行操作，Room 和全局服务访问集中在
`StorageUsageAndroid.kt`。新增组件应保持这个单向依赖，并纳入完整文件门禁。

当前已有测试覆盖的重点包括：

- YouTube 登录、Cookie 轮换、匿名会话、挑战解析、PoToken、播放解析、Range/Seek 策略与预取
  （含长音频大幅 Seek 的快速恢复）
- 网易云歌词、本地 smoke test、播放兜底和播放响应解析
- USB 独占 keep-alive、启动看门狗、前后台恢复、32-bit/float 输出、
  UAC2 显式反馈、长调度间隙重捕获、协调式重配置、Runtime Report v2、
  背压恢复、延迟 runtime 刷新重试、比特完美音量、USB 插入响应开关和音频焦点策略
- 主标签双 scene 转场、快速反向切换、抽屉/连贯详情反馈、玻璃 owner 隔离
  和未布局 scene 几何过滤
- 桌面小组件状态/取色/RemoteViews 资源、启动器快捷方式映射和全局 Snackbar 覆盖层
- 下载元数据、命名、目录迁移、快照缓存、`.nomedia`、删除语义和启动恢复
- 启动阶段、通知权限、播放服务启动、历史记录与安全模式恢复规划
- 本地扫描、元信息补全、封面回退、系统歌单去重和歌单顺序稳定性
- GitHub/WebDAV 同步序列化、缺字段快照清洗、旧歌单顺序迁移、删除策略、
  播放统计滚动窗口、全量累计合并、旧版 bucket-only 兼容、WebDAV 并发回退、
  原子文件写入和上传重试
- 长音频进度阈值、显式位置优先、BilibiliSponsorBlock 本地跳转与一起听禁用策略
- 一起听地址校验、版本门控、循环/随机模式、stable track key 目标校验、
  播放同步规划、会话候选回退、邀请/成员密钥、显式离开/重连、事件排序、Session 控制/取消与协议兼容
- 歌词视图、日语假名翻译间距、逐词时间、外部蓝牙歌词、播放音效和播放策略
- 配置备份、设置生成、安全守卫、崩溃日志文件和安全模式相关逻辑

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
- 提交贡献即表示你同意至少以 GPL-3.0 分发你的修改。
- `modules/native/src/main/cpp/README.md` 中的替代授权只覆盖明确列出的
  NeriPlayer 自有 Native 源码，不覆盖第三方代码或其他仓库内容。
- Native PR 本身不代表授予替代授权；若贡献者同意双授权，必须在 PR、
  commit 或版权持有人接受的其他可审计记录中加入该 README 提供的声明。
- 未提供显式双授权的外部 Native 贡献仍可按 GPL-3.0 接受，
  但不进入“满足署名条件即可闭源使用”的例外范围。

---

### 沟通方式 / Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues)：缺陷、功能建议、讨论
- [README.md](./README.md)：功能与使用说明
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)：社区行为准则

如你准备提交较大的结构性改动，建议先开 Issue 对齐方向。
