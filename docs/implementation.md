# 实现概览 / Implementation Notes

本文承接自 [README.md](../README.md)，说明构建版本、模块边界与关键实现路径。
面向用户的安装、能力边界与隐私说明请看 README；扩展方式、测试与提交要求请看 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 构建与版本

- `compileSdk = 37`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17；Kotlin `2.4.10` / KSP `2.3.10`
- AGP `9.4.1` / Gradle `9.6.1`
- NDK `27.0.12077973`
- CMake `3.22.1`，由 Android 构建脚本固定指定
- 版本名格式：`<git短哈希>.<MMddHHmm>`
- Release APK 文件名：`NeriPlayer-<versionName>[-abi].apk`
- 默认 Release APK 只打包 `arm64-v8a`；多 ABI 输出需加
  `-PbuildAllReleaseAbis=true`
- `.github/workflows/android_native_ci.yml` 会在 Native 相关变更时运行
  Release + `-Werror`、ASan+UBSan、TSan 三组 host CTest，并单独编译
  `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四个 Android ABI。

## 模块结构

自有 Android 库按业务域组织，由 [gradle/owned-modules.txt](../gradle/owned-modules.txt) 统一登记，共 14 个。一级领域使用 `modules/<领域>`，播放和下载保留规则与运行实现两个独立构建边界。远程音乐、歌词和元数据来源集中在 `:platform`，`api` 表达同库内部的请求协议职责；公共能力契约由模型和宿主接口表达。

| 模块 | 职责 |
| --- | --- |
| [`:common`](../modules/common/README.md) | 共享工具、日志、文案、图标、语言能力和测试夹具。 |
| [`:network`](../modules/network/README.md) | 通用 HTTP、Range、网络解析和 Web 登录基础能力。 |
| [`:model`](../modules/model/README.md) | 按业务分类的数据模型、枚举和跨模块状态契约，不依赖项目实现。 |
| [`:database`](../modules/database/README.md) | Room 数据库、实体、DAO、历史 schema 与版本升级。 |
| [`:native`](../modules/native/README.md) | Native 崩溃处理、DSP 音效、USB 协议与 PCM 计算、独占传输和第三方 libusb，生成并打包 `lib_neri.so`。 |
| [`:playback:logic`](../modules/playback/logic/README.md) | 播放 policy、状态协调 runtime、PCM/audio 和队列 queue 规则，保持包级隔离。 |
| [`:playback:runtime`](../modules/playback/runtime/README.md) | Media3 引擎、播放服务、音源解析、USB 输出、Native DSP 接入、浮窗与蓝牙歌词及宿主接入。 |
| [`:download:logic`](../modules/download/logic/README.md) | 下载准入、状态迁移、重试、调度、传输槽位、所有权、提交与元数据规则。 |
| [`:download:runtime`](../modules/download/runtime/README.md) | 下载执行、Room 队列、受管文件、迁移与恢复，以及 Worker 和 JobService。 |
| [`:lyrics`](../modules/lyrics/README.md) | LRC/YRC/TTML 解析、转换、时间轴、翻译对齐、时间偏移，以及词幕（Lyricon）和 SuperLyric 接入。 |
| [`:platform`](../modules/platform/README.md) | Bilibili、网易云、YouTube 与 Subsonic 自建音乐服务器（预览版，缺乏充分测试）的协议、账号、缓存和业务能力，跨平台评论，以及歌词来源、元数据搜索、匹配与回退。 |
| [`:local`](../modules/local/README.md) | 本地设置、媒体、歌单、历史与统计、备份、流量、存储统计与缓存清理，以及 Android 同步宿主适配。 |
| [`:sync`](../modules/sync/README.md) | GitHub/WebDAV 传输、加密凭据状态、会话、兼容编解码、合并、并发保护与 Worker 策略。 |
| [`:listentogether`](../modules/listentogether/README.md) | 一起听协议、HTTP/WebSocket 传输、身份规则、会话、连接恢复、控制与播放同步。 |

`:app` 维护 Android 入口、Compose 页面和跨领域依赖组装。设置项生成由 `:ksp-annotations` / `:ksp-processor` 维护，上游歌词子模块为 `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`；`:hidden-api` 只为播放运行模块提供编译期接口桩，不随应用打包；`build-logic` 提供构建逻辑。`np-submodule/NeriPlayer-LTW` 是一起听 Cloudflare Workers 服务端，`np-submodule/miuix` 是未参与主应用构建的上游源码与文档树。这些不计入 14 个自有 Android 库。

依赖按实际职责维护，不由目录分类推导层级。`:model` 不依赖项目实现，`:common` 不依赖业务领域；数据库、存储计算、歌词解析与播放/下载规则不得反向依赖仓库或 Android 运行实现。`:platform` 内的业务仓库消费对应协议，API 不反向读取账号仓库、Room 或播放器状态，各平台仍保持包级依赖边界。`:local` 消费数据库与领域服务，客户端的账号、凭据刷新和存储能力通过窄接口注入。所有库禁止依赖 app 或形成循环。

`:playback:logic` 的 policy 只包含 `audio`、`command`、`offload`、`pending`、`progress`、`service`、`skip`、`storage`、`wake` 九个叶子包，不引用 runtime、audio 或 queue 实现；USB policy 仍归 `:playback:runtime`。`:platform` 依赖并公开 `:lyrics` 的解析能力；歌词来源仓库、匹配和回退留在平台，`:lyrics` 不依赖平台。词幕 SDK、位置推送与异步请求代次由歌词库维护，播放器通过窄加载端口提供歌词、偏好快照和生命周期输入。

共享业务契约统一放入 `modules/model`，沿用 `data.model.<业务>` 等既有包名；页面私有状态随 UI 维护，Room 实体随数据库维护，私有算法中间状态随算法维护。物理模块目录与 Kotlin 包名表达不同层次，包名与源码内部目录一致。调整类名时核对真实保存与读取方式；Worker 持久任务、JNI 导出和 Android 组件入口须保持兼容，字段、存储键与历史 schema 不能随目录整理改变。

各模块维护自己的测试、资源与 consumer R8 规则，宿主集成测试保留在 app。Room 业务映射与本地仓库编排位于 `:local`，平台缓存适配位于 `:platform`；历史 schema 位于 `modules/database/schemas`。设置 schema 在 `:local` 运行 KSP，Compose 设置渲染留在 [app 的设置页面](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/)。`app` 不保留生产 `data` 目录，下载升级和依赖组装位于 `core/startup/legacy` 与 `core/integration`。

运行 `./gradlew verifyModularization` 检查结构、JVM 测试、合并 CRAP 覆盖率、计算域依赖和 lint。`:native` 参与登记、结构与 lint 检查；纯 Native 模块不生成 JaCoCo 数据，现有 JVM 模块的覆盖率范围保持完整。Native host 测试和四 ABI 编译通过独立 CI 与模块任务验证，入口见 [:native README](../modules/native/README.md)。针对性任务见各模块 README，例如：

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
python3 -B tools_pub/quality/module_boundaries.py
```

独立与聚合门禁保留相同范围和阈值，受检方法 CRAP 大于 9 即失败。库源码文件必须少于 2000 行，每个生产目录最多 16 个直接 Kotlin/Java 文件。FFmpeg AAR 由应用打包，`lib_neri.so` 由 `:native` AAR 提供并随 app 打包；播放服务和 Kotlin 实现由 `:playback:runtime` 维护，app 通过 `core/di/player`、`core/di/ltw` 等接口组装。具体扩展规则见 [贡献指南](../CONTRIBUTING.md#项目结构与当前实现--project-layout) 与 [质量说明](../tools_pub/quality/README.md)。

## 入口与导航

- `MainActivity` 是主界面入口，同时处理启动流程、通知权限、
  外部音频导入、USB 设备插入事件和 `neriplayer://listen-together/join` 深链。
  网页登录使用独立 Activity；蓝牙媒体验证入口和 `CarMediaBrowserService` 分别处理车载能力验证与曲库浏览。
- 如果上次启动发生 JVM / Native 崩溃或系统 ANR，会优先进入 `Safe Mode`，
  只开放日志预览导出、配置导出、登录重置、设置重置和恢复正常启动。
- 主界面是 **Compose NavHost + 动态底栏**：
  `Home / Explore / Library / Settings` 为主路径。
- `Home` 会根据首页卡片可用性动态显示；`Debug` 仅开发者模式开启后显示。
- 在“首页与启动”中可关闭哔哩哔哩音乐，隐藏首页音乐榜单和热门视频，并停止加载这两个分区；设置会保留到下次启动。
- 主标签内容由 `MainTabLayerHost` 保留出场/入场双场景，平板以居中缩放和淡入淡出切换，手机保留横向位移；
  每个场景独立持有可保存状态和高级玻璃 owner，快速反向切换也会从当前进度继续。
- 详情页默认使用背景轻微下沉、前景自下而上展开的抽屉式反馈；
  开启“连贯反馈”后，背景与详情页改为同步接力移动。
- `Now Playing` 不是普通路由，而是覆盖在主导航之上的全屏播放层，
  底部常驻 `Mini Player`；Mini Player 支持横向滑动切歌。
- 启动器快捷方式由 `LauncherShortcuts` 映射为继续播放、探索、媒体库和随机喜欢，
  只落到主标签或播放服务入口，不会绕过正常导航状态恢复。
- `Library` 使用分页导航组织本地、收藏、音乐服务器（预览版）、网易云、YouTube Music 与 Bilibili；YouTube Music 入口可在设置中关闭，同时提供最近播放和播放统计入口。
- 本地媒体库支持歌单/歌手二级分类；收藏页支持歌单/歌手/热点二级分类；
  网易云页支持歌单/专辑二级分类。
- 本地歌手详情页由 `LocalArtistDetailScreen` 承载，支持播放全部、多选、
  导出歌单和批量下载在线歌曲；网易云艺术家详情页由
  `NeteaseArtistDetailScreen` 承载，支持歌曲/专辑分页和关注状态。
- 平板的播放页、歌词页、艺术家详情和设置页会限制内容宽度并调整底部操作区，
  避免大屏布局过宽或操作按钮飘散。
- 平板横屏主界面使用左侧导航栏，竖屏保留底部导航；竖屏播放页限制封面和操作区宽度，给歌词保留更多空间。
  侧栏只显示当前选中项的文字；横屏隐藏系统导航栏，边缘滑动可临时唤出，竖屏恢复显示。
  平板迷你播放器增加上一首、下一首按钮，窄窗口保留播放按钮和滑动切歌。
  完整歌词页在竖屏下使用居中的阅读区和紧凑控制区；侧栏选项组垂直居中，播放器转场由覆盖层自然遮挡侧栏，保持底层页面宽度和模糊背景稳定。
  未手动设置时，平板主控默认位于底部并包含进度条，歌词和翻译默认字号分别为手机的 125% 和 115%；已有字号和位置选择保持不变。
- 平板界面支持跟随系统旋转，手机仅在播放页允许横屏。横屏播放页按可用高度调整封面、歌词和控制区；
  平板保留宽松双栏，手机使用紧凑双栏，隐藏辅助工具栏并缩小播放按钮；手机横屏返回后保持竖屏，重新打开播放页再允许旋转。
  横屏歌词区域保持透明，给封面保留上下间距；仅手机横屏隐藏音频规格信息，平板遵循已有显示设置。
  手机横屏将播放控制与进度固定在右下方，歌曲操作位于右上方；平板和竖屏保留播放控制位置偏好。
  手机横屏歌词页与封面页共用布局，保留各自字体和按钮尺寸设置，左右滑动可以切换两页；
  平板横屏固定显示完整歌词分栏，不通过横滑切换歌词样式。
  手机短横屏的歌曲编辑面板使用紧凑封面与独立滚动表单，表单滚动不会拖动面板。
  歌曲和歌词编辑面板按可用高度安排输入与操作区，键盘或大字体压缩空间时可滚动访问全部操作。
  保留方形封面。打开播放页会结束底层输入焦点和文字选择菜单。
- 平板启动引导和免责声明在横竖屏下均使用说明与设置双栏，内容可滚动，操作按钮保持固定。
- 平板网易云歌手详情使用简介与作品双栏；播放统计使用独立概览卡片和排行榜，窄窗口自动调整布局。
  歌手封面适当缩小，关注按钮位于简介上方，作品区与迷你播放器之间保留紧凑间距。
  平板评论面板顶部留有间距，横屏标题和输入区保持紧凑，给评论列表保留更多空间。

## 播放、缓存与服务

- 播放核心基于 Media3 ExoPlayer，由 `PlayerManager` 统一管理。
- `AudioPlayerService` 提供前台播放服务、媒体通知、MediaSession 和基础传输控制。
- Bilibili 播放通过 `ConditionalHttpDataSourceFactory`
  动态附加 `Referer / User-Agent / Cookie`。
- YouTube Music 播放包含平台 Range 传输兼容、Seek 刷新策略和预取逻辑；播放解析
  会复用登录 Cookie、匿名会话、bootstrap、`player.js` 和 PoToken 缓存，并在播放候选
  被拒时回退 EJS/HLS；长音频的大幅 Seek 会进入更短的启动恢复窗口，避免等待普通
  播放启动看门狗超时。
- 网易云播放会在无权限、无可用播放结果或试听片段场景下自动降级音质；
  仍不可播时可根据设置自动匹配 Bilibili 音源或本地音频。
- 播放状态会定期持久化，用于进程重启后的队列和状态恢复。
- 播放器实现已按 `playback/`、`url/`、`resolver/`、`service/`、`effects/`、
  `lifecycle/`、`watchdog/` 与 `usb/` 等职责分包；共享歌曲与播放状态契约由 `:model` 维护。
- 睡眠定时器、淡入淡出、切歌交叉淡入淡出、播放模式恢复等均由播放器层管理。
- 预抢占音频焦点、混音播放、蓝牙断连暂停和 USB 独占播放通过播放偏好快照
  在播放器启动早期生效。
- USB 独占目前面向 **UAC1.0** 与兼容 **UAC2.0 Type I PCM** 设备，支持设备选择、采样率/位深/缓冲策略、
  兼容性开关、32-bit PCM、PCM float 软件转换和后台缓冲区设置；Native 独占会先尝试源采样率，
  精确格式不可用且兼容策略开启时再尝试设备兼容采样率；为了减少卡住，
  兼容的 UAC2 异步显式反馈拓扑会解析时钟链、反馈端点与报告周期，
  Runtime Report v2 会暴露反馈状态、端点、速率、holdover 和长间隙重捕获计数；
  播放器层还包含启动看门狗、前后台健康审计、keep-alive 检查、
  带代次协调的 AudioSink 重配置、动态传输扩缩容、Native 传输背压恢复，
  以及必要时的系统播放回退；前后台读取到 Native 延迟刷新状态时会在有限次数内
  重试。播放器会按网络或本地来源选择唤醒策略，前后台切换后可恢复 USB 独占，
  启用比特完美音量时软件增益固定为 0 dB。USB 设备插入响应由设置控制，
  关闭后 Activity alias 和播放服务都会忽略 `USB_DEVICE_ATTACHED` 唤起路径。

## 搜索与数据来源

- **UI 搜索**：
  `Explore` 当前接入网易云、Bilibili 和 YouTube Music，
  采用按平台独立搜索，不混合聚合结果。网易云可切换歌曲/歌单/歌手，
  网易云和 Bilibili 支持触底分页；链接识别支持直接链接和含短链接的分享文案，
  Bilibili 视频链接会保留 `p` / `cid` 指向的分 P，合集分享会优先使用
  视频详情里的 UGC season，必要时回退到链接里的 `season_id`。
  搜索历史保存在本地，可在“设置 > 通用”中独立关闭显示和记录。
- **元数据补全**：
  播放页通过 `SearchManager` 使用网易云与 QQ 音乐补全封面、歌词和曲目信息。
- **歌曲百科**：
  `NeteaseSongEncyclopediaRepository` 根据网易云歌曲身份加载百科，并使用限时内存缓存；
  曲目切换会取消旧请求并校验请求代次，避免旧歌结果覆盖新歌。艺人和创作团队等附加数据
  加载失败时只降级对应内容；本机听歌记录与在线百科分别展示。
- **歌词来源与匹配**：
  - **能力与编辑器匹配**：支持原文、翻译、音译、逐词歌词、歌词分享与手动编辑。除平台自带歌词外，内置 LRCLIB 外部歌词客户端。歌词编辑器提供独立的“匹配”入口，支持修改搜索关键字、先选平台再搜索，并按平台临时缓存结果；切换平台过滤直接复用缓存，不重复请求。匹配结果会自动清理标题、制作信息等非歌词行，并综合歌名、歌手、专辑、时长和歌词类型排序（优先逐词歌词，同时保留普通歌词候选）。
  - **来源偏好与回退**：“设置 > 歌词设置 > 来源偏好”可指定默认歌词来源（`自动` / `网易云` / `酷狗` / `QQ 音乐` / `LRCLIB` / `AMLL TTML`）并开关“优先使用逐词歌词”，默认设为 `自动` 并开启优先逐词。按歌名和歌手检索指定来源时会校验时长，时长未知或不符则回退到自动歌词。在线歌曲先展示已缓存歌词，后台再请求指定来源，匹配成功后替换，未命中则保留原歌词。修改偏好会立即清理已缓存结果以生效。
  - **B 站 CC 字幕**：属于独立设置项，默认开启，需登录哔哩哔哩账号后生效。
  - **音译补全**：当前歌词源缺少音译时，会尝试从网易云补全音译并保留当前源的原文与译文；来源自带音译或用户手动编辑/清空的音译始终优先。
  - **默认歌词偏移**：“歌词偏移”卡片支持分别调整 6 个来源的偏移量，可单独或一键全部重置：

| 歌词来源 | 默认偏移 |
| :--- | :--- |
| 网易云 | `1000 ms` |
| QQ 音乐 | `500 ms` |
| 酷狗 / LRCLIB / AMLL TTML / B 站 CC | `0 ms` |

- **词幕适配**：
  `LyriconManager` 向 Lyricon 与 SuperLyric 输出当前歌曲、播放状态、进度、
  逐字歌词与翻译歌词；进度通过独立的 200 ms feed loop 推送并使用时间锚点校准，
  状态栏歌词依赖厂商能力，当前面向部分支持设备。
- **艺术家入口**：
  网易云搜索、首页、歌单/专辑详情和播放页都会尽量保留 `neteaseArtists`
  元数据，用于进入网易云艺术家详情。
- **网易云歌单缓存**：
  `NeteasePlaylistCacheRepository` 会缓存歌单 header、曲目、最近曲目签名和保存时间；
  `NeteaseCollectionDetailViewModel` 会先发布缓存再刷新网络，
  当曲目签名未变化或网络失败时复用缓存。

## 本地数据与安全

- 常规设置使用 `DataStore` 持久化，并通过 KSP 生成设置 key、备份白名单和设置 UI 元数据。
- 主题模式由 `ThemeMode` 管理，支持浅色、深色和跟随系统的 Auto 模式。
- 平台 Cookie、YouTube 授权信息、GitHub Token 与 WebDAV 密码使用
  `Android Keystore + EncryptedSharedPreferences` 本地加密保存。
- 本地歌单、最近播放、播放统计和收藏快照主要通过 `Room` 持久化，实体、DAO 与升级链由 `:database` 维护。
- 旧 JSON 保留升级导入和故障回退路径；歌单 JSON 导入/导出与远端同步格式由各自流程维护，回退文件仍使用原子写入。
- 收藏和播放统计的首次加载在 IO 协程中完成；主存读取失败时保留原数据，相关写入、备份与同步等待完整加载成功，可在后续操作中重试。
- GitHub 与 WebDAV 共用的同步载荷模型位于 `:model` 的 `data/model/sync/`，
  传输、加密凭据状态、会话、兼容编解码、清洗、合并和调度策略由 `:sync` 维护，
  Android 仓库与 WorkManager 适配位于 `:local`。
  GitHub/WebDAV 管理器通过后端接口接入共享会话，app 中不再维护同步实现目录。
  删除记录会和撤销操作一起进入合并策略，避免本地撤销后的歌曲在下一轮同步又被旧删除记录移除。
- GitHub/WebDAV 同步使用本地生成的 UUID 作为设备标识，不依赖 `ANDROID_ID`。
- 已配置且开启自动同步时，打开应用会立即提交持久同步任务，播放期间也会执行；双后端启用时，WebDAV 任务错开 10 秒。自动同步开关和同步格式升级确认仍决定任务是否允许执行。
- GitHub 同步传输与 V4 归档格式：
  - 内容采用 Protobuf 记录流和 ZSTD 内容分块，单个压缩对象不超过 2 MiB；根清单提交完整快照，未变更的分块直接复用不重传。
  - 满足快速路径条件的提交通过 GraphQL `createCommitOnBranch` 配合 `expectedHeadOid` 原子发布；其他提交先通过 Git Data API 写入二进制 blob、tree 和 commit，再通过 GraphQL `updateRefs`（`beforeOid`，`force=false`）原子推进默认分支。路径选择取决于本次增量，而非整个归档的大小。
  - 仓库内保存原始二进制正文，通过 raw 端点读取固定远端提交的内容；API 请求中的 Base64 仅作传输封装。
- 新安装默认使用 V4；已有同步地址的旧安装首次启动新版会直接显示升级弹窗，新配置的地址在检测到传统云端数据或 V3 时提示升级。
  新同步格式不向下兼容，必须先将所有参与同步的设备更新到支持新格式的版本，再确认升级；确认后立即同步并迁移该地址的云端数据。
  迁移完整保留旧歌词，不再提供常驻有损压缩选项；该地址迁移完成后升级提示自动消失，失败或取消可以重试。
  暂不升级仍可正常使用 App，但待升级地址的数据同步保持暂停。
  离线设备的版本由用户确认，客户端不能自动核实。

## 下载、本地导入与备份

- 下载使用共享 `OkHttpClient`，不是系统 `DownloadManager`。
- 默认下载并发为 **6**，可在设置中调整，最高 **8**。
- 下载文件先写入 `files/download_staging/` 下的工作文件，再提交到应用管理目录
  或用户选择的 SAF 目录；正式落盘前会先准备音频元数据，提交后再写歌词、封面、
  `.npmeta.json` 和音频标签。
- `DownloadExecutionRoomStore` 通过 Room 持久化下载操作和恢复状态；
  `DownloadTaskStore` 管理内存中的任务展示与进度。
  `GlobalDownloadManager` 启动时会等待已有队列收敛，再恢复未完成任务，
  避免旧队列和新请求互相覆盖。
- 已完成音频如果能通过下载索引或快照快速命中，会直接结算为完成，
  避免重复请求媒体和重复 SAF 探测。
- 下载支持**自动断点续传**，但按传输类型分别实现：
  - **直接 HTTP 传输**：优先读取已有工作文件大小并追加 `Range: bytes=<offset>-`
  - **分块 Range 传输**：按偏移续传，主要用于需要显式 Range 的平台播放候选
  - **HLS 下载**：记录 segment 索引和已下载字节数，通过 `.hls.json` 检查点恢复
- 工作文件会同时保存 `.resume.json` 恢复元数据，用来在应用重启后重建待恢复任务；
  `GlobalDownloadManager` 启动时会自动扫描并恢复未完成下载。
- 网络波动重试时会尽量保留已下载部分；因为 Wi‑Fi 断开而进入
  `WAITING_NETWORK` 时，也会保留断点并在网络恢复或用户确认后继续。
- 手动取消则会清理工作文件，并回滚已写入的半成品音频与 sidecar，
  不是“暂停后随时继续”的语义。
- 下载目录索引会维护快照缓存和 sidecar 引用，减少 SAF 目录遍历；
  但 Android 的 SAF 访问仍明显慢于应用私有目录，且空目录读取不会清除已有索引；
  只有确实需要外部目录时才建议切换。
- `StorageUsageAnalyzer` 会按可清理缓存、下载内容、诊断文件和应用数据分组统计占用；
  清理缓存只覆盖可再生成的缓存和分享暂存，保留下载工作文件、恢复凭据和已下载歌曲。
- `LocalAudioImportManager` 支持导入外部音频、扫描设备音乐，
  并复制附近的 `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
- 分享本地歌曲时会优先直接分享受控目录 URI；SAF/content URI 无法直接暴露时，
  才复制到 `cache/shared_local_media` 暂存后分享，该目录属于可清理缓存。
- 本地扫描预览支持只看带元信息的歌曲；创建或补充本地歌单后，
  应用会按需在后台继续补全歌曲名、歌手、专辑和封面信息，并尽量保留已编辑的本地元数据。
- 下载的“元信息后处理”可单独开关；关闭后仍会保留下载管理所需的元数据，
  只是不再把标签、歌词和封面写回音频文件。
- `BackupManager` 支持本地歌单 JSON 备份、导入与差异分析。
- 配置导出默认不包含登录态，可选择包含音乐平台授权及 GitHub/WebDAV 凭据，也可设置密码加密。
  导入兼容旧版明文配置；加密配置需要原密码，不含登录态的配置会保留设备已有账号和同步凭据。

---

构建发布版与签名流程见 [CONTRIBUTING.md](../CONTRIBUTING.md#构建发布版--release-build)。
