<h1 align="center">NeriPlayer(音理音理!)</h1>

<div align="center">

<h3>✨ 简易多平台音视频聚合流媒体播放器 🎵</h3>
<p>
  <a href="https://t.me/ouom_pub">
    <img alt="Join" src="https://img.shields.io/badge/Telegram-@ouom_pub-blue" />
  </a>
</p>
<p>
  <img src="icon/neriplayer.svg" width="260" alt="NeriPlayer logo" />
</p>

本项目的名称及图标设计灵感，源自《星空鉄道とシロの旅》中的角色「风又音理」。
<p>
采用原生开发，目前仅针对 Android 9+ 上的设备进行适配，遵循 Material You 设计理念
<p>
🚧 Work in progress / 开发中
</div>

> [!WARNING]  
> 一切开发旨在学习，请勿用于非法用途

# Features / 支持的功能
- `网易云音乐`和`哔哩哔哩`双平台混合本地歌单，告别版权限制
- `本地歌单`和`平台歌单`在线预览，快速导入导出所选音乐
- `本地歌单`支持音乐的**排序**和**删除**
- 当蓝牙/有线设备**断开**时，自动暂停播放当前曲目
- 支持 随机/顺序/歌单循环/单曲循环 播放
- 播放源显示
- 逐字歌词显示
- 正在播放的页面的背景随着音乐律动
- 音频缓存（上限 10G）
- 封面缓存

> [!NOTE]  
> 为保护音乐版权及保障您的使用权益，本软件的音频服务需基于您已登录的第三方平台账号授权，获取其合规音频流。
根据版权协议，会员专属内容仍需遵循原平台的会员规则哦～

# TODO / 暂不支持
- 视频播放
- 评论区
- 清理缓存
- 音频下载
- ~~云存储~~（维护成本有点高）

# Adaptation Status / 三方平台适配情况
- [x] 网易云音乐 / NetEase Cloud Music
- [x] 哔哩哔哩 / BiliBili
- [ ] YouTube
- [ ] QQ 音乐 / QQ Music
- [ ] 酷狗音乐 / KuGoo

> QQ 音乐的授权有效期过短，无法长期使用，目前暂无解决方案，如果你有好的办法可以在 `Issues` 中提出，若可行会尽快适配。

# Issues / 已知问题
### 网易云音乐 API
- 歌单详情接口存在获取上限，目前最多可获取 1000 首歌曲。可能可以通过把请求地址改为 `/weapi/v3/playlist/detail` 来解决，但**考虑到大部分歌单没有超过 1000 首歌曲**，因此暂时采用 `/api/v6/playlist/detail` 来一次性获取。若有需求后续再调整。

# Privacy / 隐私与数据
- 本软件（音理音理!）为跨平台音视频播放工具，仅用于整合并播放来自多个第三方在线平台的公开内容。
  本软件本身不上传、分发或修改任何音视频文件；不在服务器侧存储任何用户内容或第三方内容。
- 本软件不收集任何个人身份信息；
- 本软件不收集任何设备信息；
- 本软件不进行行为跟踪、分析或用户画像；
- 播放记录、搜索记录等仅保留在本地；
- 当用户发起下载时，文件仅保存至本地存储，不会回传开发者或任何第三方；
- 不接入第三方统计、崩溃分析或广告 SDK；
- 第三方平台访问日志由该平台依据其隐私政策处理；

# Reference / 鸣谢
<table>
<tr>
  <td><a href="https://github.com/chaunsin/netease-cloud-music">netease-cloud-music</a></td>
  <td>✨ 网易云音乐 Golang 实现 🎵</td>
</tr>
<tr>
  <td><a href="https://github.com/SocialSisterYi/bilibili-API-collect">bilibili-API-collect</a></td>
  <td>哔哩哔哩 API 收集整理（持续更新中）</td>
</tr>
<tr>
  <td><a href="https://github.com/ReChronoRain/HyperCeiler">HyperCeiler</a></td>
  <td>MIUI & HyperOS enhancement module - Make MIUI & HyperOS Great Again!</td>
</tr>
</table>

# Update Cycle / 更新周期
- 上高中了，更新频率不定，只维护基础功能，其他的交给社区吧
- 欢迎 PR
- 鉴于项目的特殊性，本仓库可能会随时停止更新

# Support / 赞助
- 鉴于项目的特殊性，我们暂不接受任何形式的捐赠，但若您能参与到项目的开发与优化中，将是对我们最大的支持。