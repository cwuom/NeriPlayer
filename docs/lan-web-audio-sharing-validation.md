# BeatSync Web Audio 与主控音源共享修订

日期：2026-10-28；分支 `codex/lan-beatsync-restart`，基线 `8eee851c`。本记录补充此前的 LAN 接入验证，不把进度日志当作声学精度证据。

## 本轮修改

- 抽取固定上游 `94c38ab16ec861835ce0b4bc3a7db82dabbb9b88` 的 10 个函数/常量片段与完整 AudioContextManager。保留 `schedulePlay/schedulePause/playAudio/pauseAudio/getCurrentTrackPosition` 的源码函数体；用 AST 和 SHA-256 检查来源。
- 客户端 NTP 与 Web Audio 在同一 WebView JS 域运行；原生捕获 WS 时间戳，ClockBridge 映射 `elapsedRealtime` 与 performance 时钟。绝对计划在目标前提交给音频节点；生产路径不再使用固定 40ms 或到期调用 Media3.play。
- Native 负责鉴权下载及媒体元数据检查，JS 只读取受限的本地随机资源地址，不加载远程脚本。仅缓冲一首完整音频，限制 40MiB 压缩体和 192MiB 解码内存；超限/格式不支持明确拒绝。
- 全员下载和解码完成后才起播。相对上游的有意差异：完整预加载窗口为 90 秒，超时不强制开始。
- 按用户纠正，LAN 复用原版云端一起听的 `resolveShareableListenTogetherStreamUrls` 和 `trustedListenTogetherStreamUrls`；主控提供完整音源候选，副设备直接下载，不再调用自身账号解析获取可能的试听。
- `sourceUrls` 只出现在会话装载/调度消息；展示快照和普通 SongItem 不保存链接。LAN 协议升级为 2，旧版本明确拒绝握手，双方需一起更新。
- 解码前后检查实际时长；相对房间元数据超过 max(1秒,1%) 则拒绝。共同装载的完整文件 SHA-256 不一致时取消播放，防止同名不同文件混播。失败后停止 SYNC 重试循环。
- 保留原生创建/加入入口和系统媒体音量；外部音频状态回写现有原生 UI/服务状态流。耳机拔出及系统安全暂停停止外部音频。会话结束释放 WebView 和临时文件。

## 关于副设备 30 秒

用户报告时，副设备状态为 position=30000、LOADING，主控仍在完整歌曲中播放；副设备当时会话缓存约 481KB。抓取后用户已切歌，原文件被替换，不能声称取得了原 30 秒响应的 freeTrialInfo 标记。

代码确认旧 LAN 没有接入原版共享候选回退，副设备独立解析可能获取试听；这是确定的实现缺口。修复后的真机记录：

- 第一首：两端实际 Web Audio 解码时长均为 **252.7490625 秒**。
- 下一首：两端实际解码时长均为 **246.077 秒**。
- 下一首两端下载文件 SHA-256 均为 `cf325b9e5bc518e101e8d608e063ed9b385ab7206af852bcf6f6749aa7db910c`。
- 两端同一房间、nudge=0、正常播放，已观察到播放进度超过 30 秒；暂停/恢复、下一首的计划 revision 能在两端对上。
- 没有清除或改变副设备账号；“不再依赖其解析”由实际代码路径和共享文件一致性证明，不声称做过退出账号实验。

候选链接没有进入调试状态或本文。临时抓取用于 ffprobe 的媒体文件已删除。

## 验证结果

- 原始音频函数的模拟 AudioContext/时钟测试：**16 项通过**，含桥接延迟120ms时目标不漂移、输出延迟过滤、取消、迟到重排、非法位置、解码取消、时长不符和 NTP 收敛。
- `:ltwlan:verifyCrap`、`:ltwlan:lintDebug`、`:app:verifyDomainDependencies`、模块边界：通过。
- `:playback:runtime:lintDebug`：通过；Media3 API 已显式 OptIn。
- 原版链接共享与 LAN 命令相关播放器回归：通过。
- `:app:assembleDebug`：通过。APK SHA-256 `c4226cd0c436428daf201ae69fae336d10da41cde59b06c88549e015443c85c8`，64829717 bytes。

```json
{
  "LAN": {
    "tests": 40,
    "failures": 0,
    "errors": 0
  },
  "Playback focused": {
    "tests": 42,
    "failures": 0,
    "errors": 0
  }
}
```

## 精度验收仍待完成

真实调度记录显示，两个设备针对共同 revision 使用相同 serverTimeToExecute；正常计划提交领先约 0.37–0.39 秒，时钟桥接最小往返约 0–0.1ms（受 JS 计时精度限制），系统报告的 outputLatency 在 32/40ms 间变化。这些只说明排程过程，不证明实际扬声器输出相差多少。

尚未得到同一录音时基下的双机声音测量；30轮起播/恢复/seek的声学 P95≤10ms 及10分钟持续播放指标未验收。新后端的完整后台/焦点/路由矩阵、长音频和分段流也未全部验证。不得以两次ADB读取的进度差作为同步误差。

生产代码已启用新后端；调试入口仅在 Debug Manifest 中声明并受 android.permission.DUMP 保护。没有提交 commit/PR、卸载正式版或清除用户数据。


## 2026-10-29 增量修订：权限、来源与音量

- 播放页、歌词全屏页、房间面板及迷你播放器使用实时 LAN 权限；未授权播放/暂停、切歌和进度条禁用并变灰，迷你播放器横滑切歌也禁用。连接未就绪时不开放旧权限。
- 主控授权作为本机单一设置持久化，房间首次 WELCOME 前恢复。真机强制停止主控进程并重新开房后，三项已开启授权仍然保留。
- 抓取时副设备播放页出现 B 站标识。代码确认 LAN 准备曲目未更新 PlaybackAudioInfo，可能残留上一曲来源或空音质。改为每曲清理旧链接和音频信息，使用房间 channelId、原版共享链接质量标记及实际文件参数重新发布。
- 复用原版 `buildListenTogetherFallbackAudioInfo`、`PlaybackAudioInfo` 和播放页展示组件，不另建音质 UI，不依据副设备偏好虚构等级。选中的候选无质量标记时，仅保留已知文件参数。
- 统一媒体音量使用主控入房时的实际 STREAM_MUSIC 音量；主控系统音量变化更新滑块。成员加入保留本机音量，主动操作统一滑块才调整全员。
- 定向 UI/权限测试 23 项通过，播放器音质/共享/命令回归 46 项通过；随后原版音质构建复用的 3 项回归通过。LAN 核心新增后共 42 项通过，核心 CRAP 门禁、LAN lint、播放器 lint、领域依赖及模块边界已验证。不是全仓库全量测试。
- Windows 实际 DataStore 写入测试曾遇临时文件 rename 失败；JVM 改为验证设置端口的原子编码/恢复，进程重启持久化由 Android 真机验证。
- 用户明确选择先完成软件与双机功能验证，声学测量后续进行；本轮仍不声明达到 10ms 实际出声误差。


最终增量 APK：64842365 bytes，SHA-256 `a46c57fb945648866a767dbd78c8ac982a941eec31d93d23c6a3f72cb28ab23b`。副设备已以 `pm install -r -t` 覆盖安装成功并清理安装临时文件。主控在最终安装前断开 ADB，仍为上一份已验证音源/权限修复的构建，最后的统一音量修复尚未安装到主控，实际音量跟随验证待重连。正式版保留，副设备已确认没有独立旧测试包。
