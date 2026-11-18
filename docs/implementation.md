# 实现概览 / Implementation Notes

本文承接自 [README.md](../README.md)，说明构建版本、模块边界与关键实现路径。

- 安装、能力边界与隐私说明：见 README。
- 扩展方式、测试与提交要求：见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 构建与版本

| 项目 | 版本 |
| :--- | :--- |
| `compileSdk` / `targetSdk` / `minSdk` | `37` / `36` / `28` |
| Java / Kotlin JVM | 17 |
| Kotlin / KSP | `2.4.10` / `2.3.10` |
| AGP / Gradle | `9.4.1` / `9.6.1` |
| NDK | `27.0.12077973` |
| CMake | `3.22.1`，由 Android 构建脚本指定 |

- 版本名格式：`<git短哈希>.<MMddHHmm>`。
- Release APK 文件名：`NeriPlayer-<versionName>[-abi].apk`。
- 默认 Release APK 只含 `arm64-v8a`，多 ABI 输出加 `-PbuildAllReleaseAbis=true`。

`.github/workflows/android_native_ci.yml` 在 Native 相关变更时运行：

- Release + `-Werror`、ASan + UBSan、TSan 三组 host CTest。
- `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四个 Android ABI 的独立编译。

## 模块结构

自有 Android 库共 14 个，按业务域组织，由 [gradle/owned-modules.txt](../gradle/owned-modules.txt) 统一登记。

- 一级领域使用 `modules/<领域>`。
- 播放和下载各拆为规则（`logic`）与运行实现（`runtime`）两个构建边界。
- 远程音乐、歌词和元数据来源集中在 `:platform`，其中 `api` 承担同库内部的请求协议职责。
- 公共能力契约由模型和宿主接口表达。

| 模块 | 职责 |
| --- | --- |
| [`:common`](../modules/common/README.md) | 共享工具、日志、文案、图标、语言能力和测试夹具。 |
| [`:network`](../modules/network/README.md) | 通用 HTTP、Range、网络解析和 Web 登录基础能力。 |
| [`:model`](../modules/model/README.md) | 按业务分类的数据模型、枚举和跨模块状态契约，不依赖项目实现。 |
| [`:database`](../modules/database/README.md) | Room 数据库、实体、DAO、历史 schema 与版本升级。 |
| [`:native`](../modules/native/README.md) | Native 崩溃处理、DSP 音效、USB 协议与 PCM 计算、独占传输和第三方 libusb，生成并打包 `lib_neri.so`。 |
| [`:playback:logic`](../modules/playback/logic/README.md) | 播放 policy、状态协调 runtime、PCM/audio 和队列 queue 规则，包级隔离。 |
| [`:playback:runtime`](../modules/playback/runtime/README.md) | Media3 引擎、播放服务、音源解析、USB 输出、Native DSP 接入、浮窗与蓝牙歌词及宿主接入。 |
| [`:download:logic`](../modules/download/logic/README.md) | 下载准入、状态迁移、重试、调度、传输槽位、所有权、提交与元数据规则。 |
| [`:download:runtime`](../modules/download/runtime/README.md) | 下载执行、Room 队列、受管文件、迁移与恢复，以及 Worker 和 JobService。 |
| [`:lyrics`](../modules/lyrics/README.md) | LRC/YRC/TTML 解析、转换、时间轴、翻译对齐、时间偏移，以及词幕（Lyricon）和 SuperLyric 接入。 |
| [`:platform`](../modules/platform/README.md) | Bilibili、网易云、YouTube 与 Subsonic 自建音乐服务器（预览版，缺乏充分测试）的协议、账号、缓存和业务能力，跨平台评论，以及歌词来源、元数据搜索、匹配与回退。 |
| [`:local`](../modules/local/README.md) | 本地设置、媒体、歌单、历史与统计、备份、流量、存储统计与缓存清理，以及 Android 同步宿主适配。 |
| [`:sync`](../modules/sync/README.md) | GitHub/WebDAV 传输、加密凭据状态、会话、兼容编解码、合并、并发保护与 Worker 策略。 |
| [`:listentogether`](../modules/listentogether/README.md) | 一起听协议、HTTP/WebSocket 传输、身份规则、会话、连接恢复、控制与播放同步。 |

非自有库的构建单元不计入这 14 个：

- `:app`：Android 入口、Compose 页面和跨领域依赖组装。
- `:ksp-annotations` / `:ksp-processor`：设置项生成。
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`：上游歌词子模块。
- `:hidden-api`：播放运行模块的编译期接口桩，不随应用打包。
- `build-logic`：构建逻辑。
- `np-submodule/NeriPlayer-LTW`：一起听 Cloudflare Workers 服务端。
- `np-submodule/miuix`：上游源码与文档树，不参与主应用构建。

### 依赖规则

依赖按实际职责维护，不由目录分类推导层级。

- `:model` 不依赖项目实现，`:common` 不依赖业务领域。
- 数据库、存储计算、歌词解析与播放/下载规则不依赖仓库或 Android 运行实现。
- `:platform` 内的业务仓库消费对应协议，API 不读取账号仓库、Room 或播放器状态，各平台保持包级依赖边界。
- `:local` 消费数据库与领域服务，账号、凭据刷新和存储能力通过窄接口注入。
- 所有库不依赖 app，也不形成循环。

`:playback:logic` 与歌词的边界如下：

- policy 只含 `audio`、`command`、`offload`、`pending`、`progress`、`service`、`skip`、`storage`、`wake` 九个叶子包，不引用 runtime、audio 或 queue 实现。
- USB policy 归 `:playback:runtime`。
- `:platform` 依赖并公开 `:lyrics` 的解析能力，`:lyrics` 不依赖平台。
- 歌词来源仓库、匹配和回退位于平台。
- 词幕 SDK、位置推送与异步请求代次由歌词库维护。
- 播放器通过窄加载端口提供歌词、偏好快照和生命周期输入。

### 契约与目录归属

- 共享业务契约放入 `modules/model`，沿用 `data.model.<业务>` 等包名。
- 页面私有状态随 UI，Room 实体随数据库，私有算法中间状态随算法。
- 物理模块目录与 Kotlin 包名表达不同层次，包名与源码内部目录一致。
- 调整类名时核对真实的保存与读取方式。
- Worker 持久任务、JNI 导出和 Android 组件入口的名称须保持兼容。
- 字段、存储键与历史 schema 不随目录整理变动。

各模块的资源归属如下：

- 测试、资源与 consumer R8 规则由各模块自行维护，宿主集成测试在 app。
- Room 业务映射与本地仓库编排位于 `:local`，平台缓存适配位于 `:platform`。
- 历史 schema 位于 `modules/database/schemas`。
- 设置 schema 在 `:local` 运行 KSP，Compose 设置渲染位于 [app 的设置页面](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/)。
- app 无生产 `data` 目录，下载升级和依赖组装位于 `core/startup/legacy` 与 `core/integration`。

### 质量门禁

`./gradlew verifyModularization` 依次检查：

- 结构与 JVM 测试。
- 合并 CRAP 覆盖率。
- 计算域依赖与 lint。

`:native` 参与登记、结构与 lint 检查。纯 Native 模块不生成 JaCoCo 数据。Native host 测试和四 ABI 编译由独立 CI 与模块任务验证，入口见 [:native README](../modules/native/README.md)。

各模块 README 列有针对性任务，例如：

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
python3 -B tools_pub/quality/module_boundaries.py
```

- 独立门禁与聚合门禁范围和阈值相同，受检方法 CRAP 大于 9 即失败。
- 库源码文件少于 2000 行，每个生产目录最多 16 个直接 Kotlin/Java 文件。
- FFmpeg AAR 由应用打包，`lib_neri.so` 由 `:native` AAR 提供并随 app 打包。
- 播放服务和 Kotlin 实现由 `:playback:runtime` 维护，app 通过 `core/di/player`、`core/di/ltw` 等接口组装。

扩展规则见 [贡献指南](../CONTRIBUTING.md#项目结构与当前实现--project-layout) 与 [质量说明](../tools_pub/quality/README.md)。

## 入口与导航

### 应用入口

- `MainActivity` 是主界面入口。
- 它处理启动流程、通知权限、外部音频导入、USB 设备插入事件和 `neriplayer://listen-together/join` 深链。
- 网页登录使用独立 Activity。
- 蓝牙媒体验证入口负责车载能力验证，`CarMediaBrowserService` 负责曲库浏览。
- 启动器快捷方式由 `LauncherShortcuts` 映射为继续播放、探索、媒体库和随机喜欢。
- 快捷方式只进入主标签或播放服务入口，沿用正常的导航状态恢复。

### Safe Mode

上次启动发生 JVM / Native 崩溃或系统 ANR 时，优先进入 `Safe Mode`，开放：

- 日志预览导出
- 配置导出
- 登录重置
- 设置重置
- 恢复正常启动

### 主导航

- 主界面是 **Compose NavHost + 动态底栏**，主路径为 `Home / Explore / Library / Settings`。
- `Home` 随首页卡片可用性显示，`Debug` 仅在开发者模式开启后显示。
- 在“首页与启动”中可关闭哔哩哔哩音乐，同时隐藏首页音乐榜单和热门视频并停止加载，该设置跨启动保留。
- `MainTabLayerHost` 为主标签保留出场与入场两个场景，各自持有可保存状态和高级玻璃 owner，快速反向切换从当前进度继续。
- 标签切换动效：平板为居中缩放加淡入淡出，手机为横向位移。
- 详情页默认背景轻微下沉、前景自下而上展开；开启“连贯反馈”后，背景与详情页同步接力移动。
- `Library` 使用分页导航，覆盖本地、收藏、音乐服务器（预览版）、网易云、YouTube Music 与 Bilibili，并提供最近播放和播放统计入口。
- YouTube Music 入口可在设置中关闭。
- 本地媒体库和收藏页带二级分类：

| 页面 | 二级分类 |
| :--- | :--- |
| 本地媒体库 | 歌单 / 歌手 |
| 收藏 | 歌单 / 歌手 / 热点 |
| 网易云 | 歌单 / 专辑（登录后含云盘入口） |

- 本地歌手详情由 `LocalArtistDetailScreen` 承载，支持播放全部、多选、导出歌单和批量下载在线歌曲。
- 网易云艺术家详情由 `NeteaseArtistDetailScreen` 承载，支持歌曲/专辑分页和关注状态。

### 播放页

- `Now Playing` 是覆盖在主导航之上的全屏播放层，不是普通路由。
- 底部常驻 `Mini Player`，支持横向滑动切歌。
- 封面保持方形。
- 打开播放页会结束底层输入焦点和文字选择菜单。
- 歌曲和歌词编辑面板按可用高度排布输入与操作区，键盘或大字体占用空间时可滚动访问全部操作。

### 平板适配

- 平板的播放页、歌词页、艺术家详情和设置页限制内容宽度，并调整底部操作区。
- 横屏主界面使用左侧导航栏，竖屏使用底部导航。
- 侧栏只显示选中项的文字，选项组垂直居中。
- 横屏隐藏系统导航栏，边缘滑动可临时唤出，竖屏恢复显示。
- 平板迷你播放器带上一首、下一首按钮，窄窗口只保留播放按钮和滑动切歌。
- 竖屏播放页限制封面和操作区宽度，给歌词留出空间。
- 竖屏完整歌词页使用居中的阅读区和紧凑控制区。
- 播放器转场由覆盖层遮挡侧栏，底层页面宽度和模糊背景保持稳定。
- 未手动设置时，主控位于底部并含进度条，歌词和翻译字号分别为手机的 125% 和 115%。
- 平板界面跟随系统旋转。
- 横屏播放页使用宽松双栏，并固定显示完整歌词分栏，不通过横滑切换歌词样式。
- 横屏音频规格信息遵循显示设置。

### 手机横屏

- 手机仅在播放页允许横屏，返回后恢复竖屏，重新打开播放页再允许旋转。
- 播放页按可用高度调整封面、歌词和控制区，使用紧凑双栏。
- 紧凑双栏隐藏辅助工具栏并缩小播放按钮。
- 播放控制与进度位于右下方，歌曲操作位于右上方。
- 平板和竖屏使用播放控制位置偏好。
- 歌词页与封面页共用布局，各自保留字体和按钮尺寸设置，左右滑动切换。
- 歌词区域透明，封面上下保留间距，隐藏音频规格信息。
- 短横屏的歌曲编辑面板使用紧凑封面和独立滚动表单，表单滚动不拖动面板。

### 引导页与平板详情

- 平板启动引导和免责声明在横竖屏下均使用说明与设置双栏，内容可滚动，操作按钮固定。
- 平板网易云歌手详情使用简介与作品双栏，关注按钮位于简介上方。
- 播放统计使用独立概览卡片和排行榜，窄窗口自动调整布局。
- 平板评论面板顶部留有间距，横屏下标题和输入区紧凑，给评论列表留出空间。

## 播放、缓存与服务

### 播放核心

- 播放核心基于 Media3 ExoPlayer，由 `PlayerManager` 统一管理。
- `AudioPlayerService` 提供前台播放服务、媒体通知、MediaSession 和基础传输控制。
- 播放器按 `playback/`、`url/`、`resolver/`、`service/`、`effects/`、`lifecycle/`、`watchdog/`、`usb/` 等职责分包。
- 共享歌曲与播放状态契约由 `:model` 维护。
- 播放状态定期持久化，进程重启后恢复队列和状态。
- 睡眠定时器、淡入淡出、切歌交叉淡入淡出、播放模式恢复由播放器层管理。
- 预抢占音频焦点、混音播放、蓝牙断连暂停和 USB 独占播放，通过播放偏好快照在播放器启动早期生效。

### Bilibili

- `ConditionalHttpDataSourceFactory` 为媒体 CDN 附加 `Referer` 和 `User-Agent`。
- Cookie 只发往受信任的 HTTPS CDN。
- 海外 COS CDN 从头请求整个音频时使用 `Range: bytes=0-`。
- Akamai 备用 CDN 使用公开请求头。
- 最后一个候选返回 HTTP 错误时，播放器只重新取流一次，直到播放进度推进或新的播放请求开始。

### YouTube Music

- 播放包含平台 Range 传输兼容、Seek 刷新策略和预取逻辑。
- 播放解析复用登录 Cookie、匿名会话、bootstrap、`player.js` 和 PoToken 缓存。
- 播放候选被拒时回退 EJS/HLS。
- 长音频的大幅 Seek 进入更短的启动恢复窗口，不等待普通播放启动看门狗超时。

### 网易云

- 无权限、无可用播放结果或试听片段时自动降级音质。
- 仍不可播时，按设置自动匹配 Bilibili 音源或本地音频。

### USB 独占

支持范围与配置：

- 设备：**UAC1.0** 与兼容 **UAC2.0 Type I PCM**。
- 可选项：设备、采样率、位深、缓冲策略、兼容性开关、32-bit PCM、PCM float 软件转换、后台缓冲区。
- Native 独占先尝试源采样率；精确格式不可用且兼容策略开启时，再尝试设备兼容采样率。
- 比特完美音量开启时，软件增益固定为 0 dB。

UAC2 异步显式反馈拓扑：

- 解析时钟链、反馈端点与报告周期。
- Runtime Report v2 暴露反馈状态、端点、速率、holdover 和长间隙重捕获计数。

播放器层的保障机制：

- 启动看门狗、前后台健康审计与 keep-alive 检查。
- 带代次协调的 AudioSink 重配置。
- 动态传输扩缩容与 Native 传输背压恢复。
- 读到 Native 延迟刷新状态时，前后台在有限次数内重试。
- 必要时回退系统播放。
- 按网络或本地来源选择唤醒策略。
- 前后台切换后恢复 USB 独占。

USB 设备插入响应由设置控制，关闭后 Activity alias 和播放服务都忽略 `USB_DEVICE_ATTACHED` 唤起路径。

## 搜索与数据来源

### UI 搜索

- `Explore` 接入网易云、Bilibili 和 YouTube Music，按平台独立搜索，不混合聚合结果。
- 网易云可切换歌曲/歌单/歌手，网易云和 Bilibili 支持触底分页。
- 链接识别支持直接链接和含短链接的分享文案。
- Bilibili 视频链接保留 `p` / `cid` 指向的分 P。
- Bilibili 合集分享优先使用视频详情里的 UGC season，必要时回退到链接里的 `season_id`。
- 搜索历史保存在本地，可在“设置 > 通用”中独立关闭显示和记录。

### 元数据与百科

- 播放页通过 `SearchManager` 使用网易云与 QQ 音乐补全封面、歌词和曲目信息。
- `NeteaseSongEncyclopediaRepository` 按网易云歌曲身份加载百科，使用限时内存缓存。
- 曲目切换时取消旧请求并校验请求代次，旧歌结果不会覆盖新歌。
- 艺人和创作团队等附加数据加载失败时，只降级对应内容。
- 本机听歌记录与在线百科分别展示。

### 歌词

歌词能力：

- 支持原文、翻译、音译、逐词歌词、歌词分享与手动编辑。
- 除平台自带歌词外，内置 LRCLIB 外部歌词客户端。

歌词编辑器的“匹配”入口：

- 可修改搜索关键字，或先选平台再搜索。
- 搜索结果按平台临时缓存，切换平台过滤直接复用缓存。
- 自动清理标题、制作信息等非歌词行。
- 综合歌名、歌手、专辑、时长和歌词类型排序，逐词歌词优先，普通歌词候选保留。

来源偏好（“设置 > 歌词设置 > 来源偏好”）：

- 默认来源可选 `自动` / `网易云` / `酷狗` / `QQ 音乐` / `LRCLIB` / `AMLL TTML`。
- “优先使用逐词歌词”可开关。
- 默认值为 `自动`，并开启优先逐词。
- 按歌名和歌手检索指定来源时校验时长，时长未知或不符则回退到自动歌词。
- 在线歌曲先展示已缓存歌词，后台再请求指定来源。
- 匹配成功后替换，未命中则保留原歌词。
- 修改偏好会立即清理已缓存结果。

其他歌词来源：

- **B 站 CC 字幕**：独立设置项，默认开启，登录哔哩哔哩账号后生效。
- **音译补全**：当前歌词源缺少音译时，从网易云补全，同时保留当前源的原文与译文。
- 来源自带的音译，以及用户手动编辑或清空的音译，优先于补全结果。

**默认歌词偏移**：“歌词偏移”卡片可分别调整 6 个来源的偏移量，支持单项重置和一键全部重置。

| 歌词来源 | 默认偏移 |
| :--- | :--- |
| 网易云 | `1000 ms` |
| QQ 音乐 | `500 ms` |
| 酷狗 / LRCLIB / AMLL TTML / B 站 CC | `0 ms` |

### 词幕适配

- `LyriconManager` 向 Lyricon 与 SuperLyric 输出当前歌曲、播放状态、进度、逐字歌词与翻译歌词。
- 进度由独立的 200 ms feed loop 推送，并以时间锚点校准。
- 翻译行按时间匹配，保留歌词卡片缓存。
- 歌曲分享可生成小米超级岛 URL。
- 状态栏歌词依赖厂商能力，面向部分支持设备。

### 网易云

- **艺术家入口**：搜索、首页、歌单/专辑详情和播放页都保留 `neteaseArtists` 元数据，用于进入艺术家详情。
- **歌单缓存**：`NeteasePlaylistCacheRepository` 缓存歌单 header、曲目、最近曲目签名和保存时间。
- `NeteaseCollectionDetailViewModel` 先发布缓存再刷新网络，曲目签名未变或网络失败时复用缓存。
- **云盘**：`NeteaseCloudViewModel` 通过 `/weapi/v1/cloud/get` 分页拉取用户云盘。
- 云盘条目优先用 `simpleSong` 生成可播放的 `SongItem`，缺省时回退文件名与条目元数据。
- 云盘歌曲的播放地址与普通网易云歌曲同样解析。

## 本地数据与安全

### 本地存储

- 常规设置使用 `DataStore` 持久化，由 KSP 生成设置 key、备份白名单和设置 UI 元数据。
- 主题模式由 `ThemeMode` 管理，支持浅色、深色和跟随系统的 Auto。
- 平台 Cookie、YouTube 授权信息、GitHub Token 与 WebDAV 密码使用 `Android Keystore + EncryptedSharedPreferences` 加密保存。
- 本地歌单、最近播放、播放统计和收藏快照主要由 `Room` 持久化，实体、DAO 与升级链由 `:database` 维护。
- 旧 JSON 提供升级导入和故障回退路径，回退文件使用原子写入。
- 歌单 JSON 导入/导出与远端同步格式由各自流程维护。
- 收藏和播放统计的首次加载在 IO 协程中完成。
- 主存读取失败时保留原数据，相关写入、备份与同步等待加载成功，并可在后续操作中重试。

### 同步架构

- GitHub 与 WebDAV 共用的同步载荷模型位于 `:model` 的 `data/model/sync/`。
- `:sync` 维护传输、加密凭据状态、会话、兼容编解码、清洗、合并和调度策略。
- Android 仓库与 WorkManager 适配位于 `:local`。
- GitHub/WebDAV 管理器通过后端接口接入共享会话。
- 删除记录与撤销操作一起进入合并策略，本地撤销的歌曲不会被旧删除记录再次移除。
- 设备标识使用本地生成的 UUID，不依赖 `ANDROID_ID`。

自动同步：

- 已配置并开启自动同步时，打开应用立即提交持久同步任务，播放期间也会执行。
- 双后端启用时，WebDAV 任务错开 10 秒。
- 自动同步开关和同步格式升级确认决定任务是否允许执行。

### GitHub 同步与 V4 归档

- 内容采用 Protobuf 记录流和 ZSTD 内容分块，单个压缩对象不超过 2 MiB。
- 根清单提交完整快照，未变更的分块直接复用，不重传。
- 仓库内保存原始二进制正文，通过 raw 端点读取固定远端提交的内容。
- API 请求中的 Base64 仅作传输封装。

提交路径由本次增量决定，与整个归档的大小无关：

- 满足快速路径条件时，通过 GraphQL `createCommitOnBranch` 配合 `expectedHeadOid` 原子发布。
- 其他提交先通过 Git Data API 写入二进制 blob、tree 和 commit。
- 再通过 GraphQL `updateRefs`（`beforeOid`，`force=false`）原子推进默认分支。

### 同步格式升级

- 新安装默认使用 V4。
- 已有同步地址的旧安装首次启动新版时，直接显示升级弹窗。
- 新配置的地址在检测到传统云端数据或 V3 时提示升级。
- 新格式不向下兼容，需先将所有参与同步的设备更新到支持新格式的版本，再确认升级。
- 确认后立即同步并迁移该地址的云端数据，旧歌词完整保留。
- 迁移完成后，该地址的升级提示自动消失；失败或取消可重试。
- 暂不升级时 App 正常使用，待升级地址的数据同步暂停。
- 离线设备的版本由用户确认，客户端无法自动核实。

## 下载、本地导入与备份

### 下载流程

- 下载使用共享 `OkHttpClient`，不使用系统 `DownloadManager`。
- 默认并发 **6**，可在设置中调整，最高 **8**。
- 文件先写入 `files/download_staging/` 下的工作文件，再提交到应用管理目录或用户选择的 SAF 目录。
- 落盘前先准备音频元数据，提交后再写歌词、封面、`.npmeta.json` 和音频标签。
- `DownloadExecutionRoomStore` 通过 Room 持久化下载操作和恢复状态。
- `DownloadTaskStore` 管理内存中的任务展示与进度。
- `GlobalDownloadManager` 启动时先等待已有队列收敛，再恢复未完成任务，避免新旧队列互相覆盖。
- 已完成音频可通过下载索引或快照快速命中，直接结算为完成，免去重复的媒体请求和 SAF 探测。

### 断点续传

下载按传输类型自动断点续传：

- **直接 HTTP 传输**：读取已有工作文件大小，追加 `Range: bytes=<offset>-`。
- **分块 Range 传输**：按偏移续传，用于需要显式 Range 的平台播放候选。
- **HLS 下载**：记录 segment 索引和已下载字节数，通过 `.hls.json` 检查点恢复。

恢复与取消：

- 工作文件同时保存 `.resume.json` 恢复元数据，应用重启后据此重建待恢复任务。
- `GlobalDownloadManager` 启动时自动扫描并恢复未完成下载。
- 网络波动重试时保留已下载部分。
- Wi‑Fi 断开进入 `WAITING_NETWORK` 时保留断点，网络恢复或用户确认后继续。
- 手动取消会清理工作文件，并回滚已写入的半成品音频与 sidecar。

### 存储与目录

- 下载目录索引维护快照缓存和 sidecar 引用，减少 SAF 目录遍历。
- 空目录读取不会清除已有索引。
- SAF 访问明显慢于应用私有目录，仅在需要外部目录时切换。
- `StorageUsageAnalyzer` 按可清理缓存、下载内容、诊断文件和应用数据分组统计占用。
- 清理缓存只覆盖可再生成的缓存和分享暂存，下载工作文件、恢复凭据和已下载歌曲不受影响。

### 本地导入与分享

- `LocalAudioImportManager` 支持导入外部音频、扫描设备音乐。
- 导入时复制附近的 `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
- 分享本地歌曲时优先分享受控目录 URI。
- SAF/content URI 无法直接暴露时，复制到 `cache/shared_local_media` 暂存后分享，该目录属于可清理缓存。
- 本地扫描预览支持只看带元信息的歌曲。
- 创建或补充本地歌单后，应用按需在后台补全歌曲名、歌手、专辑和封面，已编辑的本地元数据优先保留。
- 下载的“元信息后处理”可单独开关，关闭后仍保存下载管理所需的元数据，但标签、歌词和封面不再写回音频文件。

### 备份

- `BackupManager` 支持本地歌单 JSON 备份、导入与差异分析。
- 配置导出默认不含登录态，可选择包含音乐平台授权及 GitHub/WebDAV 凭据，也可设置密码加密。
- 导入兼容旧版明文配置，加密配置需要原密码。
- 不含登录态的配置会保留设备已有的账号和同步凭据。

---

构建发布版与签名流程见 [CONTRIBUTING.md](../CONTRIBUTING.md#构建发布版--release-build)。
