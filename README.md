[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer (音理音理!)</h1>

<div align="center">

<h3>✨ 原生 Android 多源音频播放器 🎵</h3>

<p>
  <a href="https://t.me/ouom_pub">
    <img alt="Join" src="https://img.shields.io/badge/Telegram-@ouom__pub-blue" />
  </a>
</p>

<p>
  <a href="https://t.me/neriplayer_ci">
    <img alt="ci_builds" src="https://img.shields.io/badge/CI_Builds-@neriplayer__ci-orange" />
  </a>
</p>

<p>
  <img src="icon/neriplayer.svg" width="260" alt="NeriPlayer logo" />
</p>

<p>
本项目的名称及图标灵感来源于《星空鉄道とシロの旅》中的角色「风又音理」。
</p>

<p>
项目采用原生 Android 开发，当前支持 Android 9 (API 28) 及以上设备，
围绕「多源探索、在线播放、本地可控」持续迭代。
</p>

🚧 <strong>Work in progress / 开发中</strong>

</div>

> [!WARNING]
> 本项目仅供学习与研究使用，请勿将其用于任何非法用途。

---

> [!NOTE]
> NeriPlayer 不提供公共云端曲库或媒体分发服务。
> 在线音频能力依赖您在第三方平台上的账号授权，
> 会员或受限内容仍需遵循原平台规则。

---

## 项目简介 / About
NeriPlayer 是一个基于 **Jetpack Compose + Media3** 的原生 Android
音频播放器。当前实现重点不是构建公共云端服务，而是在用户已具备
第三方平台账号能力的前提下，整合 **网易云音乐** 与 **Bilibili** 的
在线内容，并提供 **流媒体缓存、应用内下载、本地导入、本地歌单管理、
可选 GitHub 私有仓库同步** 等能力。

- **账号即能力**：通过用户在第三方平台的合法授权，启用在线播放、
  搜索、歌单访问等能力。
- **默认本地存储**：播放缓存、下载文件、歌单、历史记录、设置与授权
  信息默认保存在设备本地。
- **可选自有仓库同步**：可将歌单、收藏和历史等元数据同步到用户自己
  的 GitHub 私有仓库。
- **单 Activity + Compose 架构**：以 `MainActivity` 为唯一对外入口，
  通过 Compose `NavHost`、Mini Player 与 Now Playing 覆盖层组织界面。
- **首次使用有免责声明阶段**：应用启动流程为
  `Loading -> Disclaimer -> Main`，首次进入需阅读并同意免责声明。

---

## 核心特性 / Key Features
- 🎧 **多源探索与播放**：`Explore` 页当前支持网易云精选歌单与
  YouTube Music 歌单浏览，并提供 **网易云 / Bilibili / YouTube Music**
  搜索入口。
- 🔍 **分层搜索能力**：页面搜索与播放页元数据补全是两套链路。
  `Explore` 使用 **网易云 / Bilibili / YouTube Music**；
  `SearchManager` 用 **网易云 / QQ 音乐** 补全封面、歌词与曲目信息。
- 🧠 **基于 Media3 的自定义播放管理层**：
  `PlayerManager` 负责音源解析、播放队列、随机/循环、状态持久化、
  失败重试与恢复。
- 💾 **可配置流媒体缓存**：播放器使用 `SimpleCache + LRU` 做音频缓存，
  默认上限为 **1 GB**，支持在设置中手动清理缓存。
- ⬇️ **应用内下载与本地播放**：支持将在线音源下载到应用专属目录，
  同步保存歌词与封面，并在应用内查看进度、管理已下载歌曲。
- 📁 **本地音频导入与扫描**：支持系统 `VIEW / SEND / SEND_MULTIPLE`
  的 `audio/*`，可从外部分享/打开音频后导入；也支持扫描设备本地音频。
- ☁️ **GitHub 私有仓库同步**：可选同步本地歌单、收藏歌单、最近播放与
  删除记录，使用 `WorkManager` 进行延迟与周期同步。
- 🛠️ **开发者模式与调试工具**：设置页连续点击版本号 **7 次** 后，
  底栏会出现独立 `Debug` 页，内含 YouTube / Bili / Netease / Search
  API 探针、普通日志与崩溃日志查看器。
- 🌈 **音频反应式动态背景**：在 Android 13+ 的 Now Playing 页面，
  可选启用基于 `RuntimeShader` 的音频反应式背景效果。
- ♻️ **本地备份与恢复**：支持本地歌单与收藏数据的 JSON 导入/导出，
  用于设备迁移或手工备份。

---

## 平台现状 / Platform Status
- **网易云音乐**：登录、搜索、精选歌单/专辑访问、播放、下载、歌词补全。
- **Bilibili**：登录、搜索、收藏夹访问、分 P 转音频播放、下载。
- **YouTube Music**：登录、歌单浏览与详情、播放、下载；Explore 中已
  注册为搜索源（搜索功能开发中）。
- **QQ 音乐**：当前仅用于播放页元数据/歌词补全，未实现登录、播放与库页。

---

## 实现概览 / Implementation Notes
### 构建与版本
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17
- 版本名格式：`<git短哈希>.<MMddHHmm>`
- Release APK 文件名：`NeriPlayer-<versionName>.apk`

### 入口与导航
- 对外入口只有 `MainActivity`，同时处理应用启动与外部音频导入。
- 启动流程包含免责声明阶段；Android 13+ 首次启动时会申请通知权限。
- 主界面是 **Compose NavHost + 动态底栏**：
  `Home / Explore / Library / Settings` 为主路径。
- `Home` 只有在首页卡片启用时才显示；`Debug` 只有开发者模式开启后才显示。
- `Now Playing` 不是普通路由，而是覆盖在主导航之上的全屏播放层，
  底部常驻 `Mini Player`。

### 播放、缓存与服务
- 播放核心基于 Media3 ExoPlayer，由 `PlayerManager` 统一管理。
- `AudioPlayerService` 提供前台播放服务、媒体通知与基础传输控制。
- Bilibili 音频播放通过 `ConditionalHttpDataSourceFactory`
  动态附加 `Referer / User-Agent / Cookie`。
- 播放状态会定期持久化，用于进程重启后的队列与状态恢复。

### 搜索与数据来源
- **UI 搜索**：`ExploreViewModel` 当前接入 `Netease` 与 `Bilibili`。
- **元数据补全搜索**：`SearchManager` 当前使用
  `CloudMusicSearchApi` 与 `QQMusicSearchApi`。
- 当前 UI 层搜索是**按平台切换**，不是混合聚合结果列表。
- QQ 音乐目前主要用于播放页的元数据/歌词补全，
  Library 中的 QQ 音乐入口仍处于占位/开发中状态。

### 本地数据与安全
- 设置与平台 Cookie 使用 `DataStore` 持久化。
- 播放历史、歌单、收藏快照与部分映射数据使用本地文件持久化。
- 本地歌单使用 JSON 文件存储，并通过临时文件实现原子写入。
- GitHub Token 使用 **Android Keystore + EncryptedSharedPreferences**
  本地加密保存。
- GitHub 同步使用本地生成的 UUID 作为设备标识，不依赖 `ANDROID_ID`。

### 下载、本地导入与备份
- 下载实现基于共享 `OkHttpClient`，不是系统 `DownloadManager`。
- 下载文件存放在应用专属音乐目录，并配套保存歌词与封面。
- `LocalAudioImportManager` 支持导入外部音频，并复制附近的
  `lrc/txt` 歌词文件与 `cover/folder/front` 封面图。
- `BackupManager` 支持本地 JSON 备份、导入与差异分析。

想深入了解实现细节？请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。

---

## GitHub 同步功能 / GitHub Sync
NeriPlayer 支持将本地元数据同步到 **用户自己的 GitHub 私有仓库**，
当前同步目标主要包括：

- 本地歌单
- 收藏歌单
- 最近播放记录
- 最近播放删除记录

### 技术细节
- 🔒 **本地安全存储**：GitHub Token 保存在
  `Android Keystore + EncryptedSharedPreferences` 中。
- 🔄 **同步调度**：本地数据变更后会触发一次
  **延迟 5 秒** 的同步；同时存在 **每小时一次** 的周期同步。
- ⏱️ **最终一致性**：这是后台双向同步，不是实时秒级推送。
- 🌐 **网络要求**：同步任务依赖 `WorkManager`，仅在存在
  **validated network** 时执行。
- 🧩 **冲突处理**：同步采用三路合并，处理歌单、收藏、历史与删除记录。
- 🧯 **冲突边界**：当前以内建规则自动合并常见冲突，暂无手动冲突解决界面。
- 🪶 **省流模式**：同步模块内置 Data Saver 模式，默认开启。
- 📦 **远端格式**：远端备份文件为明文 JSON 或压缩二进制格式，
  GitHub 私有仓库并不等于端到端加密。
- 🚫 **同步边界**：同步的是歌单/收藏/历史等元数据，不会上传音频缓存、
  下载文件、本地文件歌单、Cookie 或播放 Token。

### 使用方法
1. 打开设置页中的 GitHub 同步入口。
2. 创建 GitHub Personal Access Token（需要 `repo` 权限）。
3. 在应用内完成 Token 校验与仓库配置。
4. 开启自动同步。

---

## 快速体验 / Getting Started
### a. 下载 Release 版本（推荐）
1. 前往 [GitHub Release](https://github.com/cwuom/NeriPlayer/releases)
2. 如何选择版本？
   - 大部分用户请下载 arm64-v8a 版本
   - 老旧手机（32位系统）请下载 armeabi-v7a
   - x86 / x86_64 仅供模拟器或英特尔设备或 Chromebook 使用，普通用户无需下载

### b. 下载 CI 版本
1. 前往 [GitHub Action](https://github.com/cwuom/NeriPlayer/actions)
   下载最近一次构建成功的 Artifacts 并解压。
2. 或访问 [NeriPlayer CI Builds](https://t.me/neriplayer_ci)。

> CI 仅构建 arm64-v8a 版本

### c. 本地构建
1. 克隆仓库并使用 Android Studio（最新稳定版）打开：
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 同步依赖。
3. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. 安装 APK（需要 Android 9+ 设备）：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. 首次启动时先阅读并同意免责声明；Android 13+ 设备会申请通知权限。
6. 如需调试工具，在设置页连续点击 **版本号** 7 次，
   启用开发者模式后底栏会出现独立 `Debug` 页面。

> DEBUG 版本可能会存在性能问题，仅供测试使用。

发布版构建与签名流程请参阅 [CONTRIBUTING.md](./CONTRIBUTING.md#构建发布版--release-build)。

---

## 发展规划 / Roadmap
- [ ] 视频播放
- [ ] 评论区
- [x] 清理缓存
- [x] 添加到播放列表
- [x] 平板适配
- [ ] 歌词悬浮窗
- [x] 国际化
- [x] 网易云音乐适配 / NetEase Cloud Music
- [x] 哔哩哔哩适配 / BiliBili
- [x] YouTube Music 基础适配（登录、歌单、播放、下载）
- [ ] YouTube Music 搜索能力
- [ ] 第三方平台持续扩展（酷狗音乐等）

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
- `Bilibili` 当前主要提供搜索与音频播放链路，不是完整的视频发现流。

---

## 隐私与数据 / Privacy
- NeriPlayer 不提供自己的公共云端媒体分发服务，也不接入广告 SDK、
  第三方统计或崩溃分析 SDK。
- 播放缓存、下载文件、本地歌单、历史记录、设置与授权信息默认保存在
  用户设备本地。
- 如用户主动开启 GitHub 同步，仅会将歌单/收藏/历史等元数据同步到
  用户自己的 GitHub 私有仓库。
- 不会将音频缓存、下载文件、Cookie、播放 Token 上传给开发者。
- 应用启用了 Android 系统备份 / 设备迁移能力；
  具体是否备份以及备份范围取决于系统策略。
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
