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
  <img src="icon/readme-banner.svg" width="100%" alt="NeriPlayer — Modern Music Player" />
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
> NeriPlayer 不提供公共云端曲库或媒体分发服务。在线音频能力依赖用户在第三方平台上的账号授权，
> 会员或受限内容仍需遵循原平台规则。文档中提到的 YouTube Music 相关能力仅指账号会话、
> 播放兼容和错误恢复，不代表绕过平台限制、复制受保护内容或重新分发媒体。
>
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

---

## 快速定位 / Start here

- 只想体验应用：[快速体验](#快速体验--getting-started)
- 想了解项目能力：[核心特性](#核心特性--key-features) 与 [平台现状](#平台现状--platform-status)
- 想自建一起听服务端：[一起听服务端部署](#一起听服务端部署--listen-together-deployment)
- 想了解实现细节：[docs/implementation.md](./docs/implementation.md)
- 准备贡献代码：[CONTRIBUTING.md](./CONTRIBUTING.md)

```text
NeriPlayer
├── 多源在线播放：网易云 / Bilibili / YouTube Music
├── 本地优先数据：缓存、下载、歌单、历史、统计、设置
├── 可选自有同步：GitHub / WebDAV 元数据同步
├── 丰富播放体验：Media3、歌词、音效、流体背景、桌面小组件、启动器快捷方式、悬浮/状态栏歌词
└── 可恢复运行：安全模式、崩溃日志、ANR 记录、调试探针
```

## 项目简介 / About

NeriPlayer 是一个基于 **Jetpack Compose + Media3** 的原生 Android 音频播放器。
它不构建公共云端服务，而是在用户具备第三方平台账号能力的前提下，整合
**网易云音乐**、**Bilibili** 与 **YouTube Music** 的在线内容，
并提供本地播放、下载、缓存、歌单管理和多种同步/备份能力。

当前定位：

- **账号即能力**：通过第三方平台授权启用搜索、播放、歌单和收藏夹访问。
- **本地优先**：缓存、下载文件、歌单、历史记录、设置与授权信息默认保存在设备本地。
- **可选同步**：歌单、收藏、最近播放与播放统计可同步到用户自己的 GitHub 仓库
  （应用内新建时默认私有）或 WebDAV 远端文件。
- **去中心化且尊重账号安全**：数据写入用户自己控制的远端，而不是维护者的中心化服务。
  第三方平台通常存在风控与行为采样机制，直接回写本地播放历史可能触发异常登录或账号冻结，
  因此 NeriPlayer 不向这些平台上传本地播放历史和统计。

架构、启动链路与测试护栏等实现说明见 [docs/implementation.md](./docs/implementation.md)
与 [CONTRIBUTING.md](./CONTRIBUTING.md)。

---

## 核心特性 / Key Features

### 🎧 播放与音质

- **多源与本地播放**：网易云、Bilibili、YouTube Music 与本地音频共用一个播放队列。
- **播放兜底**：网易云不可播时先降音质；开启兜底后按歌名、歌手与时长匹配 Bilibili 候选，连续失败自动跳过。
- **YouTube 播放兼容**：保留有效身份 Cookie 并支持轮换；缓存的 `player.js` 或播放候选失效时进入 EJS/HLS 回退，避免同一候选反复重试。
- **音效**：自研 DSP 提供内置音效、10 段均衡器、AutoEQ 导入、响度均衡与 32-bit 高解析输出，关闭即完全旁路。
- **USB 独占**：支持 UAC1.0 与兼容 UAC2.0 Type I PCM 的 DAC，可比特完美输出；插入响应可单独关闭。
- **后台保活**：可选「悬浮窗保活」，以不可见 1 像素窗口降低后台播放被系统冻结断开的风险。
- **播放行为**：淡入淡出、切歌交叉淡入淡出、蓝牙断连暂停、混音播放与音频焦点策略，并恢复上次播放进度与播放模式。
- **Bilibili 跳过**：SponsorBlock 自动跳过与自定义区间，默认关闭，一起听期间不生效。

### 🎤 歌词

- **逐行与逐字**：LRC / YRC / TTML 全覆盖，支持高亮、翻译、音译、偏移、点击跳转与全屏歌词。
- **CC 字幕歌词**：B 站歌曲可直接使用视频 CC 字幕，可选择字幕轨道并回退自动字幕。
- **来源与编辑**：按偏好自动选择来源（网易云 / 酷狗 / QQ 音乐 / LRCLIB / AMLL），支持手动匹配与编辑。
- **外部输出**：悬浮歌词、状态栏歌词、SuperLyric、Lyricon、蓝牙歌词与车载显示；另有实验性的 ColorOS 锁屏岛歌词（需 Root 或 Shizuku，不能保证所有 ROM 可解锁）。

### 🗂️ 媒体库与本地管理

- **分类浏览**：本地、收藏、网易云、YouTube Music、Bilibili 入口；YouTube 可完全禁用，哔哩哔哩音乐可单独关闭首页分区。
- **首页推荐**：最近常用歌单、榜单、新歌、日推、私人 FM、精品歌单与雷达歌单，刷新会更新全部分区。
- **搜索与链接识别**：按平台独立搜索，网易云与 Bilibili 支持触底分页；粘贴含标题或短链接的分享文案即可识别网易云、Bilibili 与 YouTube 内容。
- **导入与批量词图**：支持分享导入与文件夹扫描，可一键为本地歌曲批量匹配并嵌入歌词与封面。
- **本地歌手与歌单**：按展示艺术家聚合，支持排序、整体插入与删除撤销。
- **歌曲百科**：提供曲风、语种、BPM、创作团队、评论预览、相似歌曲与相关歌单。

### ⬇️ 下载、缓存与存储

- **应用内下载**：多平台音频下载、断点续传与启动恢复，默认并发 6（可调 1–8）。
- **可迁移目录**：支持 SAF 自定义目录与文件名模板，切换时自动迁移已有下载。
- **缓存清理**：音频缓存默认上限 1 GB；可用 0–1000 首滑块保留最近播放歌曲，当前播放始终受保护。

### 🔄 同步、统计与备份

- **GitHub / WebDAV 同步**：歌单、收藏、最近播放、删除记录与播放统计，保存在你自己的远端。
- **播放与流量统计**：按歌曲身份记录播放次数与时长，支持日/周/月/年视图；流量区分 Wi-Fi / 移动 / 漫游。
- **备份与恢复**：歌单 JSON 导入导出；配置导出默认不含登录态，可选择加密并包含授权信息。

### 🎨 界面与个性化

- **双界面风格**：Material You 与预览版「简洁（Clean）」，后者可开启液态玻璃效果。
- **主题与显示**：动态取色、自定义背景、歌词字号与 UI 缩放（手机 0.6–1.2x、车载 0.6–1.5x）。
- **流体背景**：播放页随封面取色的 `RuntimeShader` 流体背景，接入音频响应（Android 13+）。
- **桌面与车机**：三种尺寸小组件、启动器快捷方式与蓝牙车载信息发布。
- **大屏适配**：平板横屏固定完整歌词分栏，手机横屏可左右切换封面与歌词。

### 👥 一起听

- 创建或加入房间，WebSocket 实时同步；首次加入需邀请密钥，重连使用成员密钥。
- 支持房主/听众权限、循环与随机模式同步、时钟偏移校正与房主离线恢复。
- 可选共享房主播放候选（最多 3 条），听众优先按本机音质策略解析。

### 🛟 稳定性与诊断

- **脱机模式**：自动感知网络状态，脱机时只走本地文件与缓存。
- **安全模式**：JVM / Native 崩溃或系统 ANR 后可进入安全模式导出日志。
- **开发者模式**：连点版本号 7 次启用调试探针与日志查看器。

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
2. 安装 JDK 17，用最新稳定版 Android Studio 打开并同步依赖；
   运行 `:playback:runtime` JVM 测试或全量模块门禁时另需 JDK 21，生产字节码仍以 17 为目标。
3. 构建并安装调试版：
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. 首次启动先阅读免责声明并完成引导；通知与本地音乐权限仅在主动点按后申请，跳过不影响使用。
5. 如需调试工具，在设置页连续点击 **版本号** 7 次启用开发者模式。

> DEBUG 构建仅用于测试，性能和体积不代表发布版。
> DEBUG 包使用 `moe.ouom.neriplayer.debug` 和橙色启动图标，名称带 `DEBUG` 标记，可与 Release 共存。
> DEBUG 分享的一起听邀请使用 `neriplayer-debug://`，仍可在应用内粘贴普通邀请链接加入。
> 完成引导后会提示 DEBUG 构建风险，确认后不再提醒，清除数据或重新安装后再次提示。

在 Android Studio 中运行时选择 `app` 运行配置，并确认 Build Variants 中 `:app` 使用 `debug`。
更新包名配置或切换分支后，先执行 **Sync Project with Gradle Files**；仅重新构建 APK
不会刷新 IDE 缓存的启动包名，可能导致安装 DEBUG 包后仍启动已安装的正式版。

发布版构建与签名流程请参阅
[CONTRIBUTING.md](./CONTRIBUTING.md#构建发布版--release-build)。

---

## 平台现状 / Platform Status

- **网易云音乐**：登录、搜索、精选歌单、专辑、播放、下载、歌词、元数据补全，
  无权限播放兜底、本地收藏同步到「我喜欢的音乐」、艺术家详情、歌曲百科与关注；
  评论支持热门/最新/推荐排序、楼中楼、复制，以及登录后的点赞、发评与回复。
- **Bilibili**：Web/二维码登录、视频搜索、创建与订阅收藏夹、合集、分 P 转音频播放与下载；
  首页提供音乐热门榜、原创榜与热门视频；链接识别保留分 P 并能从 `season_id` 恢复合集上下文；
  评论支持热门/最新排序、楼中楼与登录后的互动。
- **YouTube Music**：登录、首页/媒体库歌单浏览、歌单详情、搜索与播放兼容；
  内容访问仍受平台规则与账号权限约束。
- **QQ 音乐**：仅用于播放页元数据与歌词补全，未实现登录、播放与库页数据。
- **本地音频**：外部分享/打开导入、设备与文件夹扫描、播放、歌手分类、分享与歌单管理。

---

## 实现概览 / Implementation Notes

构建版本、模块边界与关键实现路径已拆分到独立文档：

- [docs/implementation.md](./docs/implementation.md)：构建与版本、14 个自有库的职责、
  入口与导航、播放与缓存、搜索与数据来源、本地数据与安全、下载与备份。
- [CONTRIBUTING.md](./CONTRIBUTING.md#项目结构与当前实现--project-layout)：开发者视角的模块边界、
  扩展路径、测试与提交要求。

---

## 一起听服务端部署 / Listen Together Deployment

NeriPlayer 内置「一起听」功能，你可以部署自己的服务端，也可以使用他人部署的服务。

- 当前仓库内的 `np-submodule/NeriPlayer-LTW`
- 公开部署模板：[TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

服务端基于 **Cloudflare Workers** 与 **Durable Objects**，通过 WebSocket 提供实时同步。

### 一键部署到 Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

应用内可在设置页配置服务端地址、测试可用性并重置本机一起听身份。
房间号固定 6 位可读字符，昵称长度 1-24（当前允许中文、英文字母和数字）；
首次加入必须使用邀请链接中的密钥，本地歌曲不能创建一起听房间。
更完整的协议、事件与部署细节见
[np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md)。

---

## 同步 / Sync

NeriPlayer 支持把本地元数据同步到 **用户自己的** GitHub 仓库或 WebDAV 远端文件。
应用内新建 GitHub 仓库时默认创建为私有仓库，也支持接入已有仓库。

同步对象：本地歌单、收藏歌单、最近播放记录、最近播放删除记录与播放统计。

### 使用方法

1. 打开设置页中的备份与同步。
2. GitHub 方式需创建 Personal Access Token（需要 `repo` 权限）。
3. 完成 Token 校验，选择创建默认私有仓库或接入已有仓库。
4. 新安装直接使用 V4。出现升级弹窗时，先更新所有参与同步的设备，再确认并立即同步迁移该地址。
5. 开启自动同步，或手动点击立即同步。

### 行为说明

- 🔒 **凭据与传输**：Token 与 WebDAV 密码存于 `Android Keystore + EncryptedSharedPreferences`；使用 HTTP 地址会提示明文传输风险。
- 🔄 **调度与合并**：变更后延迟 5 秒同步，另有每小时周期同步；三路合并处理歌单、历史、统计与删除记录。这是后台双向同步，不是实时推送。
- 📦 **远端格式**：V4 以 ZSTD 分块增量上传，优先强 ETag 条件写入（坚果云等则核对清单指纹）；旧格式仅在新清单缺失时读取，远端文件不是端到端加密。

---

## 发展规划 / Roadmap

这些方向会根据维护精力、平台可用性和社区反馈调整，不承诺固定周期。

- [ ] 视频播放
- [ ] 第三方平台播放、库页和账号能力持续扩展
- [ ] 更完整的 QQ 音乐账号能力、库页数据与更稳定授权链路

已落地功能见 [Releases](https://github.com/cwuom/NeriPlayer/releases) 与提交历史。

---

## 问题反馈 / Bug Report

- 反馈前建议先开启开发者模式（设置页点击 **版本号** 7 次），应用会启用普通文件日志，
  崩溃日志会单独落盘。
- 前往 [Issues](https://github.com/cwuom/NeriPlayer/issues)，提供系统版本、机型、
  应用版本、复现步骤与关键日志。
- Windows 过滤日志：`adb logcat | findstr NeriPlayer`
- Linux / macOS 过滤日志：`adb logcat | grep NeriPlayer`

---

## 已知问题与能力边界 / Known Issues

- **网络**：请合理配置代理规则；全局代理可能导致部分第三方接口返回异常数据。
- **下载**：不依赖系统下载服务，已支持自动断点续传与启动恢复，但仍不是系统级后台下载器。
  手动取消会清理断点与半成品，只有网络策略暂停或可恢复错误才保留续传状态。
  自定义 SAF 下载目录便于外部访问，但扫描、迁移和落盘通常比应用私有目录慢。
- **USB 独占**：依赖兼容 DAC、前台服务和系统后台策略；息屏后被限制请按设置页提示放开
  电池/后台限制。UAC2 异步链路只接受能解析出唯一显式反馈端点与受支持时钟拓扑的设备，
  隐式反馈或无法验证的拓扑会走兼容回退。Host 测试与四 ABI 编译不等同于真实设备验证。
- **音频输出**：32-bit 高解析普通输出会旁路响度均衡、声道平衡、音频可视化与应用内倍速，
  需要这些处理时请保持该开关关闭。
- **蓝牙车载**：AVRCP 没有独立歌词字段，歌词借用标题区域；歌词刷新与封面传输取决于手机系统
  与车机能力，不保证所有普通蓝牙车机支持。Android Auto 使用车机自己的播放界面，
  当前不提供独立的 Android Automotive OS 版本。
- **歌词**：音译显示依赖平台或嵌入歌词中存在音译数据；歌词卡片写入应用缓存目录，可被缓存清理释放。
  ColorOS 锁屏岛歌词为实验特性，依赖厂商配置与 Root/Shizuku，系统更新后需重新检查。
- **平台能力**：Bilibili 主要提供搜索、收藏夹、合集与音频播放链路，不是完整视频发现流；
  QQ 音乐仅作为播放页元数据/歌词补全源。
- **同步**：不是端到端加密；配置导出默认不含登录态，选择包含时应设置密码并妥善保管，
  密码不会保存在文件中，遗忘后无法恢复。并发分支更新失败时会报告冲突，不会强制覆盖远端提交。
- **其他**：长音频进度记忆不覆盖显式播放位置，也不代替第三方平台的播放历史；
  BilibiliSponsorBlock 需要访问公开接口，且一起听期间保持关闭。

---

## 隐私与数据 / Privacy

- 不提供自己的公共云端媒体分发服务，也不接入广告 SDK、第三方统计或崩溃分析 SDK。
- 采用去中心化的数据策略：同步目标由用户自己选择和保管，
  不会把个人媒体数据汇聚到维护者控制的中心化平台。
- 缓存、下载文件、本地歌单、历史记录、播放统计、设置与授权信息默认保存在设备本地。
- 只有用户主动开启 GitHub 或 WebDAV 同步时，才会同步歌单、收藏、历史和播放统计等元数据。
- 不会将音频缓存、下载文件、Cookie、播放 Token 上传给开发者。
- 出于账号安全考虑，不会把本地播放历史或播放统计回写到第三方音乐平台。
- 配置导出默认不含登录态；选择包含时会携带授权与同步凭据，应设置密码并妥善保管。
- 默认关闭 Android 系统云备份 / 设备迁移。
- 第三方平台侧的访问日志与风控策略，由对应平台按照其自身隐私政策处理。

---

## 鸣谢 / Reference

- [netease-cloud-music](https://github.com/chaunsin/netease-cloud-music)：网易云音乐 Golang 实现
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)：哔哩哔哩 API 收集整理
- [ejs](https://github.com/yt-dlp/ejs)：External JavaScript for yt-dlp supporting many runtimes
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core)：歌词解析、转换与导出库
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui)：卡拉 OK 歌词 Composable
- [LDDC](https://github.com/chenmozhijin/LDDC)：多平台歌词下载匹配工具，手动匹配体验参考
- [Metrolist](https://github.com/MetrolistGroup/Metrolist)：YouTube Music client for Android
- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)：HyperOS enhancement module

---

## 参与、支持与许可 / Contributing, Support & License

- 贡献前请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。项目处于持续迭代中，
  Release 通常按功能批次手动发布；核心播放、本地数据、同步与恢复链路优先维护。
- 由于项目特殊性，暂不接受任何形式的捐赠；欢迎通过 Issue、PR 或分享使用体验支持项目。
- NeriPlayer 使用 **GPL-3.0** 发布：可自由使用、修改和分发；
  分发修改版时须继续遵守 GPL-3.0。
  [Native 源码授权说明](./modules/native/src/main/cpp/README.md) 仅为其中列出的
  NeriPlayer 自有 Native 源码提供附带署名条件的替代授权，第三方源码与未列入范围的仓库内容不适用；
  外部 Native 贡献不会因提交 PR 自动进入该范围，只有明确记录双授权同意时才适用。
  小米超级岛歌词的 Shizuku/XMSF 网络隔离适配移植了 GPL-3.0 的 Capsulyric 源码，
  来源说明见 [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md)。
  详细条款见 [LICENSE](./LICENSE)。

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="访问计数 (Moe Counter)">
</p>

## Star History

<a href="https://www.star-history.com/?repos=cwuom%2Fneriplayer&type=timeline&legend=bottom-right">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&theme=dark&legend=bottom-right" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
  </picture>
</a>
