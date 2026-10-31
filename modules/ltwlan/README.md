# LAN listen together / 局域网一起听

手机内 Java-WebSocket 服务端 + 注入的 OkHttp 客户端。主控通过回环连接执行与成员相同的 NTP 和播放调度。app 只组装宿主、反馈和 Compose 界面；播放器适配在 `:playback:runtime/core/player/lan`，协议模型在 `:model/data/model/lan`。

## 用户入口

播放页「更多」菜单中，局域网一起听与云端一起听为相邻的同级入口；局域网子页提供创建、加入和管理。「设置 → 一起听」继续直接显示「创建房间」「加入房间」。连接后改为「房间管理」。创建使用当前可共享歌曲；加入支持 Android NSD 自动发现及手动输入主控的 IP:端口。

首版支持网易云、Bilibili 和 YouTube Music 的可解析曲目，主控复用云端一起听的完整音源解析与域名白名单，副设备直接使用主控候选链接；链接只保留在本次会话，不写回普通歌曲。在线音源仍由成员直接下载主控链接；本地音乐由主控提供受限 HTTP 资源，成员接收完整文件、封面及已解析歌词后统一起播，使用内容 SHA-256 校验。暂不支持 USB 独占输出。房间不复制整条播放队列：下一曲/上一曲请求由主控当前队列裁决。主控离开即结束房间，未实现主控转移或进程恢复。

## 时间、音量和状态边界

- NTP 原码固定在 BeatSync `94c38ab16ec861835ce0b4bc3a7db82dabbb9b88`。NTP 参照资源位于 `src/main/resources/beatsync/`；客户端音频脚本、许可证和源码抽取 hash 位于 `src/main/assets/lan-audio/`；分别使用 `build-production.mjs` 和 `build-audio.mjs` 生成。禁止编辑生成脚本或运行来自网络的 JS。
- 客户端 NTP 和音频调度在同一个本地 WebView JS 环境执行；QuickJS 保留为原 NTP 对照测试路径。t0/t1/t2/t3 在原生应用边界采集；16 样本 FIFO、成对探针过滤和最小 RTT offset 沿上游行为。
- 不使用自动变速或周期性漂移 seek。用户 nudge、显式同步、缓冲未就绪恢复会产生离散定位。正 nudge 表示提前，负值延后；在 DataStore schema 中持久化。
- 系统媒体音量使用 `AudioManager.STREAM_MUSIC`。HELLO 上报设备当前音量；主控统一音量是批量设置各成员系统音量，成员音量为单独设置，不再采用全局×成员的播放器增益乘法。成员可修改自己，只有主控可修改别人。音量键变化回传；退出不恢复旧系统音量。
- 房间展示元数据清除 streamUrl/streamUrls/mediaUri。主控候选在装载/调度的独立 sourceUrls 字段下发，副设备不再独立解析。媒体和解码时长校验拒绝试听截断，装载屏障比较完整文件 SHA-256。stableKey 共享云端的模型级身份函数，但不代表文件内容一致或音频采样钟一致。
- 播放计划及装载回报带 revision/generation；旧回调不解锁新计划。房间与客户端各自串行处理状态，离开先使请求代次失效再取消/释放资源。
- 会话使用播放服务、有限时唤醒锁与 Wi-Fi lock。播放服务销毁时释放房间。长时间暂停后的持续在线不作保证。

## 协议与限制

这是 `protocolVersion=2` 的 NeriPlayer LAN 协议，不宣称与原 BeatSync 网站直接互通。保留 NTP、PLAY/PAUSE、SYNC、AUDIO_SOURCE_LOADED、SCHEDULED_ACTION 和 ROOM_EVENT 的核心命名/嵌套结构。增加握手、曲目元数据、细分权限、装载代次和暂停态同步。

房间最多 16 个在线成员；昵称 1–24 字符；单条 JSON 及 WS frame 上限 64 KiB；握手超时 5 秒；服务端输入限速、慢发送队列断连。主控身份只能由进程内创建的随机 token 建立，token 不进入 UI 状态或发现广播。局域网允许发现后直接加入，无邀请确认。

普通断线保持本机 nudge 并重试连接，但新 socket 获得新成员身份与音量快照；默认不授予管理权限。云端房间与 LAN 互斥。

## 验证

```powershell
./gradlew :ltwlan:verifyCrap :ltwlan:lintDebug
./gradlew :app:verifyDomainDependencies :app:assembleDebug
./gradlew :playback:runtime:testDebugUnitTest --tests "*LanPlaybackBindingTest"
./gradlew :app:testDebugUnitTest --tests "*LanRoomEntryTest" --tests "*LanSettingsSearchTest"
```

`LanSessionDeviceTest` 使用 Android 真实网络与 QuickJS、合成播放端口，覆盖会话与控制；它不是实际音频同步精度测试。正式 Debug 应用仍需在两台设备验证发现、解析、暂停/seek、音量键、后台与实际声音输出。

## Web Audio 起播路径

固定版本 BeatSync 的 10 个函数/常量片段和完整 AudioContextManager 由 AST 定位后原样抽取；`schedulePlay`、`schedulePause`、`playAudio`、`pauseAudio` 与位置外推不再用 Kotlin 重写。数据装载先原生下载、MediaExtractor 检查，再 `decodeAudioData`；实际起播用 `AudioBufferSourceNode.start(when, offset)` 提前排程，不经过到点才触发的 `PlayerManager.playImpl()`。

时钟桥接以 16 次原生单调钟往返的最小 RTT 估计映射，误差超过 5ms 拒绝排程；每次计划重新校准映射。输出延迟沿上游读取和过滤，不使用固定 40ms。nudge 默认零；音频上下文中断退出会话，不自动变速或 seek 纠偏。

当前只缓冲一首完整音源，压缩文件上限 200MiB、预计解码缓冲上限 512MiB、最多双声道。解码前按实际 AudioContext 采样率估算；HLS/不可完整获取的分段流明确拒绝。装载窗口为 90 秒；全员未就绪时不强制开始。此处相对上游 3 秒后继续播放是明确的完整预加载适配差异。

已下载会话文件在退出或下一次会话清理；平台鉴权只在原生数据源处理，JS 只读限定的随机本地资源 URL。后台 WebView 存活与实际出声精度分别验证；接口时间戳、文件指纹和进度条一致都不能当作声学同步证据。


## 播放页权限与音频信息

主控的切歌、播放暂停、进度控制三项授权由宿主通过 `playback.lanHostPermissions` 原子保存，首次欢迎消息前恢复。成员按钮、进度条及迷你播放器切歌手势随房间权限变化；服务端授权仍是最终边界。

LAN 切换曲目时清除上首媒体链接和音频展示信息。来源使用房间的 channelId，音质复用原版共享候选链接的质量标记，编码/码率/采样率读取实际下载文件；不使用副设备的音质偏好猜测实际音质。没有可信质量标记时仅显示已测得的文件信息。会话中不开放单机音质切换选项。

统一媒体音量从主控实际 STREAM_MUSIC 音量初始化，并跟随主控系统音量报告；成员加入时保留本机音量，成员单独改音量不改变统一滑块。只有主控显式调整统一滑块时才向全员下发目标值。

本地音乐的设计与验证进度见 [LAN 本地音乐同步接入准备](../../docs/lan-local-audio-preparation.md)。已有双机播放、封面歌词、跳转、暂停恢复及重入验证，完整验收仍在推进。

当前预算为编码音频 200 MiB、Float32 PCM 512 MiB，分别检查；整进程内存还包含压缩副本、临时解码对象与 WebView。连接期间播放页提供直接房间菜单，未连接时仍从更多菜单或设置创建/加入。

房间管理采用紧凑布局：三项成员权限在同一行选择，统一媒体音量留在主页面，成员音量进入独立设备音量子页；本机偏移和归零在主页面最下方。播放页使用局域网图标与房间管理标签直接打开面板。

主控本地共享直接读取原文件或已授权URI，不生成音频快照，不通过HTTP回环下载。只缓存解析后的封面/歌词资源；成员继续原生HTTP接收与校验，主副设备共用同一准备屏障与BeatSync调度。保存点46fbf4b3仍保留此前的快照方案。

原文件直读实验：主控音频不再复制到会话目录，原始文件/URI通过只读能力共享，初次扫描计算身份，传输和解码读取时增量校验；源变化拒绝装载。借用资源退役不会删除用户原文件。按用户决定，不对AAC时长表做专项修正；保留时长一致性校验与解码诊断，解码异常仍明确报错。详见 [诊断记录](../../docs/lan-local-audio-duration-diagnosis.md)。保存点仍为46fbf4b3。
