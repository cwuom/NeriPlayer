<h1 align="center">NeriPlayer (音理音理!)</h1>

<div align="center">

<h3>✨ 简易多平台音视频聚合流媒体播放器 🎵</h3>

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
项目采用原生 Android 开发，当前支持 Android 9 (API 28) 及以上设备，遵循 Material You 设计与动效语言，持续探索「聚合音视频内容、保持隐私本地可控」的体验路径。
</p>

🚧 <strong>Work in progress / 开发中</strong>

</div>

> [!WARNING]
> 本项目仅供学习与研究使用，请勿将其用于任何非法用途。

---

> [!NOTE]
> 为保护音乐版权并保障您的使用权益，本软件的音频服务依赖您在第三方平台的账号授权。
> 会员专属内容仍需遵循原平台的会员规则。

---

## 项目简介 / About
NeriPlayer 是一个专注于「聚合多平台音视频流」的开源播放器，致力于在不复制、不缓存版权内容的前提下，为个人学习与研究提供一个整洁、可控且现代化的播放器体验。核心目标包括：

- **账号即能力**：通过用户在第三方平台的合法授权，实现多源搜索与播放。
- **跨平台聚合**：整合网易云音乐、哔哩哔哩等平台的播放与账号能力，逐步拓展更多来源。
- **隐私优先**：所有播放记录、搜索历史均保留在本地，不上传任何账号信息或媒体数据。
- **开放演进**：以 Jetpack Compose + Media3 为基础，欢迎社区协作扩展平台、完善体验。

---

## 核心特性 / Key Features
- 🎧 **跨平台音频播放**：同一界面内快速切换网易云、B 站等多平台歌曲来源。
- 🔍 **统一搜索与元数据补全**：聚合多平台搜索接口，自动补齐封面、歌词与曲目信息。
- 🧠 **自研播放器管理器**：`PlayerManager` 统一处理播放队列、缓存、失败重试与洗牌逻辑。
- 🌈 **Material You & Compose**：全局动态色彩、沉浸式背景渲染与可组合式 UI 组件库。
- 🪄 **音频可视化背景**：基于 `ReactiveRenderersFactory` + RuntimeShader 的实时音频反应效果。
- 🔐 **零云端存储**：不上传、不分发任何媒体文件；Cookie 与账号信息仅保存在本地 `DataStore`。

---

## 架构速览 / Architecture Highlights
- **核心模块**：
    - `core/api`：封装各平台 API（网易云、B 站、跨平台搜索等）。
    - `core/player`：基于 Media3 的播放器服务与前台通知，处理音轨解析、缓存策略与状态同步。
    - `data`：使用 Jetpack DataStore 与 JSON 文件维护设置、Cookie、本地歌单等持久化数据。
    - `ui`：Jetpack Compose 构建的多 Tab 导航、Now Playing 页面以及调试工具。
- **依赖注入**：通过 `AppContainer` 实现 Service Locator，集中管理客户端、仓库、搜索服务与播放器实例。
- **扩展方式**：新增平台时实现对应 API/仓库，并在 `AppContainer` 和 `PlayerManager` 中注册即可复用现有能力。

想深入了解实现细节？请阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 中的「目录结构与关键实现」与「扩展指南」。

---

## 快速体验 / Getting Started
### a. 下载 CI 版本 （推荐）
1. 前往 [GitHub Action](https://github.com/cwuom/NeriPlayer/actions) 中点击最后一次构建成功的版本并下载 Artifacts 后解压
2. 前往 [NeriPlayer CI Builds](https://t.me/neriplayer_ci)

### b. 构建本项目
1. 克隆仓库并使用 Android Studio（最新稳定版）打开：
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 同步依赖（首次打开 Android Studio 会自动执行）。
3. 构建调试版：
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. 安装 APK（需要 Android 9+ 设备）：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. 在设置页面连续点击 **版本号** 7 次，即可开启开发者模式与调试入口，用于自测或反馈问题。

发布版构建与签名流程请参阅 [CONTRIBUTING.md](./CONTRIBUTING.md#构建发布版--release-build)。

---

## 发展规划 / Roadmap
- [ ] 视频播放
- [ ] 评论区
- [ ] 清理缓存
- [ ] 添加到播放列表
- [x] 平板适配
- [ ] 歌词悬浮窗
- [ ] 国际化
- [ ] 第三方平台持续扩展（YouTube、QQ 音乐、酷狗音乐等）
- [x] 网易云音乐适配 / NetEase Cloud Music
- [x] 哔哩哔哩适配 / BiliBili

> ⚠️ QQ 音乐的授权有效期过短，无法长期使用。  
> 如果您有可行的解决方案，请在 `Issues` 中提出，我们会尽快评估并适配。

---

## 问题反馈 / Bug Report
- 反馈前请确保已开启调试模式（设置界面点击 **版本号** 7 次）。
- 前往 [Issues](https://github.com/cwuom/NeriPlayer/issues)，按照模板提供：系统版本、机型、应用版本、复现步骤及关键日志。
- 可使用以下命令过滤调试日志：
  ```bash
  adb logcat | grep "[NeriPlayer]"
  ```

---

## 已知问题 / Known Issues
### 网易云音乐 API
- 歌单详情接口存在 1000 首曲目获取上限，目前仍使用 `/api/v6/playlist/detail`。
- 如有更大规模歌单需求，欢迎在 Issues 中讨论改进方案。
### 网络
- 请合理配置代理规则，全局代理可能导致部分接口返回异常数据。

---

## 隐私与数据 / Privacy
- NeriPlayer 仅作为跨平台聚合工具，不上传、不分发、不修改任何音视频文件。
- 不收集任何 **个人身份信息**、**设备信息** 或行为轨迹。
- 播放与搜索记录仅保存在本地；下载文件不会上传或回传给开发者/第三方。
- 不接入第三方统计、崩溃分析或广告 SDK。
- 第三方平台访问日志由对应平台按照其隐私政策处理。

---

## 鸣谢 / Reference
<table>
<tr>
  <td><a href="https://github.com/chaunsin/netease-cloud-music">netease-cloud-music</a></td>
  <td>✨ 网易云音乐 Golang 实现 🎵</td>
</tr>
<tr>
  <td><a href="https://github.com/SocialSisterYi/bilibili-API-collect">bilibili-API-collect</a></td>
  <td>哔哩哔哩-API收集整理【不断更新中....】</td>
</tr>
<tr>
  <td><a href="https://github.com/ReChronoRain/HyperCeiler">HyperCeiler</a></td>
  <td>HyperOS enhancement module - Make HyperOS Great Again!</td>
</tr>
</table>

---

## 更新周期 / Update Cycle
- 仅维护核心功能，其他功能欢迎社区贡献。
- 仓库可能因特殊原因暂停更新。
- 欢迎提交 PR 与反馈！

---

## 支持方式 / Support
- 由于项目特殊性，暂不接受任何形式的捐赠。
- 欢迎通过提交 Issue、PR 或分享使用体验来支持项目发展。

---

## 许可证 / License
NeriPlayer 使用 **GPL-3.0** 开源许可证发布。

这意味着：
- ✅ 你可以自由地使用、修改和分发本软件；
- ⚠️ 分发修改版时须继续以 GPL-3.0 协议开源；
- 📚 详细条款请参阅 [LICENSE](./LICENSE)。

---

# Contributing to NeriPlayer / 贡献指南
感谢你对 NeriPlayer 的兴趣！在提交 PR 前，请先阅读完整的 [CONTRIBUTING.md](./CONTRIBUTING.md)。

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="访问计数 (Moe Counter)">
  <br/>
  <a href="https://starchart.cc/cwuom/NeriPlayer">
    <img src="https://starchart.cc/cwuom/NeriPlayer.svg" alt="Star 历史趋势图">
  </a>
</p>

