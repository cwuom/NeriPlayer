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
</div>


# 🚧 Work in progress / 开发中


# Issues / 已知问题
### 网易云音乐 API
- 歌单详情接口存在获取上限，目前最多可获取 1000 首歌曲。可能可以通过把请求地址改为 `/weapi/v3/playlist/detail` 来解决，但**考虑到大部分歌单没有超过 1000 首歌曲**，因此暂时采用 `/api/v6/playlist/detail` 来一次性获取。若有需求后续再调整。

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

# Support / 赞助
- 鉴于项目的特殊性，我们暂不接受任何形式的捐赠，但若您能参与到项目的开发与优化中，将是对我们最大的支持。