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
>
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

---

> [!NOTE]
> NeriPlayer 不提供公共云端曲库或媒体分发服务。
> 在线音频能力依赖用户在第三方平台上的账号授权，
> 会员或受限内容仍需遵循原平台规则。

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
├── 丰富播放体验：Media3、歌词、音效、流体背景、悬浮/状态栏歌词
└── 可恢复运行：安全模式、崩溃日志、ANR 记录、调试探针
```

---

## 项目简介 / About

NeriPlayer 是一个基于 **Jetpack Compose + Media3** 的原生 Android
音频播放器。它不构建公共云端服务，而是在用户具备第三方平台账号能力的前提下，
整合 **网易云音乐**、**Bilibili** 与 **YouTube Music** 的在线内容，
并提供本地播放、下载、缓存、歌单管理和多种同步/备份能力。

当前定位：

- **账号即能力**：通过第三方平台授权启用搜索、播放、歌单和收藏夹访问。
- **本地优先**：播放缓存、下载文件、歌单、历史记录、设置与授权信息默认保存在设备本地。
- **可选同步**：可将歌单、收藏、最近播放和播放统计同步到用户自己的
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
- **测试护栏**：下载存储、同步合并、YouTube 取流、一起听、歌词解析、
  播放策略、配置备份与安全模式等关键链路都有对应单元测试或设备测试。

---

## 项目亮点 / Why it stands out

- **本地优先，也认真做脱机体验**：
  `NetworkStatusMonitor` 基于系统网络校验自动识别脱机状态，
  `offlineCachedImageRequest` 会在脱机时阻断远程图片请求并优先使用缓存；
  首页、探索、播放页、歌词页、歌单和下载列表都会接收 `offlineMode`，
  网络断开时仍能围绕本地文件、已下载音频、播放缓存、缓存封面和本地歌单继续使用。
- **多源播放不是简单入口堆叠**：
  `PlayerManager` 负责音源解析、队列和失败恢复；网易云不可播、无直链或
  只返回试听片段时，会先尝试音质降级，再由
  `PlayerManagerNeteaseAutoSourceSwitch` 按歌名、歌手和时长评分自动匹配
  Bilibili 音源兜底；播放异常时还会刷新当前链接，连续失败再跳过或停止。
- **GLSL/AGSL 高性能流体背景**：
  播放页动态背景由 `BgEffectPainter` 加载
  `assets/hyper_background_effect.glsl` 并通过 `RuntimeShader` 逐帧渲染；
  shader 内部基于封面取色、动态色块和轻量颗粒噪声生成流体背景，
  并接入 `uMusicLevel / uBeat` 做音频响应，不是简单把封面做高斯模糊。
- **仿 Apple Music 的深度歌词体验**：
  `AdvancedLyricsView` 支持逐词/逐字高亮、翻译歌词、
  音译显示、歌词偏移、点击跳转、长按分享、景深模糊、边缘渐隐和全屏歌词；
  `LyricShareSheet` 可选择歌词行，复制文本、分享歌曲或生成 1080px 歌词卡片；
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
  `Mini Player` 支持横向滑动切到上一首/下一首，常用播放控制不用每次展开全屏。
- **听感也能细调**：
  `PlaybackEffectsController` 将倍速、音调、`Equalizer` 和
  `LoudnessEnhancer` 绑定到当前 Media3 音频会话；
  内置多种均衡器预设，也支持手动频段、响度增强、淡入淡出、
  交叉淡入淡出、蓝牙断连暂停、USB 独占播放和音频焦点策略。
  USB 独占会由 native 驱动接管完整 **UAC1.0** 音频功能，避免系统音和其他应用共用 USB 通道；
  目前还**只支持 UAC1.0** 设备，设备选择、采样率/位深/缓冲策略、后台运行提醒，以及启动看门狗、
  前后台健康审计和卡死自动恢复也都补齐了。
- **下载链路已从“能下”进化到“能恢复”**：
  下载不走系统 `DownloadManager`，而是用共享 `OkHttpClient`、
  可调并发、工作文件和 sidecar 元数据管理完整落盘流程；
  直链、YouTube Range 分块和 HLS 都有对应续传策略，网络策略暂停后可继续，
  启动时会恢复未完成队列，已落盘的缓存命中会直接结算，
  手动取消则会清理半成品，语义边界很清楚。
- **存储占用能看清，也能有边界地清理**：
  `StorageUsageAnalyzer` 会把音频缓存、图片缓存、下载暂存、分享暂存、
  平台歌单缓存、下载内容、日志、崩溃报告和核心应用数据分组统计；
  清缓存支持只清可再生成的缓存，不会把用户主动下载的歌曲当作缓存删掉。
- **去中心化同步与播放统计**：
  NeriPlayer 不提供公共云端曲库或开发者托管账号数据；
  GitHub/WebDAV 同步只在用户自己的远端保存歌单、收藏、最近播放和
  播放统计等元数据。`PlaybackStatsRepository` 按歌曲稳定身份记录播放次数、
  收听时长、最近播放和每日桶，并参与同步合并。
- **流量管理不是事后看数字**：
  `TrafficStatsRepository` 会区分播放流量、下载流量、Wi‑Fi、移动网络、
  漫游和缓存命中；批量或单曲下载在高风险网络下还能主动弹出提示，
  避免把“省流量”只做成设置页里的摆设。
- **高个性化，不只是换主题色**：
  设置 schema 由 `AutoSettingsSchema` 管理，覆盖动态取色、种子色、
  主题风格、自动/浅色/深色模式、UI 缩放、自定义背景、歌词字号、
  歌词模糊、播放页流体背景、首页卡片开关、默认启动页、触感反馈，
  以及歌曲自定义名称、歌手和封面等细粒度选项。
- **ANR、崩溃和安全模式都纳入诊断闭环**：
  `AnrWatchdog` 会读取 Android `ApplicationExitInfo.REASON_ANR` 并保存系统
  ANR trace；`ExceptionHandler` 与 `NativeCrashHandler` 分别记录 JVM 和
  Native 崩溃。上次启动异常时，`SafeModeManager` 可跳过完整应用初始化，
  直接进入安全模式预览、复制或导出日志。
- **一起听不是“同步一个进度条”这么简单**：
  客户端和 Cloudflare Worker 共同维护房间、角色、队列、播放状态、
  房主离线恢复、成员控制请求、播放链接共享、版本门控更新和自定义服务端地址；
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
5. 首次启动时先阅读免责声明并完成启动引导；Android 13+ 会申请通知权限。
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
  首页支持最近常用歌单、推荐卡片；国际化模式下优先展示 YouTube Music
  首页歌单与歌曲货架。
- 🗂️ **媒体库分类浏览**：
  `Library` 提供本地、收藏、网易云、YouTube Music、Bilibili 等入口；
  本地页支持歌单/歌手切换、搜索、歌手排序，收藏页支持歌单/歌手切换，
  网易云页支持歌单/专辑切换，Bilibili 页区分创建收藏夹、订阅收藏夹和合集。
- 🔍 **分层搜索能力**：
  `Explore` 使用网易云 / Bilibili / YouTube Music 按平台独立搜索；
  播放页元数据补全使用网易云 / QQ 音乐，并接入 LRCLIB 外部歌词来源。
- 🧠 **Media3 播放核心**：
  `PlayerManager` 管理音源解析、队列、随机/循环、状态恢复、失败重试、
  播放链接刷新、YouTube 预取与平台特殊请求策略。
- 🔁 **网易云自动换源**：
  网易云歌曲无权限、无可用直链或仅返回试听片段时，会先尝试降低音质，
  再按歌曲名、歌手和时长匹配 Bilibili 音源兜底。
- 🧯 **播放失败兜底**：
  播放异常时优先刷新当前播放链接；Bilibili 取流支持 DASH 音频重试和
  html5/mp4 渐进流回退，连续失败时自动跳过或停止避免卡死。
- 🎚️ **播放音效**：
  Now Playing 内置倍速、音调、响度增强和系统均衡器预设/手动频段调节。
- 🎛️ **细粒度播放行为**：
  支持保留上次播放进度、恢复播放模式、淡入淡出、切歌交叉淡入淡出、
  蓝牙断连暂停、USB 独占播放、混音播放和预抢占音频焦点。
- 🔌 **USB 独占播放**：
  目前只支持 **UAC1.0** 的 USB DAC 设备，支持设备选择、采样率/位深/缓冲策略、
  兼容性开关和后台运行提醒；播放启动卡住、Native 传输卡住或前后台切换异常时，会尽量自动恢复，
  必要时回退到系统播放避免整条链路卡死。
- 💾 **可配置流媒体缓存**：
  使用 `SimpleCache + LRU` 缓存音频，默认上限 **1 GB**，
  支持分别清理音频缓存、图片缓存、下载暂存、分享暂存和平台歌单缓存，
  并可查看分组后的存储占用详情。
- 🛰️ **脱机模式**：
  自动感知网络可用性，脱机时停用在线探索和首页远程刷新，
  远程图片只走本地缓存；本地文件、已下载音频、播放缓存、歌单、
  最近播放和播放统计仍可访问。
- ⬇️ **应用内下载与管理**：
  支持多平台音频下载、本地下载列表、任务进度、取消/重试，
  并保存歌词、封面、元数据和音频标签；默认下载并发为 **6**，
  可在设置中调整，最高 **8**。下载队列会持久化，应用重启后可恢复
  未完成任务，已存在的完整下载会直接结算为完成状态。
- 📁 **可迁移下载目录**：
  下载文件默认在应用管理目录，也可通过 SAF 选择自定义目录；
  切换目录时会迁移已有下载，并支持自定义文件名模板。
  出于性能考虑，不建议频繁切换到 SAF 目录。
- 🎵 **本地音频导入与扫描**：
  支持系统 `VIEW / SEND / SEND_MULTIPLE` 的 `audio/*`，
  也支持扫描设备本地音乐、按授权文件夹定向扫描，
  并自动识别附近歌词与封面 sidecar 文件；大批量扫描会先快速预览，
  再在后台补全更完整的本地元信息。
- 👤 **本地歌手分类与详情**：
  本地歌曲会按展示艺术家自动分组，并识别 `feat.`、`with`、`和/与`、
  顿号、分号和常见斜杠分隔；本地歌手页支持播放全部、多选、
  导出为歌单，以及对在线来源歌曲发起批量下载。
- 🩷 **本地歌单与收藏**：
  内置「我喜欢的音乐」和「本地文件」系统歌单，普通本地歌单支持创建、
  重命名、删除、排序和添加歌曲；「我喜欢的音乐」支持同步可识别歌曲到
  网易云我喜欢的音乐。
- 🧑‍🎤 **网易云艺术家详情**：
  网易云歌曲可进入艺术家页，查看艺术家信息、热门歌曲与专辑分页，
  并支持关注/取消关注；关注的艺术家会出现在媒体库收藏分类中。
- 🧺 **网易云歌单详情缓存**：
  歌单详情会缓存 header 与曲目列表，二次进入可先展示本地缓存；
  网络失败或解析失败时也能回退到最近一次成功加载的数据。
- ☁️ **GitHub / WebDAV 同步**：
  可选同步本地歌单、收藏歌单、最近播放、播放统计和删除记录，
  使用 `WorkManager` 做延迟与周期同步，数据保存在用户自己的远端。
- 📊 **播放统计**：
  按歌曲稳定身份记录播放次数、累计收听时长、首次/最近播放时间和每日统计桶；
  支持按日/周/月/年/总计查看，并可参与 GitHub/WebDAV 同步。
- 📶 **流量统计与高风险提示**：
  记录播放/下载流量、Wi‑Fi/移动/漫游分布和缓存命中；
  在移动网络或漫游环境下下载时可主动提示风险。
- ♻️ **备份与恢复**：
  支持歌单 JSON 导入/导出；也支持完整配置导入/导出，
  可迁移设置、语言、平台授权、GitHub/WebDAV 配置与一起听设置。
- 🎧 **一起听**：
  支持创建房间或加入他人房间，通过 WebSocket 实时同步播放状态，
  支持房主/听众权限、成员控制开关、成员进出自动暂停、
  可选共享房主解析直链、邀请链接、深链加入、自定义服务端和房主离线检测。
- 🌈 **个性化与主题**：
  支持自动/浅色/深色模式、动态取色、种子色、主题风格、UI 缩放、
  自定义背景图、触感反馈、歌词字号、歌词模糊、默认启动页和首页卡片开关。
- ✨ **播放页动效与歌词**：
  支持 `RuntimeShader` / GLSL 流体背景、音频反应式动态背景、封面模糊背景、
  仿 Apple Music 歌词、高级歌词、逐词歌词、翻译歌词、歌词偏移、
  音译显示、歌词长按分享、歌词卡片生成、歌词编辑、歌词字体调节、
  歌词触感反馈和 Lyrics 全屏页。
- 👆 **迷你播放器手势**：
  底部 Mini Player 支持横向滑动切换上一首/下一首，同时保留点击展开与播放暂停。
- 🪟 **悬浮歌词与状态栏歌词**：
  支持系统悬浮歌词，颜色、描边、字号、位置、对齐和翻译显示都可自定义；
  也支持魅族状态栏歌词（部分设备可用）和 SuperLyric 输出，
  应用前台可自动隐藏悬浮窗避免遮挡。
- 🔌 **外部歌词/设备联动**：
  支持词幕适配（Lyricon Provider）、SuperLyric、外部蓝牙歌词、
  蓝牙断连暂停和 USB 独占播放开关；外部歌词链路会同步当前歌曲、
  播放状态、进度、逐字歌词和翻译。
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
  无权限自动换源、本地收藏同步到网易云我喜欢的音乐、艺术家详情、
  热门歌曲/专辑分页和艺术家关注。
- **Bilibili**：
  Web 登录、二维码登录、视频搜索、创建收藏夹、订阅收藏夹、合集、
  收藏夹/合集列表搜索、分 P 转音频播放、下载；
  当前不是完整视频发现流或评论区客户端。
- **YouTube Music**：
  登录、首页/媒体库歌单浏览、歌单详情、搜索、播放、下载，
  并包含 PoToken / JS Challenge 相关支持。
- **QQ 音乐**：
  当前仅用于播放页元数据和歌词补全，未实现登录、播放和库页数据。
- **本地音频**：
  支持外部分享/打开导入、设备扫描、授权文件夹扫描、本地文件播放、
  本地歌手分类、分享和本地歌单管理。

---

## 实现概览 / Implementation Notes

### 构建与版本

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17
- NDK `27.0.12077973`
- CMake `3.22.1`
- 版本名格式：`<git短哈希>.<MMddHHmm>`
- Release APK 文件名：`NeriPlayer-<versionName>[-abi].apk`
- 默认 Release 只构建 `arm64-v8a`；多 ABI 构建需加
  `-PbuildAllReleaseAbis=true`

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
  外部音频导入和 `neriplayer://listen-together/join` 深链。
- 如果上次启动发生 JVM / Native 崩溃或系统 ANR，会优先进入 `Safe Mode`，
  只开放日志预览导出、配置导出、登录重置、设置重置和恢复正常启动。
- 主界面是 **Compose NavHost + 动态底栏**：
  `Home / Explore / Library / Settings` 为主路径。
- `Home` 会根据首页卡片可用性动态显示；`Debug` 仅开发者模式开启后显示。
- `Now Playing` 不是普通路由，而是覆盖在主导航之上的全屏播放层，
  底部常驻 `Mini Player`；Mini Player 支持横向滑动切歌。
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
- YouTube Music 播放包含 Google Video Range 支持、Seek 刷新策略和预取逻辑。
- 网易云播放会在无权限、无可用直链或试听片段场景下自动降级音质；
  仍不可播时可根据设置自动搜索并切换到 Bilibili 音源。
- 播放状态会定期持久化，用于进程重启后的队列和状态恢复。
- 睡眠定时器、淡入淡出、切歌交叉淡入淡出、播放模式恢复等均由播放器层管理。
- 预抢占音频焦点、混音播放、蓝牙断连暂停和 USB 独占播放通过播放偏好快照
  在播放器启动早期生效。
- USB 独占目前只覆盖 **UAC1.0** 设备，支持设备选择、采样率/位深/缓冲策略、
  兼容性开关和后台缓冲区设置；为了减少卡住，播放器层还包含启动看门狗、
  前后台健康审计、keep-alive 检查、
  Native 传输恢复，以及必要时的系统播放回退。

### 搜索与数据来源

- **UI 搜索**：
  `Explore` 当前接入网易云、Bilibili 和 YouTube Music，
  采用按平台独立搜索，不混合聚合结果。
- **元数据补全**：
  播放页通过 `SearchManager` 使用网易云与 QQ 音乐补全封面、歌词和曲目信息。
- **歌词来源**：
  除平台歌词外，还包含 LRCLIB 外部歌词客户端；
  播放页支持原文歌词、翻译歌词、音译歌词、逐词歌词、歌词分享和手动编辑。
- **词幕适配**：
  `LyriconManager` 向 Lyricon 与 SuperLyric 输出当前歌曲、播放状态、进度、
  逐字歌词与翻译歌词；状态栏歌词依赖厂商能力，当前面向部分支持设备。
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
- GitHub/WebDAV 同步使用本地生成的 UUID 作为设备标识，不依赖 `ANDROID_ID`。

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
  避免重复拉流和重复 SAF 探测。
- 下载支持**自动断点续传**，但按传输类型分别实现：
  - **直链下载**：优先读取已有工作文件大小并追加 `Range: bytes=<offset>-`
  - **分块 Range 下载**：按偏移续传，主要用于需要显式 Range 的 YouTube 取流
  - **HLS 下载**：记录 segment 索引和已下载字节数，通过 `.hls.json` 检查点恢复
- 工作文件会同时保存 `.resume.json` 恢复元数据，用来在应用重启后重建待恢复任务；
  `GlobalDownloadManager` 启动时会自动扫描并恢复未完成下载。
- 网络波动重试时会尽量保留已下载部分；因为 Wi‑Fi 断开而进入
  `WAITING_NETWORK` 时，也会保留断点并在网络恢复或用户确认后继续。
- 手动取消则会清理工作文件，并回滚已写入的半成品音频与 sidecar，
  不是“暂停后随时继续”的语义。
- 下载目录索引会维护快照缓存和 sidecar 引用，减少 SAF 目录遍历；
  但 Android 的 SAF 访问仍明显慢于应用私有目录，只有确实需要外部目录时才建议切换。
- `StorageUsageAnalyzer` 会按可清理缓存、下载内容、诊断文件和应用数据分组统计占用；
  清理缓存只覆盖可再生成的缓存和暂存文件，不会删除用户主动保存的下载歌曲。
- `LocalAudioImportManager` 支持导入外部音频、扫描设备音乐，
  并复制附近的 `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
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
- 🧩 **冲突处理**：同步采用三路合并，处理歌单、收藏、历史、删除记录和播放统计。
- 🪶 **省流模式**：可使用 `ProtoBuf + GZIP` 的 `backup.bin`；
  关闭省流模式时使用 JSON。
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
- WebDAV 远端文件同样不是端到端加密备份。

---

## 发展规划 / Roadmap

### 方向探索

这些方向会根据维护精力、平台可用性和社区反馈调整，不承诺固定周期。

- [ ] 视频播放
- [ ] 评论区
- [ ] 第三方平台持续扩展（酷狗音乐等）
- [ ] 更完整的 QQ 音乐账号能力、库页数据与更稳定授权链路

### 近期已落地

- [x] USB 独占播放的设备选择、质量策略、后台运行提醒和多层自动恢复
- [x] 本地扫描快速预览、后台元信息补全与封面回退
- [x] 一起听直链共享开关、异步直链解析和更稳的房间同步
- [x] 歌词长按选择、复制、歌曲分享和歌词卡片生成
- [x] 歌词音译显示与歌词行为面板
- [x] Mini Player 横向滑动切换上一首/下一首
- [x] 网易云歌单详情缓存与网络失败回退
- [x] 存储占用分组分析和扩展缓存清理
- [x] 启动时恢复陈旧下载队列、已完成下载快速结算和 SAF 索引性能优化
- [x] 媒体库重新设计与本地/收藏/网易云二级分类
- [x] 本地艺术家自动分类与本地艺术家详情页
- [x] 网易云艺术家详情、热门歌曲/专辑分页和艺术家关注
- [x] 播放统计日/周/月/年/总计周期视图
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
- [x] WebDAV 同步
- [x] 播放统计
- [x] 播放音效
- [x] 网易云无权限自动换源
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
- USB 独占依赖兼容的 **UAC1.0** USB DAC、前台服务和系统后台策略；
  如果息屏后被系统限制，请按设置页提示放开电池/后台限制。
- 歌词音译显示依赖平台或嵌入歌词中存在音译数据；没有音译时开关会保持不可用。
- 歌词卡片会写入应用缓存目录用于系统分享，后续可通过缓存清理释放。
- Bilibili 当前主要提供视频搜索、收藏夹、合集和音频播放链路，不是完整视频发现流。
- QQ 音乐当前仅作为播放页元数据/歌词补全源。
- GitHub/WebDAV 同步不是端到端加密；完整配置导出文件可能包含授权信息，
  请自行妥善保管。

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
- ⚠️ 分发修改版时须继续以 GPL-3.0 协议开源。
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
