[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer (音理音理!)</h1>

<div align="center">

<h3>✨ 聚合多平台音源、本地曲库、自建音乐服务器（预览版）与逐字歌词的原生 Android 音频播放器 🎵</h3>

<p><strong>🖥️ 在找 PC 端吗？ <a href="https://github.com/cwuom/NeriPlayer-Desktop">前往 NeriPlayer Desktop 仓库 →</a></strong></p>

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
项目名称与图标的灵感来自《星空鉄道とシロの旅》中的角色「风又音理」。
</p>

<p>
原生 Android 应用，支持 Android 9 (API 28) 及以上设备，
围绕「多源探索、在线播放、本地可控、数据自持」打造。
</p>

🛠️ <strong>Active development / 持续迭代中</strong>

<a href="https://trendshift.io/repositories/23906" target="_blank"><img src="https://trendshift.io/api/badge/repositories/23906" alt="cwuom%2FNeriPlayer | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

> [!WARNING]
> **自建音乐服务器功能目前为预览版本，缺乏充分测试。**

---

> [!WARNING]
> 本项目仅供学习与研究使用，请勿用于非法用途。请只在你拥有权利、授权或平台规则允许的范围内访问、播放或保存内容。
>
> 项目不提供媒体内容、密钥、规避付费 / DRM / 地区限制的方案，也不提供公共云端曲库、媒体代理或再分发服务。
>
> 在线功能依赖你在第三方平台上的账号授权，会员或受限内容须遵循原平台规则。文档中的 YouTube Music 能力仅涉及账号会话、播放兼容与错误恢复。
>
> 本项目及维护者不接受任何形式的赞助、捐赠或商业资助。

---

## 快速定位 / Start here

| 快速导航 | 说明 |
| :--- | :--- |
| [快速体验](#快速体验--getting-started) | 下载 APK、获取 CI 构建或从源码编译 |
| [核心特性](#核心特性--key-features) | 播放、音效、歌词与本地管理 |
| [平台现状](#平台现状--platform-status) | 各平台的支持范围 |
| [连接音乐服务器](#连接音乐服务器) | 接入 Navidrome / OpenSubsonic 自建曲库（预览版） |
| [一起听服务端部署](#一起听服务端部署--listen-together-deployment) | 用 Cloudflare Workers 自建实时同步听歌房间 |
| [docs/implementation.md](./docs/implementation.md) | 架构、模块职责、启动链路与测试 |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | 开发环境、提交流程与贡献指南 |

**核心亮点**

| 能力维度 | 亮点 |
| :--- | :--- |
| **多源在线与自建库** | 网易云音乐、Bilibili、YouTube Music、本地音频与 Navidrome / OpenSubsonic（预览版）共用一个播放队列 |
| **歌词** | LRC / YRC / TTML 逐行与逐字歌词、Bilibili CC 字幕、翻译音译、多源匹配；支持悬浮窗、状态栏、蓝牙 / 车载与系统级输出 |
| **音频与视觉** | Media3 引擎、自研 DSP、10 段 EQ、AutoEQ、32-bit 输出、USB 独占；Material You 与 Clean 主题、流体背景、桌面小组件、启动器快捷方式 |
| **本地优先与自有同步** | 数据默认保存在本地；可选把元数据同步到你自己的 GitHub 仓库或 WebDAV |
| **可恢复运行** | 脱机模式、安全模式、JVM / Native 崩溃日志、ANR 记录与调试探针 |

## 项目简介 / About

NeriPlayer 是基于 **Jetpack Compose + Media3** 的原生 Android 音频播放器，把 **网易云音乐**、**Bilibili**、**YouTube Music**、**自建音乐服务器** 与 **本地音乐** 整合进统一的播放与管理体验。本地播放无需任何账号，在线功能使用你在对应平台上的账号授权。

- **自建音乐服务器（预览版）**：连接 Navidrome / OpenSubsonic，在“资源库 → 音乐服务器”浏览与搜索专辑、单曲、歌手，复用现有播放器、封面与歌词，支持本地收藏、歌单与原文件离线下载（见[连接音乐服务器](#连接音乐服务器)）。
- **本地优先**：缓存、下载文件、歌单、历史记录、设置与授权信息保存在设备本地。
- **可选同步**：歌单、收藏、最近播放与播放统计可同步到你自己的 GitHub 仓库（应用内新建时默认私有）或 WebDAV。
- **数据自持**：同步数据只写入你控制的远端，不经过维护者服务器。
- **账号安全**：为避免触发第三方平台的风控，NeriPlayer 不向第三方平台回写播放历史和统计。

> 💡 **三者相互独立**
>
> - **音乐服务器**：接入你自建的 Navidrome / OpenSubsonic 曲库。
> - **一起听服务端**：在多台设备间实时同步播放状态。
> - **GitHub / WebDAV 同步**：同步歌单与统计等元数据，不传音频文件，也不含音乐服务器配置与凭据。

架构、启动链路与测试说明见 [docs/implementation.md](./docs/implementation.md) 与 [CONTRIBUTING.md](./CONTRIBUTING.md)。

### 连接音乐服务器

> [!WARNING]
> 预览版，缺乏充分测试，兼容性与稳定性因服务器、曲库和网络环境而异。服务器回写、一起听和配置迁移尚未接入。

1. 在“设置 → 第三方平台登录 → 音乐服务器”填写名称、地址、用户名和密码，连接检查通过后保存。
2. 地址填服务器基础地址，如 `https://music.example.com:4533/navidrome/`，可含端口和反代子路径，不要带 `/app/` 或 `/rest/`。
3. 打开“资源库 → 音乐服务器”，选择配置后浏览或搜索专辑、单曲、歌手，点击歌手查看其专辑。
4. 切换分类时保留关键词，专辑内搜索只筛选当前专辑。
5. 点击歌曲播放，或收藏、加入队列、加入本地歌单。
6. 歌词优先读取服务端结构化歌词，不支持时使用标准歌词接口。
7. 长按歌曲或多选后点击“下载到本地”，服务器允许时按原文件格式保存，进度与重试由下载管理负责。
8. 下载完成后可在本地音乐的“已下载”中播放，断网或移除服务器配置后仍可使用。

**机制说明**

- **目录缓存**：首次打开会加载并缓存专辑、单曲、歌手三类完整目录，搜索基于本地目录，缓存 24 小时内复用。
- **手动刷新**：曲库变化后可手动刷新，刷新失败时保留已有目录。
- **媒体加载**：目录缓存只含元数据，封面按需加载，音频在播放或下载时获取。
- **歌手头像**：优先使用服务器图片，无图时取该歌手最后一首有封面歌曲的封面。
- **凭据**：配置和密码保存在本机加密存储中，普通同步不迁移。
- **编辑配置**：只能修改名称和密码；更换地址或用户名需新建配置，原歌单条目不会迁移。
- **封面同步**：封面以不含认证信息的接口地址同步，私有服务器仍需登录。
- **多端使用**：另一台 Android 设备添加相同地址和用户名后即可加载封面，桌面端暂只保留地址。

协议与客户端约定见 [API 文档](docs/music-server-api.md)。

---

## 核心特性 / Key Features

### 🎧 播放与音质

- **多源与本地播放**：网易云音乐、Bilibili、YouTube Music 与本地音频共用一个播放队列。
- **播放兜底**：网易云音乐不可播时先降音质；开启兜底后按歌名、歌手与时长匹配 Bilibili 候选，连续失败自动跳过。
- **YouTube Music 兼容**：保留并轮换有效身份 Cookie；缓存的 `player.js` 或播放候选失效时回退到 EJS / HLS。
- **音效**：自研 DSP 提供内置音效、10 段均衡器、AutoEQ 导入、响度均衡与 32-bit 高解析输出，关闭后完全旁路。
- **USB 独占**：支持 UAC1.0 与兼容 UAC2.0 Type I PCM 的 DAC，比特完美输出，可单独关闭插入响应。
- **后台播放**：切到其他应用或从最近任务划掉后，音乐通过媒体前台服务继续播放。
- **悬浮窗保活**：可选，用不可见的 1 像素窗口降低后台播放被系统冻结的概率。
- **播放行为**：淡入淡出、切歌交叉淡入淡出、蓝牙断连暂停、混音播放与音频焦点策略，启动时恢复上次进度与播放模式。
- **Bilibili 跳过**：SponsorBlock 自动跳过与自定义区间，默认关闭，一起听期间不生效。

### 🎤 歌词

- **逐行与逐字**：支持 LRC / YRC / TTML，含高亮、翻译、音译、偏移、点击跳转与全屏歌词。
- **CC 字幕歌词**：Bilibili 歌曲可使用视频 CC 字幕，可选字幕轨道，也可回退到自动字幕。
- **来源与编辑**：按偏好自动选择网易云音乐 / 酷狗 / QQ 音乐 / LRCLIB / AMLL，支持手动匹配与编辑。
- **外部输出**：悬浮歌词、状态栏歌词、SuperLyric、Lyricon、蓝牙歌词与车载显示。
- **ColorOS 锁屏岛歌词**：实验功能，需要 Root 或 Shizuku，视 ROM 支持而定。

### 🗂️ 媒体库与本地管理

- **收藏到网易云歌单**：使用网易云音乐音源播放时，在播放页右上角三点菜单选择“收藏到网易云歌单”，选中歌单即可加入。
  - 需要登录并联网。
  - 切换到 Bilibili 或本地兜底音源后，入口隐藏。
- **分类浏览**：本地、收藏、网易云音乐、YouTube Music、Bilibili 入口；YouTube Music 可完全禁用，Bilibili 音乐可单独关闭首页分区。
- **首页推荐**：最近常用歌单、榜单、新歌、日推、私人 FM、精品歌单与雷达歌单，刷新会更新全部分区。
- **搜索**：按平台独立搜索，网易云音乐与 Bilibili 支持触底分页。
- **链接识别**：粘贴含标题或短链接的分享文案，即可识别网易云音乐、Bilibili 与 YouTube Music 内容。
- **导入与批量词图**：支持分享导入与文件夹扫描，可为本地歌曲一键批量匹配并嵌入歌词与封面。
- **本地歌手与歌单**：按展示艺术家聚合，支持排序、整体插入与删除撤销。
- **歌曲百科**：曲风、语种、BPM、创作团队、评论预览、相似歌曲与相关歌单。

### ⬇️ 下载、缓存与存储

- **应用内下载**：多平台音频下载，支持断点续传与启动恢复，默认并发 6（可调 1–8）。
- **自定义目录**：支持 SAF 目录与文件名模板，切换时自动迁移已有下载。
- **缓存上限**：音频缓存默认上限 1 GB。
- **保留最近播放**：用 0–1000 首滑块保留最近播放的歌曲，当前播放的歌曲始终受保护。
- **本地导入缓存**：在“设置 → 存储与缓存 → 清除缓存”中清理无引用的提取封面与临时扫描索引。
- **封面保留**：历史、统计等仍在使用的封面会保留，本次启动已用到的封面在下次冷启动时再判断。

### 🔄 同步、统计与备份

- **GitHub / WebDAV 同步**：歌单、收藏、最近播放、删除记录与播放统计保存在你自己的远端。
- **播放统计**：按歌曲记录播放次数与时长，支持日 / 周 / 月 / 年视图。
- **流量统计**：区分 Wi-Fi、移动网络与漫游。
- **备份与恢复**：歌单 JSON 导入导出。
- **配置导出**：默认不含登录态，可选择加密并包含授权信息。

### 🎨 界面与个性化

- **双界面风格**：Material You 与预览版“简洁（Clean）”，后者支持液态玻璃效果。
- **主题与显示**：动态取色、自定义背景、歌词字号与 UI 缩放（手机 0.6–1.2x，车载 0.6–1.5x）。
- **流体背景**：播放页随封面取色的 `RuntimeShader` 背景，响应音频（Android 13+）。
- **桌面与车机**：三种尺寸的小组件、启动器快捷方式与蓝牙车载信息发布。
- **大屏适配**：平板横屏固定显示完整歌词分栏，手机横屏可左右切换封面与歌词。

### 👥 一起听

- **统一入口**：播放页「更多 → 一起听」顶部通过滑块切换「云端」「局域网」，平板迷你播放器和设置页也使用同样的分组。
- **房间同步**：创建或加入房间，通过 WebSocket 实时同步。
- **加入方式**：首次加入需要邀请密钥，重连使用成员密钥。
- **同步内容**：房主 / 听众权限、循环与随机模式、时钟偏移校正与房主离线恢复。
- **播放候选**：可选共享房主的播放候选（最多 3 条），听众优先按本机音质策略解析。

- **局域网一起听**支持在同一 Wi-Fi 下自动发现房间或按地址加入。通过播放器底部音响按钮、播放页「更多」或「设置 → 一起听」管理房间，共享音频、封面和歌词。成员默认可播放和暂停。「进度」权限同时控制切歌与拖动进度，默认开启。成员也能查看统一媒体音量、调整自己设备的音量和同步偏移。房主开启成员音量权限后，成员还能拖动统一音量条调节全员音量，或单独调节房主设备。房主的权限设置会保存在本机。局域网房间与云端房间独立，暂不支持 USB 独占输出。详见[模块说明](modules/ltwlan/README.md)。

### 🛟 稳定性与诊断

- **脱机模式**：自动感知网络状态，脱机时只使用本地文件与缓存。
- **安全模式**：JVM / Native 崩溃或系统 ANR 后进入安全模式，可导出日志。
- **开发者模式**：连点版本号 7 次，启用调试探针与日志查看器。

---

## 快速体验 / Getting Started

### a. 下载 Release 版本（推荐）

前往 [GitHub Releases](https://github.com/cwuom/NeriPlayer/releases)，下载对应架构的 APK。

| APK 架构 (ABI) | 适用设备 |
| :--- | :--- |
| `arm64-v8a` | 绝大多数现代手机与平板（推荐） |
| `armeabi-v7a` | 较老的 32 位 ARM 设备 |
| `x86` / `x86_64` | 桌面模拟器、Intel 设备或 Chromebook |

> [!IMPORTANT]
> Release 在一批功能完成后手动发布，可能包含尚未发现的问题。

### b. 下载 CI 版本

1. 前往 [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions)，下载最近一次成功构建的 Artifacts 并解压。
2. 或访问 Telegram 频道 [NeriPlayer CI Builds](https://t.me/neriplayer_ci)。

> master 分支 CI 默认上传 `arm64-v8a` APK，手动 Release 流程构建多 ABI APK。

### c. 本地构建

1. 克隆仓库并初始化子模块：
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. 安装 JDK 17，用最新稳定版 Android Studio 打开并同步依赖。
3. 运行 `:playback:runtime` JVM 测试或全量模块门禁时，另需 JDK 21（生产字节码目标仍为 17）。
4. 构建并安装调试版：
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. 首次启动阅读免责声明并完成引导，通知与本地音乐权限在你主动点按后才会申请。
6. 需要调试工具时，在设置页连续点击 **版本号** 7 次启用开发者模式。

**DEBUG 构建说明**

- 仅用于测试，性能和体积与发布版不同。
- 包名为 `moe.ouom.neriplayer.debug`，使用橙色启动图标，名称带 `DEBUG` 标记，可与 Release 共存。
- 分享的一起听邀请使用 `neriplayer-debug://`，也可粘贴普通邀请链接加入。
- 完成引导后会提示 DEBUG 构建风险，确认后不再提醒，清除数据或重装后再次提示。

在 Android Studio 中运行时，选择 `app` 运行配置，并确认 Build Variants 中 `:app` 为 `debug`。
更新包名配置或切换分支后，先执行 **Sync Project with Gradle Files**，否则 IDE 可能仍沿用旧的启动包名，导致启动已安装的正式版。

发布版构建与签名流程见 [CONTRIBUTING.md](./CONTRIBUTING.md#构建发布版--release-build)。

---

## 平台现状 / Platform Status

### 网易云音乐

- 登录、搜索、精选歌单、专辑、云盘、播放、下载、歌词与元数据补全。
- 无权限时播放兜底，本地收藏可同步到“我喜欢的音乐”。
- 艺术家详情、歌曲百科与关注。
- 评论支持热门 / 最新 / 推荐排序、楼中楼与复制，登录后可点赞、发评与回复。
- 受原平台账号权限与规则约束。

### Bilibili

- Web / 二维码登录、视频搜索、创建与订阅收藏夹、合集。
- 分 P 视频转音频播放与下载。
- 首页提供音乐热门榜、原创榜与热门视频。
- 链接识别保留分 P，并能从 `season_id` 恢复合集上下文。
- 评论支持热门 / 最新排序与楼中楼，登录后可互动。
- 以音频播放为主，不提供完整视频流。

### YouTube Music

- 登录、首页 / 媒体库歌单浏览、歌单详情、搜索与播放兼容。
- 内容访问受平台规则与账号权限约束。

### 自建音乐服务器（Navidrome / OpenSubsonic）

- 浏览和搜索专辑、单曲、歌手，查看歌手专辑。
- 复用现有播放器、封面与歌词。
- 支持本地收藏、歌单操作和原文件离线下载。
- 预览版，缺乏充分测试；服务器回写、一起听和配置迁移尚未接入。

### QQ 音乐

- 播放页元数据与歌词补全。
- 不支持登录、播放与库页数据。

### 本地音频

- 外部分享 / 打开导入、设备与文件夹扫描、播放。
- 歌手分类、分享与歌单管理。

在“我喜欢”或本地歌单中，联网时点按从桌面端同步的 Bilibili 多分 P 视频，即可选择要播放的分 P。

---

## 实现概览 / Implementation Notes

构建版本、模块边界与关键实现路径见：

- [docs/implementation.md](./docs/implementation.md)：构建与版本、15 个自有库的职责、入口与导航、播放与缓存、搜索与数据来源、本地数据与安全、下载与备份。
- [CONTRIBUTING.md](./CONTRIBUTING.md#项目结构与当前实现--project-layout)：模块边界、扩展路径、测试与提交要求。

---

## 一起听服务端部署 / Listen Together Deployment

NeriPlayer 内置“一起听”，你可以部署自己的服务端，也可以使用他人部署的服务。

- 仓库内源码：`np-submodule/NeriPlayer-LTW`
- 公开部署模板：[TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

服务端基于 **Cloudflare Workers** 与 **Durable Objects**，通过 WebSocket 实时同步。

### 一键部署到 Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

应用内的设置页可配置服务端地址、测试可用性并重置本机一起听身份。

- 房间号为固定 6 位可读字符。
- 昵称长度 1–24，允许中文、英文字母和数字。
- 首次加入必须使用邀请链接中的密钥。
- 云端房间仅支持在线歌曲。局域网房间也支持本地音频。

完整的协议、事件与部署细节见 [np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md)。

---

## 同步 / Sync

NeriPlayer 可把本地元数据同步到**你自己的** GitHub 仓库或 WebDAV 远端文件。应用内新建的 GitHub 仓库默认私有，也可接入已有仓库。

**同步对象**：本地歌单、收藏歌单、最近播放记录、最近播放删除记录与播放统计。

### 使用方法

1. 打开设置页中的备份与同步。
2. 使用 GitHub 时，创建带 `repo` 权限的 Personal Access Token。
3. 完成 Token 校验，选择新建默认私有仓库或接入已有仓库。
4. 开启自动同步，或手动点击立即同步。

新安装直接使用 V4。若出现升级弹窗，先更新所有参与同步的设备，再确认并立即同步以迁移地址。

### 行为说明

- 🔒 **凭据**：Token 与 WebDAV 密码保存在 `Android Keystore + EncryptedSharedPreferences` 中。
- ⚠️ **传输**：使用 HTTP 地址时会提示明文传输风险。
- 🔄 **调度**：变更后延迟 5 秒同步，另有每小时一次的周期同步。
- 🔀 **合并**：对歌单、历史、统计与删除记录做三路合并，属于后台双向同步，不是实时推送。
- 📦 **远端格式**：V4 以 ZSTD 分块增量上传。
- 🧷 **写入保护**：优先使用强 ETag 条件写入，坚果云等服务改为核对清单指纹。
- 🔓 **加密**：远端文件未做端到端加密。

---

## 发展规划 / Roadmap

规划会随维护精力、平台可用性和社区反馈调整。

- [ ] 视频播放
- [ ] 持续扩展第三方平台的播放、库页和账号能力
- [ ] 完善 QQ 音乐账号能力、库页数据与授权链路

已落地的功能见 [Releases](https://github.com/cwuom/NeriPlayer/releases) 与提交历史。

---

## 问题反馈 / Bug Report

- 反馈前先开启开发者模式（设置页点击 **版本号** 7 次），应用会启用普通文件日志，崩溃日志单独保存。
- 前往 [Issues](https://github.com/cwuom/NeriPlayer/issues)，提供系统版本、机型、应用版本、复现步骤与关键日志。
- Windows 过滤日志：`adb logcat | findstr NeriPlayer`
- Linux / macOS 过滤日志：`adb logcat | grep NeriPlayer`

---

## 已知问题与能力边界 / Known Issues

### 网络

- 请为第三方平台配置分流规则，全局代理可能导致部分接口返回异常数据。

### 下载

- 使用应用内下载管理而非系统下载服务，支持断点续传与启动恢复，受应用后台生命周期约束。
- 手动取消会清理断点与未完成文件，仅网络策略暂停或可恢复错误会保留续传状态。
- 自定义 SAF 目录便于外部访问，但扫描、迁移与写入慢于应用私有目录。

### USB 独占

- 需要兼容的 DAC 与前台服务。息屏后中断时，请按设置页提示放开电池与后台限制。
- UAC2 异步模式要求唯一的显式反馈端点和受支持的时钟拓扑，暂不支持隐式反馈与无法验证的拓扑。
- 使用系统音频输出时请关闭 USB 独占。
- 手机与 DAC 的兼容性需实机验证。

### 音频输出

- 开启 32-bit 高解析输出后，响度均衡、声道平衡、音频可视化与应用内倍速会被旁路，需要这些功能时请关闭该开关。

### 蓝牙车载

- AVRCP 协议没有独立歌词字段，车载歌词借用曲目标题区域显示，刷新率与封面显示取决于手机系统和车机。
- Android Auto 使用车机原生界面，项目不提供独立的 Android Automotive OS 版本。

### 歌词

- 音译需要平台歌词或内嵌标签自带音译数据。
- 生成的歌词卡片保存在应用缓存目录，可通过清理缓存释放。
- ColorOS 锁屏岛歌词为实验功能，依赖厂商系统与 Root / Shizuku，系统升级后需重新确认可用性。

### 平台能力

- Bilibili 侧重搜索、收藏夹、合集与音频播放，不提供完整视频推荐流。
- QQ 音乐仅用于播放页元数据与歌词补全。

### 同步与导出

- 远端同步文件未做端到端加密。
- 配置导出默认不含登录态；包含授权信息时请设置加密密码并妥善保管，密码不写入导出文件，遗忘后无法找回。
- 多端并发更新同一分支出现分叉时会提示冲突，不会强制覆盖远端提交。

### 其他

- 长音频进度记忆不覆盖你手动指定的起播位置，也不回写第三方平台的播放历史。
- Bilibili SponsorBlock 通过公开接口查询跳过区间，在一起听房间内自动禁用。

---

## 隐私与数据 / Privacy

- 不提供自己的公共云端媒体分发服务，不接入广告、第三方统计或崩溃分析 SDK。
- 同步目标由你自己选择和保管，个人媒体数据不会汇聚到维护者控制的平台。
- 缓存、下载文件、本地歌单、历史记录、播放统计、设置与授权信息保存在设备本地。
- 只有你主动开启 GitHub 或 WebDAV 同步时，才会同步歌单、收藏、历史和播放统计等元数据。
- 音频缓存、下载文件、Cookie 与播放 Token 不会上传给开发者。
- 出于账号安全考虑，本地播放历史和统计不会回写到第三方音乐平台。
- 配置导出默认不含登录态；选择包含时会携带授权与同步凭据，请设置密码并妥善保管。
- 默认关闭 Android 系统云备份与设备迁移。
- 第三方平台的访问日志与风控策略，由对应平台按其隐私政策处理。

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

- **参与贡献**：请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。核心播放、本地数据、同步与恢复链路优先维护，Release 按功能批次手动发布。
- **支持项目**：暂不接受任何形式的捐赠，欢迎通过 Issue、PR 或分享使用体验支持项目。
- **许可证**：NeriPlayer 使用 **GPL-3.0** 发布，可自由使用、修改和分发，分发修改版时须继续遵守 GPL-3.0，详细条款见 [LICENSE](./LICENSE)。
- **Native 源码授权**：[说明](./modules/native/src/main/cpp/README.md)中列出的 NeriPlayer 自有 Native 源码另有附带署名条件的替代授权，第三方源码与未列入的内容不适用。
- **外部 Native 贡献**：提交 PR 不会自动纳入该授权，只有明确记录双授权同意时才适用。
- **第三方代码**：小米超级岛歌词的 Shizuku / XMSF 网络隔离适配移植了 GPL-3.0 的 Capsulyric 源码，见 [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md)。

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
