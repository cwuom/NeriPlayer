# 播放器缓存与本项目预加载上限

核对日期：2026-10-30。只记录官方文档可确认的项目；不把磁盘缓存或安装要求当作解码内存限额。

| 播放器 | 官方证据 | 可比较范围 |
| --- | --- | --- |
| Spotify | 官方 storage information 说明缓存歌曲/播客片段与离线下载，建议至少1GB可用空间 | 文档讨论存储管理，不是每首解码PCM配额；未公开固定PCM上限 |
| Apple Music | 官方无损说明称流式无损增加数据量、无损下载占更多设备空间 | 无法据此推断解码缓冲大小；未公开固定PCM上限 |
| MPD | 官方 user.rst 的 audio_buffer_size 默认4 MiB | 内部音频缓冲，可配置；不能类比本项目整首AudioBuffer |
| mpv | 官方 options.rst 的 audio-buffer 默认0.2秒，说明为最小输出缓冲，设备可能更大 | 输出缓冲，不是整首文件或整个播放器总内存 |
| 网易云、QQ音乐 | 本轮未取得可验证的官方固定PCM预算 | 不填写猜测值，不以设置里的缓存空间冒充RAM |

本项目采用完整文件下载加整首Web Audio解码，因此与输出环形缓冲并不等价。当前用户已明确决定编码文件上限200 MiB、解码PCM上限512 MiB，直接实施此固定策略。网络响应、编码副本、解码临时对象、WebView与界面都会额外占内存；本值不是进程总内存承诺。

官方来源（2026-10-30直接读取）：
- https://support.spotify.com/us/article/storage-information/
- https://support.apple.com/en-us/118295
- https://raw.githubusercontent.com/MusicPlayerDaemon/MPD/master/doc/user.rst
- https://raw.githubusercontent.com/mpv-player/mpv/master/DOCS/man/options.rst

限制：闭源播放器未公开内部预算，无法得出“市面播放器普遍给几百MB”的可靠数字。本轮未完成网易云/QQ内部实现分析，不声称已验证其缓冲策略。
