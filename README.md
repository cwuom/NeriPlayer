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

## 项目简介 / About

NeriPlayer 是一个基于 **Jetpack Compose + Media3** 的原生 Android
音频播放器。它不构建公共云端服务，而是在用户具备第三方平台账号能力的前提下，
整合 **网易云音乐**、**Bilibili** 与 **YouTube Music** 的在线内容，
并提供本地播放、下载、缓存、歌单管理和多种同步/备份能力。

当前定位：

- **账号即能力**：通过第三方平台授权启用搜索、播放、歌单和收藏夹访问。
- **本地优先**：播放缓存、下载文件、歌单、历史记录、设置与授权信息默认保存在设备本地。
- **可选同步**：可将歌单、收藏、最近播放和播放统计同步到用户自己的
  GitHub 私有仓库或 WebDAV 远端文件。
- **单 Activity + Compose 架构**：`MainActivity` 是唯一对外入口，
  UI 由 Compose `NavHost`、动态底栏、Mini Player 与 Now Playing 覆盖层组织。
- **启动引导**：首次启动流程为 `Loading -> Disclaimer -> Onboarding -> Main`，
  需先阅读免责声明，再完成语言、平台账号、同步和个性化设置引导。

---

## 为什么值得关注 / Why it stands out

- **不绑公共云服务**：登录第三方平台只是为了调用你已有的账号能力，
  媒体、缓存、歌单、历史和设置默认都留在本机。
- **能播，也能管**：在线播放、缓存、下载、歌单、备份与 GitHub/WebDAV
  同步是一套连续体验，不是零散功能拼接。
- **歌词体验做得很深**：逐词歌词、翻译歌词、歌词编辑、悬浮歌词、
  Lyricon 与外接设备歌词都走同一条播放数据链路。
- **出问题也方便排查**：内置开发者模式、普通日志、JVM/Native 崩溃日志，
  启动阶段还能直接进入安全模式做隔离排查。
- **主链路可持续演进**：基于 Compose + Media3 + 手写 DI，
  播放、下载、同步、鉴权、歌词和一起听都有成体系的测试与调试探针。

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
- 🔍 **分层搜索能力**：
  `Explore` 使用网易云 / Bilibili / YouTube Music 按平台独立搜索；
  播放页元数据补全使用网易云 / QQ 音乐，并接入 LRCLIB 外部歌词来源。
- 🧠 **Media3 播放核心**：
  `PlayerManager` 管理音源解析、队列、随机/循环、状态恢复、失败重试、
  YouTube 预取与平台特殊请求策略。
- 🔁 **网易云自动换源**：
  网易云歌曲无权限、无可用直链或仅返回试听片段时，会先尝试降低音质，
  再按歌曲名、歌手和时长匹配 Bilibili 音源兜底。
- 🎚️ **播放音效**：
  Now Playing 内置倍速、音调、响度增强和均衡器预设/手动频段调节。
- 💾 **可配置流媒体缓存**：
  使用 `SimpleCache + LRU` 缓存音频，默认上限 **1 GB**，
  支持分别清理音频缓存和图片缓存。
- ⬇️ **应用内下载与管理**：
  支持多平台音频下载、本地下载列表、任务进度、取消/重试，
  并保存歌词、封面、元数据和音频标签。
- 📁 **可迁移下载目录**：
  下载文件默认在应用管理目录，也可通过 SAF 选择自定义目录；
  切换目录时会迁移已有下载，并支持自定义文件名模板。
- 🎵 **本地音频导入与扫描**：
  支持系统 `VIEW / SEND / SEND_MULTIPLE` 的 `audio/*`，
  也支持扫描设备本地音乐，并自动识别附近歌词与封面 sidecar 文件。
- 🩷 **本地歌单与收藏**：
  内置「我喜欢的音乐」和「本地文件」系统歌单，普通本地歌单支持创建、
  重命名、删除、排序和添加歌曲；「我喜欢的音乐」支持同步可识别歌曲到
  网易云我喜欢的音乐。
- ☁️ **GitHub / WebDAV 同步**：
  可选同步本地歌单、收藏歌单、最近播放、播放统计和删除记录，
  使用 `WorkManager` 做延迟与周期同步。
- ♻️ **备份与恢复**：
  支持歌单 JSON 导入/导出；也支持完整配置导入/导出，
  可迁移设置、语言、平台授权、GitHub/WebDAV 配置与一起听设置。
- 🎧 **一起听**：
  支持创建房间或加入他人房间，通过 WebSocket 实时同步播放状态，
  支持房主/听众权限、邀请链接、深链加入、自定义服务端和房主离线检测。
- 🌈 **个性化与主题**：
  支持浅色/深色/跟随系统、动态取色、种子色、主题风格、UI 缩放、
  自定义背景图、歌词字号和首页卡片开关。
- ✨ **播放页动效与歌词**：
  支持音频反应式动态背景、封面模糊背景、高级歌词、逐词歌词、翻译歌词、
  歌词偏移、歌词编辑、歌词字体调节和 Lyrics 全屏页。
- 🪟 **悬浮歌词**：
  支持系统悬浮歌词，颜色、描边、字号、位置、对齐和翻译显示都可自定义，
  也可在应用前台自动隐藏避免遮挡。
- 🔌 **外部歌词/设备联动**：
  支持词幕适配（Lyricon Provider）、外部蓝牙歌词、蓝牙断连暂停和
  USB 独占播放开关；词幕适配会同步当前歌曲、播放状态、进度、逐字歌词和翻译。
- 🛠️ **开发者模式与调试工具**：
  设置页连续点击版本号 **7 次** 后，底栏出现 `Debug` 页，
  包含 YouTube / Bili / Netease / Search / Listen Together 探针、
  普通日志与崩溃日志查看器。
- 🛟 **安全模式与崩溃日志**：
  上次启动发生异常时，可直接进入安全模式预览或导出崩溃日志，
  并按需清理设置、授权信息或崩溃标记。

---

## 平台现状 / Platform Status

- **网易云音乐**：
  登录、歌曲搜索、精选歌单、专辑、歌单/专辑列表搜索、播放、下载、歌词、播放页元数据补全，
  无权限自动换源，以及本地收藏同步到网易云我喜欢的音乐。
- **Bilibili**：
  登录、视频搜索、收藏夹、合集、收藏夹/合集列表搜索、分 P 转音频播放、下载；
  当前不是完整视频发现流或评论区客户端。
- **YouTube Music**：
  登录、首页/歌单浏览、歌单详情、搜索、播放、下载，
  并包含 PoToken / JS Challenge 相关支持。
- **QQ 音乐**：
  当前仅用于播放页元数据和歌词补全，未实现登录、播放和库页数据。
- **本地音频**：
  支持外部分享/打开导入、设备扫描、本地文件播放、分享和本地歌单管理。

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
- `np-submodule/NeriPlayer-LTW`：一起听 Cloudflare Workers 服务端。

### 入口与导航

- `MainActivity` 是唯一对外入口，同时处理启动流程、通知权限、
  外部音频导入和 `neriplayer://listen-together/join` 深链。
- 主界面是 **Compose NavHost + 动态底栏**：
  `Home / Explore / Library / Settings` 为主路径。
- `Home` 会根据首页卡片可用性动态显示；`Debug` 仅开发者模式开启后显示。
- `Now Playing` 不是普通路由，而是覆盖在主导航之上的全屏播放层，
  底部常驻 `Mini Player`。
- Library 还提供最近播放和播放统计入口。

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

### 搜索与数据来源

- **UI 搜索**：
  `Explore` 当前接入网易云、Bilibili 和 YouTube Music，
  采用按平台独立搜索，不混合聚合结果。
- **元数据补全**：
  播放页通过 `SearchManager` 使用网易云与 QQ 音乐补全封面、歌词和曲目信息。
- **歌词来源**：
  除平台歌词外，还包含 LRCLIB 外部歌词客户端；
  播放页支持原文歌词、翻译歌词、逐词歌词和手动编辑。
- **词幕适配**：
  `LyriconManager` 向 Lyricon 输出当前歌曲、播放状态、进度、
  逐字歌词与翻译歌词。

### 本地数据与安全

- 常规设置使用 `DataStore` 持久化，并通过 KSP 生成设置 key、备份白名单和设置 UI 元数据。
- 平台 Cookie、YouTube 授权信息、GitHub Token 与 WebDAV 密码使用
  `Android Keystore + EncryptedSharedPreferences` 本地加密保存。
- 播放历史、播放统计、歌单、收藏快照和部分映射数据使用本地文件持久化。
- 本地歌单使用 JSON 文件存储，并通过临时文件实现原子写入。
- GitHub/WebDAV 同步使用本地生成的 UUID 作为设备标识，不依赖 `ANDROID_ID`。

### 下载、本地导入与备份

- 下载使用共享 `OkHttpClient`，不是系统 `DownloadManager`。
- 下载文件可存放在应用管理目录或用户选择的 SAF 目录，
  并配套保存歌词、封面、元数据和音频标签。
- 当前下载不提供断点续传。
- `LocalAudioImportManager` 支持导入外部音频、扫描设备音乐，
  并复制附近的 `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
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

---

## GitHub 同步 / GitHub Sync

NeriPlayer 支持将本地元数据同步到 **用户自己的 GitHub 私有仓库**。

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
- 📦 **远端格式**：GitHub 私有仓库不等于端到端加密，
  远端文件仍由用户自行保管。
- 🚫 **同步边界**：不会上传音频缓存、下载文件、本地音频文件、
  Cookie 或播放 Token。

### 使用方法

1. 打开设置页中的备份与同步。
2. 创建 GitHub Personal Access Token（需要 `repo` 权限）。
3. 在应用内完成 Token 校验与仓库配置。
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

### 正在推进

- [ ] 视频播放
- [ ] 评论区
- [ ] 第三方平台持续扩展（酷狗音乐等）
- [ ] 更完整的 QQ 音乐账号能力、库页数据与更稳定授权链路

### 近期已落地

- [x] 悬浮歌词
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

- 下载功能当前不依赖系统下载服务，也不提供断点续传。
- Bilibili 当前主要提供视频搜索、收藏夹、合集和音频播放链路，不是完整视频发现流。
- QQ 音乐当前仅作为播放页元数据/歌词补全源。
- GitHub/WebDAV 同步不是端到端加密；完整配置导出文件可能包含授权信息，
  请自行妥善保管。

---

## 隐私与数据 / Privacy

- NeriPlayer 不提供自己的公共云端媒体分发服务，也不接入广告 SDK、
  第三方统计或崩溃分析 SDK。
- 播放缓存、下载文件、本地歌单、历史记录、播放统计、设置与授权信息默认保存在
  用户设备本地。
- 如用户主动开启 GitHub 或 WebDAV 同步，仅会同步歌单、收藏、历史和播放统计等元数据。
- 不会将音频缓存、下载文件、Cookie、播放 Token 上传给开发者。
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

- 仅维护核心功能，其他能力欢迎社区贡献。
- 仓库可能因特殊原因暂停更新。
- 欢迎提交 PR 与反馈。

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
