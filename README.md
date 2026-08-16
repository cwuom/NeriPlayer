[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer (音理音理!)</h1>

<div align="center">

<h3>✨ 一个把多源在线播放、本地管理、歌词体验和自建同步做进原生 Android 的音频播放器 🎵</h3>

<p>
  <a href="https://github.com/cwuom/NeriPlayer/releases">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/cwuom/NeriPlayer/total?style=social" />
  </a>
  <a href="https://github.com/cwuom/NeriPlayer/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/cwuom/NeriPlayer?include_prereleases&label=Release" />
  </a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
  <a href="https://t.me/ouom_pub">
    <img alt="Telegram" src="https://img.shields.io/badge/Telegram-@ouom__pub-blue" />
  </a>
  <a href="https://t.me/neriplayer_ci">
    <img alt="CI Builds" src="https://img.shields.io/badge/CI_Builds-@neriplayer__ci-orange" />
  </a>
</p>

<p>
  <img src="icon/neriplayer.svg" width="260" alt="NeriPlayer logo" />
</p>

<p>
本项目的名称及图标灵感来源于《星空鉄道とシロの旅》中的角色「风又音理」。
</p>

<p>
项目采用原生 Android 开发，支持 Android 9 (API 28) 及以上设备，
围绕「多源探索、在线播放、本地可控、数据自持」持续打磨。
</p>

🛠️ <strong>Active development / 持续迭代中</strong>

<a href="https://trendshift.io/repositories/23906" target="_blank"><img src="https://trendshift.io/api/badge/repositories/23906" alt="cwuom%2FNeriPlayer | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

> [!WARNING]
> 本项目仅供学习与研究使用，请勿将其用于任何非法用途。
> 请只在你拥有权利、授权或第三方平台规则允许的范围内访问、播放或保存内容。
> 本项目不提供媒体内容、密钥、规避付费/DRM/地区限制的方案，也不提供公共媒体代理或再分发服务。
>
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

---

> [!NOTE]
> NeriPlayer 不提供公共云端曲库或媒体分发服务。
> 在线音频能力依赖用户在第三方平台上的账号授权，
> 会员或受限内容仍需遵循原平台规则。
> 文档中提到的 YouTube Music 相关能力仅指账号会话、播放兼容和错误恢复，
> 不代表绕过平台限制、复制受保护内容或重新分发媒体。

---

## 快速定位 / Start here

如果你只是想体验应用，请看 [快速体验](#快速体验--getting-started)。
如果你想了解项目能力，请看 [项目亮点](#项目亮点--why-it-stands-out)
和 [核心特性](#核心特性--key-features)。
如果你准备贡献代码，请直接阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。
如果你要自建一起听服务端，请看
[一起听服务端部署](#一起听服务端部署--listen-together-deployment)。

```text
NeriPlayer
├── 多源在线播放：网易云 / Bilibili / YouTube Music
├── 本地优先数据：缓存、下载、歌单、历史、统计、设置
├── 可选自有同步：GitHub / WebDAV 元数据同步
├── 丰富播放体验：Media3、歌词、音效、流体背景、桌面小组件、启动器快捷方式、悬浮/状态栏歌词
└── 可恢复运行：安全模式、崩溃日志、ANR 记录、调试探针
```

## 项目简介 / About

NeriPlayer 是一个基于 **Jetpack Compose + Media3** 的原生 Android
音频播放器。它不构建公共云端服务，而是在用户具备第三方平台账号能力的前提下，
整合 **网易云音乐**、**Bilibili** 与 **YouTube Music** 的在线内容，
并提供本地播放、下载、缓存、歌单管理和多种同步/备份能力。

当前定位：

- **账号即能力**：通过第三方平台授权启用搜索、播放、歌单和收藏夹访问。
- **本地优先**：播放缓存、下载文件、歌单、历史记录、设置与授权信息默认保存在设备本地。
- **可选同步**：可将歌单、收藏、最近播放、歌曲播放统计、歌单打开统计和
  本地歌单播放统计同步到用户自己的
  GitHub 仓库（应用内新建时默认使用私有仓库）或 WebDAV 远端文件。
- **尊重隐私与账号安全**：同步策略刻意保持去中心化，
  数据写入用户自己控制的 GitHub/WebDAV 远端，而不是上传到项目维护者的中心化服务。
  应用并非没有能力把播放历史回写到第三方音乐平台；但中心化音乐平台的客户端
  通常存在风控与行为采样机制，直接上报历史播放数据可能触发异常登录、异常播放
  或账号冻结等风险。出于保护账号安全的考虑，NeriPlayer 暂不向这些平台上传
  本地播放历史和播放统计。
- **单 Activity + Compose 架构**：`MainActivity` 是唯一对外入口，
  UI 由 Compose `NavHost`、动态底栏、Mini Player 与 Now Playing 覆盖层组织。
- **启动与恢复链路**：正常启动流程为
  `Loading -> Disclaimer -> Onboarding -> Main`；
  如果上次启动发生崩溃或系统 ANR，会先进入 `Safe Mode`。
- **测试护栏**：下载存储、同步合并、YouTube 播放兼容、一起听、歌词解析、
  播放策略、配置备份与安全模式等关键链路都有对应单元测试或设备测试。

---

## 项目亮点 / Why it stands out

- **本地优先，也认真做脱机体验**：
  `NetworkStatusMonitor` 基于系统默认网络承载自动识别脱机状态，
  `offlineCachedImageRequest` 会在脱机时阻断远程图片请求并优先使用缓存；
  首页、探索、播放页、歌词页、歌单和下载列表都会接收 `offlineMode`，
  网络断开时仍能围绕本地文件、已下载音频、播放缓存、缓存封面和本地歌单继续使用。
- **多源播放不是简单入口堆叠**：
  `PlayerManager` 负责音源解析、队列和失败恢复；网易云不可播、无可用播放结果或
  只返回试听片段时，会先尝试音质降级，再由
  `PlayerManagerNeteaseAutoSourceSwitch` 按歌名、歌手和时长评分自动匹配
  Bilibili 音源兜底；播放异常时还会刷新当前链接，连续失败再跳过或停止。
- **YouTube 播放兼容有多级回退**：
  登录态会保留有效身份 Cookie，并与匿名 visitor 分别维护 bootstrap 和 PoToken
  会话；Cookie 轮换、会话参数、`player.js` 和挑战结果会优先复用缓存，失效的
  `player.js` 或被平台拒绝的播放候选会进入 EJS/HLS 回退，避免在同一候选上反复重试。
- **GLSL/AGSL 高性能流体背景**：
  播放页动态背景由 `BgEffectPainter` 加载
  `assets/shaders/hyper_background_effect.glsl` 并通过 `RuntimeShader` 逐帧渲染；
  shader 内部基于封面取色、动态色块和轻量颗粒噪声生成流体背景，
  并接入 `uMusicLevel / uBeat` 做音频响应，不是简单把封面做高斯模糊。
- **仿 Apple Music 的深度歌词体验**：
  `SyncedLyricsView` 与 `AdvancedLyricsView` 支持逐词/逐字高亮、翻译歌词、
  音译显示、歌词偏移、点击跳转、长按分享、景深模糊、边缘渐隐和全屏歌词；
  `LyricShareSheet` 可选择歌词行，复制文本、分享歌曲或生成 1080px 歌词卡片；
  歌词页遇到含假名的日语原文时，会为翻译行额外留出间距，避免日文字形和译文挤在一起；
  悬浮歌词、状态栏歌词、SuperLyric、Lyricon、蓝牙歌词和歌词编辑
  也复用同一条播放数据链路。
- **完整的本地歌曲管理链路**：
  `LocalAudioImportManager` 支持外部分享/打开导入、授权文件夹扫描、
  设备媒体库扫描和常见音频格式识别，并会处理附近的 `lrc/txt` 歌词与
  `cover/folder/front` 封面；大批量扫描会先给出快速预览，再在后台补全
  更完整的歌名、歌手、专辑和封面信息；`LocalPlaylistRepository`
  负责本地系统歌单、普通歌单、收藏、排序、去重、备份和同步触发。
- **媒体库已经有“分类浏览”的骨架**：
  `Library` 不只是歌单列表，本地内容可在歌单/歌手之间切换，
  `LocalArtistSummary` 会按展示艺术家自动聚合歌曲、拆分常见合作歌手写法、
  生成稳定身份和封面；
  网易云歌曲还能进入艺术家详情页，查看热门歌曲和专辑，并把艺术家关注到收藏页。
- **大屏和日常操作都在补手感**：
  平板/横屏播放页、歌词页、设置页和艺术家页会使用更稳定的宽度与底部操作布局；
  `Mini Player` 支持横向滑动切到上一首/下一首，常用播放控制不用每次展开全屏；
  桌面小组件提供 4x2 播放卡片和 2x2 迷你播放器；卡片背景和主播放键会从当前
  封面取色，4x2 显示当前歌曲、封面、进度、四项播放控制和桌面歌词入口，
  2x2 以封面为主视觉并保留三项播放控制，两者都复用播放服务的控制链路；
  启动器快捷方式可直接继续播放、打开探索、打开媒体库或随机播放我喜欢的音乐；
  底部主标签按目标顺序做可打断的横向换页，转场中会同时保留出场与入场页面，
  避免玻璃背景、滚动位置和页面状态在快速切换时闪断；
  播放页长按封面可打开沉浸式大图预览，支持双指缩放、拖动与下载封面。
- **听感也能细调**：
  `PlaybackEffectsController` 将倍速、音调、`Equalizer` 和
  `LoudnessEnhancer` 绑定到当前 Media3 音频会话；
  内置多种均衡器预设，也支持手动频段、响度增强、按歌曲实时响度均衡、
  声道平衡、32-bit 高解析普通输出、淡入淡出、交叉淡入淡出、
  蓝牙断连暂停、USB 独占播放和音频焦点策略。
  USB 独占会由 native 驱动接管兼容 USB 音频设备，避免系统音和其他应用共用 USB 通道；
  当前面向 **UAC1.0** 和兼容 **UAC2.0 Type I PCM** 设备，设备选择、
  采样率/位深/缓冲策略、32-bit PCM、PCM float 软件转换、后台运行提醒、
  UAC2 时钟拓扑与显式反馈端点解析、启动看门狗、前后台健康审计、
  动态传输扩缩容和卡死自动恢复也都补齐了；前后台切换会按网络或本地来源选择
  唤醒策略，USB 独占播放可在回到前台后恢复；长调度间隙后会重新捕获反馈时钟，
  避免继续使用陈旧的速率估计。比特完美音量模式保持软件增益为 0 dB，交给 DAC
  硬件控制音量。
- **下载链路已从“能下”进化到“能恢复”**：
  下载不走系统 `DownloadManager`，而是用共享 `OkHttpClient`、
  可调并发、工作文件和 sidecar 元数据管理完整落盘流程；
  直接 HTTP 传输、平台需要显式 Range 的传输和 HLS 都有对应续传策略，网络策略暂停后可继续，
  启动时会恢复未完成队列，已落盘的缓存命中会直接结算，
  手动取消则会清理半成品，语义边界很清楚；下载完成后即使标签写入失败，已落盘
  音频仍会保留。
- **存储占用能看清，也能有边界地清理**：
  `StorageUsageAnalyzer` 会把音频缓存、图片缓存、下载暂存、分享暂存、
  平台歌单缓存、下载内容、日志、崩溃报告和核心应用数据分组统计；
  清缓存支持只清可再生成的缓存，不会把用户主动下载的歌曲当作缓存删掉。
- **去中心化同步与播放统计**：
  NeriPlayer 不提供公共云端曲库或开发者托管账号数据；
  GitHub/WebDAV 同步只在用户自己的远端保存歌单、收藏、最近播放和
  播放统计等元数据。`PlaybackStatsRepository` 按歌曲稳定身份记录播放次数、
  收听时长、最近播放和每日桶，并参与同步合并；播放和流量统计采用延迟批量写入，
  在关键生命周期阶段 flush，并限制每日统计桶数量。
  远端同步快照读取时会兼容旧字段和异常缺字段数据，过滤缺少可解析歌曲身份的记录。
- **流量管理不是事后看数字**：
  `TrafficStatsRepository` 会区分播放流量、下载流量、Wi‑Fi、移动网络、
  漫游和缓存命中；批量或单曲下载在高风险网络下还能主动弹出提示，
  避免把“省流量”只做成设置页里的摆设；统计写入同样使用批量累积和生命周期 flush。
- **高个性化，不只是换主题色**：
  设置 schema 由 `AutoSettingsSchema` 管理，覆盖动态取色、种子色、
  主题风格、自动/浅色/深色模式、UI 缩放、自定义背景、歌词字号、
  歌词模糊、播放页流体背景、两级高级模糊、连贯反馈、首页卡片开关、默认启动页、触感反馈，
  以及歌曲自定义名称、歌手和封面等细粒度选项。
  OnePlus 的高密度屏幕（系统 `densityDpi >= 500`）会自动叠加 `0.95x` UI 修正，
  普通密度机型不触发修正，用户手动设置值仍会作为额外乘数参与计算。
  “进阶高级模糊”和“连贯反馈”默认关闭；关闭连贯反馈时，歌单等详情页使用抽屉式展开；
  进阶高级模糊仅在 Android 13+ 且父级高级模糊开启时可用；
  它会增强屏幕级顶部 Tab、底部 Tab 和设置结构卡片的实时玻璃材质，
  并提供 `12-64 dp` 的模糊度调节，不改变文字、图标、布局与点击区域。
  模糊质量可独立选择“超低、低、默认、高”：超低和低保持与默认相同的模糊范围，
  通过局部区域渲染、动态下采样和 RenderNode 硬件缓存降低渲染开销。
  检测到天玑 SoC 且尚未保存过选择时默认使用“超低”；前后台切换或更改质量时会
  重建局部缓存，避免恢复后出现异常背景。“高”需要先开启进阶高级模糊。
- **ANR、崩溃和安全模式都纳入诊断闭环**：
  `AnrWatchdog` 会读取 Android `ApplicationExitInfo.REASON_ANR` 并保存系统
  ANR trace；`ExceptionHandler` 与 `NativeCrashHandler` 分别记录 JVM 和
  Native 崩溃。上次启动异常时，`SafeModeManager` 可跳过完整应用初始化，
  直接进入安全模式预览、复制或导出日志。
- **一起听不是“同步一个进度条”这么简单**：
  客户端和 Cloudflare Worker 共同维护房间、角色、队列、播放状态、
  循环/随机模式、房主离线恢复、成员控制请求、会话播放候选共享、版本门控更新和自定义服务端地址；
  客户端还会估算服务端时钟偏移、按漂移阈值校正进度，并在会话候选异步返回后
  重载权威音源，避免本地待启动状态覆盖房间的暂停或播放指令；
  邀请链接必须包含首次加入所需的房间密钥，成员密钥不会出现在公开房间状态中；
  本地歌曲不能创建或替换一起听房间当前曲目；开启会话候选共享后，房主当前歌曲的候选播放地址会由
  Durable Object 临时缓存，听众可在成员会话内取得当前候选而无需等待房主再次响应；当前曲目最多保留三条
  经校验的候选，听众始终先按本机音质策略解析，失败后才在会话内回退，候选不会写入歌曲
  或离线缓存；同一成员凭据重连不会触发成员变更暂停，房主和听众都会定期保活连接；
  服务端使用 Durable Objects 持久化房间状态，并通过 WebSocket 做实时同步。

---

## 快速体验 / Getting Started

### a. 下载 Release 版本（推荐）

1. 前往 [GitHub Releases](https://github.com/cwuom/NeriPlayer/releases)
2. 如何选择版本？
- 大部分手机请选择 `arm64-v8a`
- 老旧 32 位设备请选择 `armeabi-v7a`
- `x86` / `x86_64` 主要用于模拟器、英特尔设备或 Chromebook

> [!IMPORTANT]
> Release 渠道不是严格意义上的稳定通道。版本通常在完成一批功能后手动发布，
> 仍可能包含未充分暴露的问题。

### b. 下载 CI 版本

1. 前往 [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions)
   下载最近一次成功构建的 Artifacts 并解压。
2. 或访问 [NeriPlayer CI Builds](https://t.me/neriplayer_ci)。

> master 分支 CI 默认上传 `arm64-v8a` APK；手动 Release 流程会构建多 ABI APK。

### c. 本地构建

1. 克隆仓库并初始化子模块：
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 使用 Android Studio 最新稳定版打开项目并同步依赖。
3. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. 安装 APK：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. 首次启动时先阅读免责声明并完成启动引导；引导页会说明通知与本地音乐权限，
   仅在你主动点按后申请，跳过不影响继续使用。
6. 如需调试工具，在设置页连续点击 **版本号** 7 次启用开发者模式，
   底栏会出现独立 `Debug` 页面。

> DEBUG 构建仅用于测试，性能和体积不代表发布版。

发布版构建与签名流程请参阅
[CONTRIBUTING.md](./CONTRIBUTING.md#构建发布版--release-build)。

---

## 核心特性 / Key Features

- 🎧 **多源探索与播放**：
  支持网易云音乐、Bilibili、YouTube Music 与本地音频播放。
- 🏠 **首页推荐与继续播放**：
  首页支持最近常用歌单、网易云动态推荐源、雷达歌单与推荐卡片；
  默认同时展示榜单、新歌、日推、私人 FM、精品歌单等可用来源，刷新会更新全部分区。
  国际化模式下优先展示 YouTube Music 首页歌单与歌曲货架。
- 🗂️ **媒体库分类浏览**：
  `Library` 提供本地、收藏、网易云、YouTube Music、Bilibili 等入口；
  可在“设置 > 通用”中完全禁用 YouTube，关闭后不会展示相关入口或执行后台预热；
  本地页支持歌单/歌手切换、搜索、歌手排序，收藏页支持歌单/歌手切换，
  网易云页支持歌单/专辑切换，Bilibili 页区分创建收藏夹、订阅收藏夹和合集。
- 🔍 **分层搜索能力**：
  `Explore` 使用网易云 / Bilibili / YouTube Music 按平台独立搜索；
  网易云支持歌曲、歌单和歌手分类，网易云与 Bilibili 搜索结果可在列表触底后
  自动加载下一页；“链接识别”可直接粘贴链接或含标题、短链接的整段分享文案，
  识别网易云歌曲/歌单/歌手、Bilibili 视频/收藏夹/合集/UP 主及 YouTube
  视频/歌单/频道。项目已有详情页的类型可直接打开，暂无详情页的类型会明确提示；
  Bilibili 链接会尽量保留 `p` / `cid` 分 P 和 `season_id` 合集上下文，
  分享合集时可直接解析到对应合集列表。
  搜索框下方会显示本地搜索历史，默认记录防抖搜索过的关键词；
  可在“设置 > 通用”关闭探索页搜索历史，关闭后不显示也不再记录新关键词，
  已有记录不会被自动删除，重新开启后仍可显示；
  播放页元数据补全使用网易云 / QQ 音乐，并接入 LRCLIB 外部歌词来源；
  歌词编辑器可选择酷狗、网易云、QQ 音乐、AMLL TTML、LRCLIB 和
  YouTube Music 后手动匹配歌词，逐字歌词优先但不隐藏普通歌词，并会自动清理
  标题、制作信息等非歌词行。
- 🧠 **Media3 播放核心**：
  `PlayerManager` 管理音源解析、队列、随机/循环、状态恢复、失败重试、
  播放链接刷新、YouTube 预取与平台特殊请求策略。
  随机播放会先生成真实乱序队列，再按该队列顺序播放、持久化和展示。
- YouTube 登录态会保留有效 Cookie，并支持 Cookie 轮换、匿名播放会话、bootstrap/
  `player.js`/PoToken 缓存和优先级预取；签名或播放候选被拒时可回退 EJS/HLS。
- ⏩ **可选 BilibiliSponsorBlock 自动跳过**：默认关闭。开启后仅向公开接口发送当前
  BV 号的 SHA-256 前缀，按分 P 和时长筛选 `intro`、`outro`、`sponsor`、
  `music_offtopic`、`filler`、`padding` 片段并在本地跳转；不会上传账号、播放历史或新片段。一起听期间保持关闭，
  避免房间播放状态漂移。
- ⏭️ **Bilibili 自定义跳过区间**：可从正在播放的更多选项或 Bilibili 收藏夹/合集条目
  管理同一视频分 P 的多个区间；规则按 `BVID + CID` 本地持久化，并通过 GitHub/WebDAV
  同步。播放页可使用设为开始/结束、前后 5 秒及播放/暂停辅助定位；一起听期间不应用本地自动跳过，避免房间状态漂移。
- 🔁 **网易云播放兜底**：
  网易云歌曲无权限、无可用播放结果或仅返回试听片段时，会先尝试降低音质；
  Bilibili 和本地音频兜底默认关闭，只有用户主动开启后，才会按歌曲名、歌手和时长
  匹配 Bilibili 候选或按稳定元数据匹配可读的本地音频。
- 🧯 **播放失败兜底**：
  播放异常时优先刷新当前播放链接；Bilibili 播放请求支持 DASH 音频重试和
  html5/mp4 渐进流回退，连续失败时自动跳过或停止避免卡死。
- 🎚️ **播放音效**：
  Now Playing 内置倍速、音调、响度增强和系统均衡器预设/手动频段调节；
  播放设置可开启按歌曲实时分析的响度均衡、声道平衡和 32-bit 高解析普通输出。
  响度均衡在 USB 独占播放时保持旁路；高解析普通输出会优先保留高精度管线，
  并旁路响度均衡、声道平衡、音频可视化和应用内倍速等处理。
- 🎛️ **细粒度播放行为**：
  支持保留上次播放进度、恢复播放模式、淡入淡出、切歌交叉淡入淡出、
  蓝牙断连暂停、USB 独占播放、混音播放和预抢占音频焦点。
  长音频进度记忆默认开启，仅对时长不少于 15 分钟的内容保存位置；低于 5 秒的
  位置不会写入，距离结尾 30 秒以内会视为已完成并清零，显式指定的播放位置优先于
  记忆位置。该位置以 `resumePositionMs` 参与本地与可选同步，不代表第三方平台播放历史。
- 🔌 **USB 独占播放**：
  支持 **UAC1.0** 和兼容 **UAC2.0 Type I PCM** 的 USB DAC 设备，支持设备选择、
  采样率/位深/缓冲策略、兼容性开关、32-bit PCM、
  PCM float 到设备格式的软件转换和后台运行提醒；
  插入 USB 设备时是否响应系统 `USB_DEVICE_ATTACHED` 事件可单独关闭，
  用于避免不想接管 DAC 时反复唤起应用；
  跟随歌曲采样率时会先按 USB 描述符尝试源采样率，精确格式不可用且兼容策略开启时，
  再尝试设备上报的兼容采样率；
  对需要异步显式反馈的兼容 UAC2 拓扑，会解析时钟链和反馈端点，
  根据设备反馈调度音频包，并在长调度间隙后重新进入反馈时钟捕获；
  播放启动卡住、Native 传输背压卡住或前后台切换异常时，会通过原地重配置、
  协调式 AudioSink 重建、动态传输扩缩容和软恢复尽量拉回，
  必要时回退到系统播放避免整条链路卡死；播放器会按网络或本地来源选择唤醒策略，
  前后台切换后可恢复 USB 独占，启用比特完美音量时软件增益固定为 0 dB。
  设置页会显示后台行为是否受电池优化或后台权限限制；服务端播放保持时，后台音频锚点
  会根据实际输出路由选择静音或零均值载波，并通过 MediaSession 提供远程音量控制，
  不会把载波当作用户可听内容。
- 💾 **可配置流媒体缓存**：
  使用 `SimpleCache + LRU` 缓存音频，默认上限 **1 GB**，
  支持分别清理音频缓存、图片缓存、下载暂存、分享暂存和平台歌单缓存，
  并可查看分组后的存储占用详情。
- 🛰️ **脱机模式**：
  自动感知系统默认网络承载状态，脱机时停用在线探索和首页远程刷新，
  远程图片只走本地缓存；本地文件、已下载音频、播放缓存、歌单、
  最近播放和播放统计仍可访问。
- ⬇️ **应用内下载与管理**：
  支持多平台音频下载、本地下载列表、任务进度、取消/重试，
  并保存歌词、封面、元数据和音频标签；默认下载并发为 **6**，
  可在设置中调整，最高 **8**。下载队列会持久化，应用重启后可恢复
  未完成任务，已存在的完整下载会直接结算为完成状态；需要显式 Range 的平台传输会按分块
  Range 并支持续传，下载完成后标签写入失败也不会删除已落盘音频。
- 📁 **可迁移下载目录**：
  下载文件默认在应用管理目录，也可通过 SAF 选择自定义目录；
  切换目录时会迁移已有下载，并支持自定义文件名模板。
  出于性能考虑，不建议频繁切换到 SAF 目录。
- 🎵 **本地音频导入与扫描**：
  支持系统 `VIEW / SEND / SEND_MULTIPLE` 的 `audio/*`，
  也支持扫描设备本地音乐、按授权文件夹定向扫描，
  并自动识别附近歌词与封面 sidecar 文件；大批量扫描会先快速预览，
  可按“已有元信息”过滤预览结果，再在后台补全更完整的本地元信息；SAF 空目录结果
  不会误清理已有目录索引。
- 👤 **本地歌手分类与详情**：
  本地歌曲会按展示艺术家自动分组，并识别 `feat.`、`with`、`和/与`、
  顿号、分号和常见斜杠分隔；本地歌手页支持播放全部、多选、
  导出为歌单，以及对在线来源歌曲发起批量下载。
- 🩷 **本地歌单与收藏**：
  内置「我喜欢的音乐」和「本地文件」系统歌单；「本地文件」按「手动添加」
  和「已下载」分类；两类来源独立统计，手动添加的已下载歌曲会同时显示在两个分类中。
  移除手动添加的歌曲只会移出歌单，确认删除已下载歌曲时会一并清理受管下载文件。
  普通本地歌单支持创建、重命名、删除、排序和添加歌曲；
  删除歌单或歌曲后会提供撤销反馈，批量导出到本地歌单时也会确认目标并支持撤销新增内容；
  详情会显示累计播放次数；收藏页提供歌单、歌手和热点分类，热点会按个人歌曲收听记录生成可播放的周榜和月榜。
  「我喜欢的音乐」支持同步可识别歌曲到网易云我喜欢的音乐。
- 🧑‍🎤 **网易云艺术家详情**：
  网易云歌曲可进入艺术家页，查看艺术家信息、热门歌曲与专辑分页，
  并支持关注/取消关注；关注的艺术家会出现在媒体库收藏分类中。
- 🧺 **网易云歌单详情缓存**：
  歌单详情会缓存 header 与曲目列表，二次进入可先展示本地缓存；
  网络失败或解析失败时也能回退到最近一次成功加载的数据。
- ☁️ **GitHub / WebDAV 同步**：
  可选同步本地歌单、收藏歌单、最近播放、歌曲与歌单播放统计和删除记录，
  使用 `WorkManager` 做延迟与周期同步，数据保存在用户自己的远端。
- 📊 **播放统计**：
  按歌曲稳定身份记录播放次数、累计收听时长、首次/最近播放时间和每日统计桶；
  支持按日/周/月/年/总计查看；周/月/年分别采用近 7/30/365 天窗口，并可参与 GitHub/WebDAV 同步；
  歌单打开次数和本地歌单累计/每日播放同样参与按设备分片的同步合并；周/月热点歌单由同步后的歌曲收听统计动态生成，周榜仅收录累计收听不少于 10 分钟的歌曲，月榜门槛为 30 分钟；
  同步合并会保留全量累计和每日桶，旧版 bucket-only 数据也会被提升为可见累计；
  写入采用延迟批量策略，
  在关键生命周期 flush，且每日桶有保留上限。
- 📶 **流量统计与高风险提示**：
  记录播放/下载流量、Wi‑Fi/移动/漫游分布和缓存命中；
  在移动网络或漫游环境下下载时可主动提示风险。
- ♻️ **备份与恢复**：
  支持歌单 JSON 导入/导出；也支持完整配置导入/导出，
  可迁移设置、语言、平台授权、GitHub/WebDAV 配置与一起听设置。
- 🎧 **一起听**：
  支持创建房间或加入他人房间，通过 WebSocket 实时同步播放状态，
  支持房主/听众权限、成员控制开关、新成员加入时可自动暂停、同一成员重连不暂停、显式离开时通知房主、循环/随机模式同步、
  可选共享房主解析的会话播放候选、邀请链接、深链加入、自定义服务端和房主离线检测；首次加入必须
  提供邀请密钥，成员重连使用成员密钥。房主可分别复制邀请信息和邀请密钥；点击加入会从
  剪贴板读取有效邀请，无有效邀请时不会入房。本地歌曲不能建房或替换房间当前曲目；会话候选共享
  开启时，Worker 只公开并缓存当前曲目的房主候选，听众可直接取得缓存，关闭共享会清空缓存；
  当前曲目最多保留 3 条候选，听众先按本机音质策略解析并优先尝试本机候选，本机候选
  无法启动时再在本次会话内依次回退；候选不会写入歌曲或离线缓存。随机模式会提交并
  复用发起端已经生成的真实队列顺序，关闭随机时恢复顺序；队列拖动、添加和删除会以
  房间版本化操作合并，双方同时操作时保留可重放的意图，纯重排不会重载当前歌曲。
  房间会按当前曲目时长推算服务端位置，单曲循环会按时长回绕，模式切换会重新锚定进度；
  控制事件按客户端顺序过滤，`REQUEST_SET_TRACK` 只能选择当前队列歌曲，并继续校验稳定
  曲目键和服务端时钟。
- 🌈 **个性化与主题**：
  支持自动/浅色/深色模式、动态取色、种子色、主题风格、UI 缩放、
  自定义背景图、触感反馈、歌词字号（封面页和歌词页可分别调整）、
  歌词模糊、默认启动页和首页卡片开关；
  Android 13+ 还可按需开启默认关闭的“进阶高级模糊”，增强顶部/底部 Tab
  与设置结构卡片材质；模糊度可在 `12-64 dp` 间调节，父级关闭时会保留
  子级选择和模糊度但停止进阶绘制。模糊质量可独立选择超低、低、默认或高；
  低和超低保持默认覆盖范围，以局部渲染、动态下采样和硬件缓存减轻开销，
  天玑设备在未保存偏好时默认超低，并会在前后台恢复或切换质量后重建缓存。
- ✨ **播放页动效与歌词**：
  支持 `RuntimeShader` / GLSL 流体背景、音频反应式动态背景、封面模糊背景、
  仿 Apple Music 歌词、高级歌词、逐词歌词、翻译歌词、歌词偏移、
  音译显示、歌词长按分享、歌词卡片生成、歌词编辑、歌词字体调节，
  且封面页与歌词页的歌词 / 翻译字号可分别调节，
  歌词触感反馈和 Lyrics 全屏页。封面页逐行歌词与底部 Dock 工具栏可以分别关闭；
  窄屏或高缩放的竖屏会自动切换为紧凑布局，并将操作按钮收缩到稳定的触控槽位。
  歌词分享支持逐行点选和从起点到目标行的长按范围选择。RuntimeShader 动态背景仅在 Android 13+
  启用；封面模糊需要 Android 12+，高级模糊需要 Android 13+，低版本会降级。
- 👆 **迷你播放器手势**：
  底部 Mini Player 支持横向滑动切换上一首/下一首，同时保留点击展开与播放暂停。
- 🧩 **桌面小组件**：
  提供 4x2 播放卡片和 2x2 迷你播放器；卡片背景和主播放键从当前封面取色，
  4x2 显示歌曲、进度、播放/暂停、上一首、下一首和桌面歌词入口，2x2 以封面为主视觉
  并保留三项播放控制。播放时组件本地按秒更新进度，播放暂停和切歌会立即刷新。
- 🚀 **启动器快捷方式**：
  长按桌面图标可继续播放上次队列、打开探索、打开媒体库或随机播放我喜欢的音乐；
  没有可恢复队列或我喜欢的音乐为空时会给出反馈而不是静默失败。
- 🪟 **悬浮歌词与状态栏歌词**：
  支持系统悬浮歌词，颜色、描边、字号、位置、对齐和翻译显示都可自定义；
  也支持魅族状态栏歌词（部分设备可用）和 SuperLyric 输出，
  应用前台可自动隐藏悬浮窗避免遮挡。
- 🔌 **外部歌词/设备联动**：
  支持词幕适配（Lyricon Provider）、SuperLyric、外部蓝牙歌词、
  蓝牙断连暂停和 USB 独占播放开关；外部歌词链路会同步当前歌曲、
  播放状态、进度、逐字歌词和翻译；Lyricon/SuperLyric 进度由独立 200 ms 推送，
  翻译行按时间匹配。蓝牙歌词的原文和翻译可独立开启；同时开启时会分别写入
  标题和艺术家字段，并在专辑/描述字段保留曲目信息。歌词卡片使用缓存，
  歌曲分享支持 Xiaomi Super Island 链接。
- 🛠️ **开发者模式与调试工具**：
  设置页连续点击版本号 **7 次** 后，底栏出现 `Debug` 页，
  包含 YouTube / Bili / Netease / Search / Listen Together 探针、
  普通日志与崩溃日志查看器。
- 🧾 **登录与日志更友好**：
  支持网易云 / Bilibili 二维码登录与网页登录兜底；
  开发者模式外也可开启持久文件日志，便于复现疑难问题。
- 🛟 **安全模式与崩溃日志**：
  上次启动发生 JVM / Native 崩溃或系统 ANR 时，可直接进入安全模式预览或导出日志，
  并按需清理设置、授权信息或崩溃标记。

---

## 平台现状 / Platform Status

- **网易云音乐**：
  登录、歌曲搜索、精选歌单、专辑、歌单/专辑列表搜索、播放、下载、歌词、播放页元数据补全，
  无权限播放兜底、本地收藏同步到网易云我喜欢的音乐、艺术家详情、
  热门歌曲/专辑分页和艺术家关注。
- **Bilibili**：
  Web 登录、二维码登录、视频搜索、创建收藏夹、订阅收藏夹、合集、
  收藏夹/合集列表搜索、分 P 转音频播放、下载；
  链接识别会保留选中的分 P，并能从 `season_id` 或视频详情恢复合集上下文；
  当前不是完整视频发现流或评论区客户端。
- **YouTube Music**：
  登录、首页/媒体库歌单浏览、歌单详情、搜索和播放兼容，
  并包含 PoToken / JS Challenge 相关支持；内容访问仍受平台规则和用户账号权限约束。
- **QQ 音乐**：
  当前仅用于播放页元数据和歌词补全，未实现登录、播放和库页数据。
- **本地音频**：
  支持外部分享/打开导入、设备扫描、授权文件夹扫描、本地文件播放、
  本地歌手分类、分享和本地歌单管理。

---

## 实现概览 / Implementation Notes

### 构建与版本

- `compileSdk = 37`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17
- NDK `27.0.12077973`
- CMake `3.28.0+`
- 版本名格式：`<git短哈希>.<MMddHHmm>`
- Release APK 文件名：`NeriPlayer-<versionName>[-abi].apk`
- 默认 Release 只构建 `arm64-v8a`；多 ABI 构建需加
  `-PbuildAllReleaseAbis=true`
- `.github/workflows/android_native_ci.yml` 会在 Native 相关变更时运行
  Release + `-Werror`、ASan+UBSan、TSan 三组 host CTest，并单独编译
  `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四个 Android ABI。

### 模块结构

- `:app`：主 Android 应用。
- `:ksp-annotations` / `:ksp-processor`：设置项自动登记与生成。
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`：歌词解析与 Compose 歌词 UI 子模块。
- `build-logic`：统一 Gradle convention plugin。
- `buildSrc`：保留的辅助 Gradle 构建逻辑模块。
- `np-submodule/NeriPlayer-LTW`：一起听 Cloudflare Workers 服务端。
- `np-submodule/miuix`：仓库内附带的上游 Miuix 源码/文档树，当前不参与主应用模块构建。

### 入口与导航

- `MainActivity` 是唯一对外入口，同时处理启动流程、通知权限、
  外部音频导入、USB 设备插入事件和 `neriplayer://listen-together/join` 深链。
- 如果上次启动发生 JVM / Native 崩溃或系统 ANR，会优先进入 `Safe Mode`，
  只开放日志预览导出、配置导出、登录重置、设置重置和恢复正常启动。
- 主界面是 **Compose NavHost + 动态底栏**：
  `Home / Explore / Library / Settings` 为主路径。
- `Home` 会根据首页卡片可用性动态显示；`Debug` 仅开发者模式开启后显示。
- 主标签内容由 `MainTabLayerHost` 保留出场/入场双场景并按标签顺序横向切换；
  每个场景独立持有可保存状态和高级玻璃 owner，快速反向切换也会从当前进度继续。
- 详情页默认使用背景轻微下沉、前景自下而上展开的抽屉式反馈；
  开启“连贯反馈”后，背景与详情页改为同步接力移动。
- `Now Playing` 不是普通路由，而是覆盖在主导航之上的全屏播放层，
  底部常驻 `Mini Player`；Mini Player 支持横向滑动切歌。
- 启动器快捷方式由 `LauncherShortcuts` 映射为继续播放、探索、媒体库和随机喜欢，
  只落到主标签或播放服务入口，不会绕过正常导航状态恢复。
- `Library` 使用分页导航组织本地、收藏、网易云、YouTube Music、Bilibili
  和 QQ 音乐占位；同时提供最近播放和播放统计入口。
- 本地媒体库支持歌单/歌手二级分类；收藏页支持歌单/歌手二级分类；
  网易云页支持歌单/专辑二级分类。
- 本地歌手详情页由 `LocalArtistDetailScreen` 承载，支持播放全部、多选、
  导出歌单和批量下载在线歌曲；网易云艺术家详情页由
  `NeteaseArtistDetailScreen` 承载，支持歌曲/专辑分页和关注状态。
- 平板和横屏下，播放页、歌词页、艺术家详情和设置页会限制内容宽度并调整底部操作区，
  避免大屏布局过宽或操作按钮飘散。

### 播放、缓存与服务

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
  `lifecycle/`、`watchdog/` 与 `usb/` 等职责分包；共享歌曲模型位于 `data/model/`，
  旧包名仅保留少量兼容别名，不应作为新增代码入口。
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

### 搜索与数据来源

- **UI 搜索**：
  `Explore` 当前接入网易云、Bilibili 和 YouTube Music，
  采用按平台独立搜索，不混合聚合结果。网易云可切换歌曲/歌单/歌手，
  网易云和 Bilibili 支持触底分页；链接识别支持直接链接和含短链接的分享文案，
  Bilibili 视频链接会保留 `p` / `cid` 指向的分 P，合集分享会优先使用
  视频详情里的 UGC season，必要时回退到链接里的 `season_id`。
  搜索历史保存在本地，可在“设置 > 通用”中独立关闭显示和记录。
- **元数据补全**：
  播放页通过 `SearchManager` 使用网易云与 QQ 音乐补全封面、歌词和曲目信息。
- **歌词来源**：
  除平台歌词外，还包含 LRCLIB 外部歌词客户端；歌词编辑器提供单独的
  “匹配”入口，可修改匹配关键字，先选择平台再搜索，并按平台临时缓存
  搜索结果。切换平台过滤时直接更新已缓存候选，不会频繁重新请求；匹配结果会自动清理
  标题、制作信息等非歌词行；排序按
  歌名、歌手、专辑、时长和歌词类型综合计算，优先逐字歌词但保留普通歌词
  候选。播放页支持原文歌词、翻译歌词、音译歌词、逐词歌词、歌词分享和手动编辑。
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

### 本地数据与安全

- 常规设置使用 `DataStore` 持久化，并通过 KSP 生成设置 key、备份白名单和设置 UI 元数据。
- 主题模式由 `ThemeMode` 管理，支持浅色、深色和跟随系统的 Auto 模式。
- 平台 Cookie、YouTube 授权信息、GitHub Token 与 WebDAV 密码使用
  `Android Keystore + EncryptedSharedPreferences` 本地加密保存。
- 播放历史、播放统计、歌单、收藏快照和部分映射数据使用本地文件持久化。
- 本地歌单使用 JSON 文件存储，并通过临时文件实现原子写入。
- GitHub 与 WebDAV 共用的同步载荷模型位于 `data/sync/model/`，
  封面映射位于 `data/sync/`；GitHub/WebDAV 管理器与传输仍在各自 provider 包，
  现有兼容序列化和多数合并策略继续位于 `sync/github/`。
  删除记录会和撤销操作一起进入合并策略，避免本地撤销后的歌曲在下一轮同步又被旧删除记录移除。
- GitHub/WebDAV 同步使用本地生成的 UUID 作为设备标识，不依赖 `ANDROID_ID`。
- GitHub 同步通过 Git Data API 在用户仓库中创建原始二进制 blob，再以非强制更新提交到默认分支；
  读取使用 raw 内容，API 请求中的 Base64 只属于传输封装，不会改变仓库内保存的正文。
  同步正文上限为 12 MiB，省流模式保存原始 `GZIP(ProtoBuf)`，普通模式保存 UTF-8 JSON。

### 下载、本地导入与备份

- 下载使用共享 `OkHttpClient`，不是系统 `DownloadManager`。
- 默认下载并发为 **6**，可在设置中调整，最高 **8**。
- 下载文件先写入 `cache/download_staging` 下的工作文件，再提交到应用管理目录
  或用户选择的 SAF 目录；正式落盘前会先准备音频元数据，提交后再写歌词、封面、
  `.npmeta.json` 和音频标签。
- `DownloadTaskStore` 会持久化待下载队列和任务状态；
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
  清理缓存只覆盖可再生成的缓存和暂存文件，不会删除用户主动保存的下载歌曲。
- `LocalAudioImportManager` 支持导入外部音频、扫描设备音乐，
  并复制附近的 `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
- 分享本地歌曲时会优先直接分享受控目录 URI；SAF/content URI 无法直接暴露时，
  才复制到 `cache/shared_local_media` 暂存后分享，该目录属于可清理缓存。
- 本地扫描预览支持只看带元信息的歌曲；创建或补充本地歌单后，
  应用会按需在后台继续补全歌曲名、歌手、专辑和封面信息，并尽量保留已编辑的本地元数据。
- 下载的“元信息后处理”可单独开关；关闭后仍会保留下载管理所需的元数据，
  只是不再把标签、歌词和封面写回音频文件。
- `BackupManager` 支持本地歌单 JSON 备份、导入与差异分析。
- `ConfigFileManager` 支持完整配置导入/导出，用于迁移设置、授权和同步配置。

想深入了解实现细节？请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。

---

## 一起听服务端部署 / Listen Together Deployment

NeriPlayer 内置“一起听”功能。你可以快速部署自己的服务端，
也可以使用他人部署的服务。

服务端源码与部署入口：

- 当前仓库内的 `np-submodule/NeriPlayer-LTW`
- 公开部署模板：
  [TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

服务端基于 **Cloudflare Workers** 和 **Durable Objects**，
通过 WebSocket 提供实时同步。

### 一键部署到 Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

应用内可在设置页配置一起听服务端地址、测试可用性，并重置本机一起听身份。

补充说明：

- 房间号固定为 6 位可读字符，昵称长度为 1-24，当前允许中文、英文字母和数字
- 首次加入必须使用邀请链接中的密钥；本地歌曲不能创建一起听房间
- 更完整的协议、事件和部署细节请看
  [np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md)

---

## GitHub 同步 / GitHub Sync

NeriPlayer 支持将本地元数据同步到 **用户自己的 GitHub 仓库**。
应用内新建仓库时默认创建为私有仓库，也支持接入已有仓库。

当前同步对象包括：

- 本地歌单
- 收藏歌单
- 最近播放记录
- 最近播放删除记录
- 播放统计

### 技术细节

- 🔒 **本地安全存储**：GitHub Token 保存在
  `Android Keystore + EncryptedSharedPreferences` 中。
- 🔄 **同步调度**：本地数据变更后触发一次 **延迟 5 秒** 的同步；
  同时存在 **每小时一次** 的周期同步。
- ⏱️ **最终一致性**：这是后台双向同步，不是实时秒级推送。
- 🌐 **网络要求**：同步任务依赖 `WorkManager`，仅在存在
  **validated network** 时执行。
- 🧩 **冲突处理**：同步采用三路合并，处理歌单、收藏、历史、删除记录和播放统计；
  新增或从备份恢复的歌曲会携带成员 token，旧删除只移除它实际观察到的成员，
  避免恢复内容在下一次 GitHub/WebDAV 同步时再次消失。
- 🧹 **远端容错**：读取 JSON/ProtoBuf 同步快照时会兼容缺字段旧数据，
  过滤无可解析歌曲身份或无有效删除时间的异常记录；
  缺失 `addedAt` 的歌曲会排在已有时间的歌曲之后，避免异常快照打乱歌单。
- 🪶 **省流模式**：`backup-raw.bin` 直接写入原始 `GZIP(ProtoBuf)` 字节；读取侧仍兼容
  `backup.json` 和历史 `backup.bin` Base64 格式，关闭省流模式时使用 JSON。JSON、压缩
  正文和解压后正文分别有 8 MiB、12 MiB、16 MiB 的上限。
- GitHub 同步通过 Git Data API 的 blob/tree/commit 非强制更新提交原始正文，按远端分支头
  做并发保护；WebDAV 没有 ETag/Last-Modified 时，
  只有远端 SHA-256 指纹未变化才会回退无条件写入，否则按并发冲突失败。
- 使用省流模式前，应先升级同一仓库的 Android/Desktop 同步终端，使它们都支持原始 GZIP、
  JSON 与历史 Base64 的 read-both 读取；旧客户端可能只能读取历史 Base64 文本。
- 📦 **远端格式**：GitHub 仓库不等于端到端加密，
  远端文件仍由用户自行保管。
- 🚫 **同步边界**：不会上传音频缓存、下载文件、本地音频文件、
  Cookie 或播放 Token。

### 使用方法

1. 打开设置页中的备份与同步。
2. 创建 GitHub Personal Access Token（需要 `repo` 权限）。
3. 在应用内完成 Token 校验，并选择创建默认私有仓库或接入已有仓库。
4. 开启自动同步，或手动点击立即同步。

---

## WebDAV 同步 / WebDAV Sync

除 GitHub 外，NeriPlayer 也支持将同一套同步数据保存到 WebDAV 远端文件。

- 同步对象与 GitHub 同步一致。
- 支持自动同步和手动立即同步。
- 使用 `WorkManager` 做延迟同步、周期同步、网络检查和失败重试。
- WebDAV URL、用户名和密码保存在本地加密存储中。
- WebDAV 优先使用 ETag/Last-Modified 做条件写入；服务器不提供条件令牌时，只有在
  远端 SHA-256 指纹未变化的情况下才允许回退到无条件写入，否则按冲突失败。
- WebDAV 远端文件同样不是端到端加密备份。

---

## 发展规划 / Roadmap

### 方向探索

这些方向会根据维护精力、平台可用性和社区反馈调整，不承诺固定周期。

- [ ] 视频播放
- [ ] 评论区
- [ ] 第三方平台播放、库页和账号能力持续扩展
- [ ] 更完整的 QQ 音乐账号能力、库页数据与更稳定授权链路

### 近期已落地

- [x] 主标签双场景横向转场、可打断反向切换、玻璃 owner 接力和默认抽屉式详情反馈
- [x] 标准化 Snackbar 反馈覆盖层、歌单删除撤销和批量导出撤销
- [x] 桌面小组件与启动器快捷方式
- [x] Bilibili 分 P、合集分享和 `season_id` 链接识别
- [x] 日语假名歌词的翻译行间距优化
- [x] 编辑歌词时选择平台手动匹配歌词，结果临时缓存并优先展示逐字候选
- [x] 一起听循环/随机模式同步、服务端时钟偏移估算与权威会话候选恢复
- [x] 一起听按曲目时长推算位置、单曲循环位置回绕和最多三条会话候选
- [x] 一起听邀请/成员密钥、控制事件顺序过滤和队列内曲目选择约束
- [x] 32-bit 高解析普通输出、PCM float 声道平衡和响度均衡线程安全状态
- [x] USB 独占前后台恢复、播放唤醒策略和比特完美音量设置
- [x] USB 独占 UAC2 显式反馈、长调度间隙重捕获、协调式 AudioSink 重配和 Runtime Report v2
- [x] Native USB 三组 host sanitizer/警告门禁与四 ABI Android 编译 CI
- [x] USB 独占 32-bit PCM、UAC2.0 Type I PCM 兼容路径、原地重配置、动态传输扩缩容和背压卡顿恢复
- [x] GitHub/WebDAV 异常同步快照、缺字段歌曲和删除记录兼容清洗
- [x] GitHub/WebDAV JSON、原始 GZIP 与历史 Base64 同步格式兼容读取
- [x] 播放器、下载存储、启动流程、歌词组件和一起听客户端按职责拆分子包
- [x] 本地歌单显示顺序版本化及 GitHub/WebDAV 旧同步数据兼容迁移
- [x] USB 独占播放的设备选择、质量策略、后台运行提醒和多层自动恢复
- [x] 本地扫描快速预览、后台元信息补全与封面回退
- [x] 本地扫描按已有元信息过滤预览，以及长音频进度记忆与完成清零
- [x] BilibiliSponsorBlock 可选自动跳过和 Now Playing 紧凑布局/歌词范围选择
- [x] 一起听会话候选共享开关、异步播放候选解析和更稳的房间同步
- [x] 歌词长按选择、复制、歌曲分享和歌词卡片生成
- [x] 歌词音译显示与歌词行为面板
- [x] Lyricon/SuperLyric 独立 200 ms 进度推送、歌词翻译匹配和 Super Island 分享链接
- [x] Mini Player 横向滑动切换上一首/下一首
- [x] 网易云歌单详情缓存与网络失败回退
- [x] 存储占用分组分析和扩展缓存清理
- [x] 启动时恢复陈旧下载队列、已完成下载快速结算和 SAF 索引性能优化
- [x] 媒体库重新设计与本地/收藏/网易云二级分类
- [x] 本地艺术家自动分类与本地艺术家详情页
- [x] 网易云艺术家详情、热门歌曲/专辑分页和艺术家关注
- [x] 播放统计日/周/月/年/总计周期视图
- [x] 播放统计滚动窗口、同步累计和旧版 bucket-only 数据兼容合并
- [x] 网易云与 Bilibili 二维码登录
- [x] 可配置下载并发、下载恢复和落盘可靠性增强
- [x] 标准化歌词嵌入设置
- [x] 自动主题模式、主题设置重构和深色模式检测优化
- [x] 歌词定位触感反馈
- [x] 预抢占音频焦点设置
- [x] 悬浮歌词、状态栏歌词与 SuperLyric 输出
- [x] 清理缓存
- [x] 添加到播放列表
- [x] 平板/横屏播放页适配
- [x] 国际化
- [x] 网易云音乐适配
- [x] Bilibili 适配
- [x] YouTube Music 基础适配
- [x] YouTube Music 搜索能力
- [x] YouTube 匿名播放会话、Cookie 轮换保护、PoToken/EJS/bootstrap 缓存和播放候选/HLS 回退
- [x] WebDAV 同步
- [x] 播放统计
- [x] 播放音效
- [x] 网易云无权限播放兜底
- [x] 词幕适配（Lyricon）/ 外部歌词输出
- [x] 安全模式与启动崩溃日志

> ⚠️ 当前 QQ 音乐主要用于播放页元数据补全。
> 完整账号能力、库页数据与更稳定的授权链路仍在开发中。

---

感谢使用 NeriPlayer。由于项目功能较多且用户运行环境复杂，
可能会出现符合预期的差异或异常情况。若您在运行中遇到任何问题，
欢迎随时提交反馈，我们将持续优化。

---

## 问题反馈 / Bug Report

- 反馈前建议先开启开发者模式（设置页点击 **版本号** 7 次）。
- 开发者模式开启后，应用会启用普通文件日志；崩溃日志会单独落盘。
- 前往 [Issues](https://github.com/cwuom/NeriPlayer/issues)，提供：
  系统版本、机型、应用版本、复现步骤与关键日志。
- Windows 可使用以下命令过滤日志：
  ```bash
  adb logcat | findstr NeriPlayer
  ```
- Linux / macOS 可使用：
  ```bash
  adb logcat | grep NeriPlayer
  ```

---

## 已知问题 / Known Issues

### 网络

- 请合理配置代理规则；全局代理可能导致部分第三方接口返回异常数据。

### 能力边界

- 下载功能当前不依赖系统下载服务；已支持自动断点续传与启动恢复，
  但仍不是系统级后台下载器，也没有做跨设备同步。
- 手动取消下载会清理断点和半成品；只有网络策略暂停或可恢复错误才保留续传状态。
- 自定义 SAF 下载目录便于外部访问文件，但目录扫描、迁移和落盘通常比应用私有目录更慢。
- USB 独占依赖兼容的 **UAC1.0** 或 **UAC2.0 Type I PCM** USB DAC、
  前台服务和系统后台策略；如果息屏后被系统限制，请按设置页提示放开电池/后台限制。
- UAC2 异步链路目前只接受能解析出唯一显式反馈端点和受支持时钟拓扑的设备；
  隐式反馈或无法验证的拓扑会拒绝 Native 候选并走兼容回退。
- 显式反馈路径已覆盖 host 模型测试与 Android 四 ABI 编译，
  但这些结果不等同于具体手机/DAC 组合的真实设备稳定性验证。
- 32-bit 高解析普通输出只面向普通系统输出的高精度音源，会旁路部分应用内音频处理；
  需要响度均衡、声道平衡、音频可视化或应用内倍速时，请保持该开关关闭。
- 歌词音译显示依赖平台或嵌入歌词中存在音译数据；没有音译时开关会保持不可用。
- 歌词卡片会写入应用缓存目录用于系统分享，后续可通过缓存清理释放。
- Bilibili 当前主要提供视频搜索、收藏夹、合集和音频播放链路，不是完整视频发现流。
- QQ 音乐当前仅作为播放页元数据/歌词补全源。
- GitHub/WebDAV 同步不是端到端加密；完整配置导出文件可能包含授权信息，
  请自行妥善保管。
- 省流同步写入 `backup-raw.bin` 原始 GZIP 字节；新版在迁移期间同时保留
  `backup.json` 与历史 `backup.bin` Base64 的读取兼容。
- GitHub 同步通过仓库 Git Data API 提交二进制正文；并发分支更新失败时会报告冲突，
  不会强制覆盖远端提交。
- 长音频进度记忆不覆盖显式播放位置，也不会代替第三方平台的播放历史；
  BilibiliSponsorBlock 需要访问公开接口，且一起听期间保持关闭。

---

## 隐私与数据 / Privacy

- NeriPlayer 不提供自己的公共云端媒体分发服务，也不接入广告 SDK、
  第三方统计或崩溃分析 SDK。
- 项目采用去中心化的数据策略：同步目标由用户自己选择和保管，
  不会把个人媒体数据汇聚到维护者控制的中心化平台。
- 播放缓存、下载文件、本地歌单、历史记录、播放统计、设置与授权信息默认保存在
  用户设备本地。
- 如用户主动开启 GitHub 或 WebDAV 同步，仅会同步歌单、收藏、历史和播放统计等元数据。
- 不会将音频缓存、下载文件、Cookie、播放 Token 上传给开发者。
- 出于账号安全考虑，应用不会把本地播放历史或播放统计回写到第三方音乐平台；
  这类上报可能被平台风控误判为异常行为。
- 完整配置导出文件会包含设置、授权信息和同步配置，适合自用迁移，
  不应公开分享。
- 默认关闭 Android 系统云备份 / 设备迁移。
- 第三方平台侧的访问日志与风控策略，由对应平台按照其自身隐私政策处理。

---

## 鸣谢 / Reference

<table>
<tr>
  <td><a href="https://github.com/chaunsin/netease-cloud-music">netease-cloud-music</a></td>
  <td>✨ 网易云音乐 Golang 实现 🎵</td>
</tr>
<tr>
  <td><a href="https://github.com/SocialSisterYi/bilibili-API-collect">bilibili-API-collect</a></td>
  <td>哔哩哔哩 API 收集整理</td>
</tr>
<tr>
  <td><a href="https://github.com/yt-dlp/ejs">ejs</a></td>
  <td>External JavaScript for yt-dlp supporting many runtimes</td>
</tr>
<tr>
  <td><a href="https://github.com/6xingyv/accompanist-lyrics-core">accompanist-lyrics-core</a></td>
  <td>A lyrics parsing, converting, exporting library for Kotlin</td>
</tr>
<tr>
  <td><a href="https://github.com/6xingyv/accompanist-lyrics-ui">accompanist-lyrics-ui</a></td>
  <td>The state-of-the-art karaoke lyrics composable</td>
</tr>
<tr>
  <td><a href="https://github.com/chenmozhijin/LDDC">LDDC</a></td>
  <td>多平台精准歌词下载匹配工具，手动歌词匹配体验参考</td>
</tr>
<tr>
  <td><a href="https://github.com/MetrolistGroup/Metrolist">Metrolist</a></td>
  <td>YouTube Music client for Android</td>
</tr>
<tr>
  <td><a href="https://github.com/ReChronoRain/HyperCeiler">HyperCeiler</a></td>
  <td>HyperOS enhancement module - Make HyperOS Great Again!</td>
</tr>
</table>

---

## 更新周期 / Update Cycle

- 项目处于持续迭代中，Release 通常按功能批次手动发布。
- 核心播放、本地数据、同步与恢复链路会优先维护。
- 第三方平台能力可能受平台策略影响，欢迎提交 Issue、PR 或复现日志。

---

## 支持方式 / Support

- 由于项目特殊性，暂不接受任何形式的捐赠。
- 欢迎通过提交 Issue、PR 或分享使用体验来支持项目发展。

---

## 许可证 / License

NeriPlayer 使用 **GPL-3.0** 开源许可证发布。

这意味着：

- ✅ 你可以自由使用、修改和分发本软件。
- ⚠️ 按根目录 GPL-3.0 分发修改版时，须继续遵守 GPL-3.0。
- 🧩 `app/src/main/cpp/README.md` 仅为其中列出的 NeriPlayer 自有 Native 源码
  提供附带署名条件的替代授权；第三方源码和未列入范围的仓库内容不适用。
- ✍️ 外部 Native 贡献不会因提交 PR 自动进入替代授权范围，
  只有贡献者明确记录双授权同意时才适用。
- 📚 详细条款请参阅 [LICENSE](./LICENSE)。

---

# Contributing to NeriPlayer / 贡献指南

贡献前请先阅读完整的 [CONTRIBUTING.md](./CONTRIBUTING.md)。

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="访问计数 (Moe Counter)">
  <br/>
  <a href="https://starchart.cc/cwuom/NeriPlayer">
    <img src="https://starchart.cc/cwuom/NeriPlayer.svg" alt="Star 历史趋势图">
  </a>
</p>
