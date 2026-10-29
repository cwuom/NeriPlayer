# 局域网一起听：组件联网调研与选型修订

> 调研窗口：北京时间 2026-10-27 深夜至 2026-10-28 凌晨。Star 为查询时快照，不是持续更新值。
> 工作区：`codex/lan-beatsync-restart`，基线 `8eee851c`。本轮仅新增调研文件，没有改动生产依赖或功能代码。
> 依据：GitHub 官方 API 的 24 个仓库元数据、项目官方 README / 发布记录、Android 与 Ktor 官方文档；另补查 Rhino。
> 原始记录在 `research/beatsync-components-2026-10-27/`。文件夹日期表示采集开始日期。

## 1. 调研后的建议

| 能力 | 建议 | 对原设计的影响 |
|---|---|---|
| WS 客户端 | 继续使用工程已有 OkHttp | 保留 |
| 手机内 WS 服务端 | 首选 Java-WebSocket；Ktor CIO 作为实测对照 | 暂不因热度更换，补充对照门槛 |
| 局域网发现 | Android 原生 NSD / DNS-SD 优先；手动地址兜底；UDP 作为必要时的兼容后备 | 调整原来以自定义 UDP 为唯一发现方式的设计 |
| JSON 协议 | 继续 kotlinx.serialization，保留 BeatSync 消息结构 | 不为更多 star 改用 Gson 或 Protobuf |
| JS 核心复用 | 在大规模 Kotlin 翻译前，增加嵌入 JS 的小规模验证 | 原方案过早把 Kotlin 移植当作默认唯一实施路线，需要保留原码运行机会 |
| NTP / 调度 | 继续复用固定版本 BeatSync 的成对探针和算法 | 公网校时库不能直接替换房间相对时钟算法 |
| 播放后端 | 先保留 Media3 / PlayerManager | 不把低层音频库当成无成本替换 |
| 音频验证 | 引入 OboeTester 作为外部测量工具，并做实际多设备录音 | 增加验证能力；不意味着产品引入 Oboe |
| 后台生命周期 | 复用已有 Media3 播放服务 | 会话资源随服务管理；不能承诺暂停状态永久保活 |
| 全套同步音频替代 | Snapcast 留作改变产品前提时的候选 | 当前不采用统一音频分发路线 |

这里的“首选”和“候选”属于针对当前工程的设计判断。尚未跑 Android 包体、耗电、GC、网络抖动或音频精度对比，不能把功能列表当成性能实验结论。

## 2. WebSocket：高 star 候选并未推翻原组合

| 项目 | Star 快照 | 核查结果 | 当前判断 |
|---|---:|---|---|
| OkHttp | 47,100 | JVM / Android 客户端；工程已依赖 5.4.0 | 保留客户端 |
| Netty | 35,066 | 通用异步网络框架，需自行组织 pipeline 与业务接口 | 不为一个 LAN 房间引入更多框架层；不宣称其延迟更大 |
| Ktor | 14,555 | 官方支持服务端 WebSocket 与 CIO/Netty 等引擎，latest release API 返回 3.6.0 | 保留 CIO 对照原型，验证手机部署、收包时间戳可见性和资源占用 |
| Java-WebSocket | 10,803 | Java NIO 的 WS 服务端/客户端；latest release API 返回 1.6.0 | 首选手机内 WS 服务端 |
| AndroidAsync | 7,523 | Android 异步网络能力；仓库 pushed_at 为 2022-11-23 | 对比优先级下降；旧日期本身不代表不能使用 |
| NanoHTTPD | 7,236 | 嵌入式 HTTP / WS；pushed_at 为 2023-07-25 | 缺少足以推动替换的收益 |

Ktor 官方服务器引擎表列出 JVM/Native 等平台；这不足以证明当前选定引擎在我们的全部 Android 版本与构建配置下开箱即用，也不能据此断言不能运行。需要实际构建和设备验证。[S1][S2]

Java-WebSocket README 与 POM 明确使用 SLF4J。原设计“零传递依赖”应删除。它与 OkHttp 的发送调用也不能当作网卡实际发送时刻；仍需在应用边界取时间戳并量化排队误差。[S3]

对照原型必须使用相同的消息量、编码器、探针间隔和设备。比较 cold start、包体增量、线程/内存、消息接收至响应入队的 P50/P95/P99、纯探针对比例、后台重连和连续建退房资源释放。先比较现有组合与 Ktor CIO，不同时引入三套生产实现。

## 3. 发现：优先采用 Android 自带 NSD

Android 官方 NSD 使用 DNS-SD 注册、发现和解析局域网服务，适合发布房间名、地址、端口及少量描述信息。[S4]

建议实现：

1. 主控启动 WS 服务成功后，注册 `_neriplayer._tcp` 服务，TXT 中仅放 roomId、协议版本和必要展示信息。
2. 成员发现并解析服务后，再走原有 WS 握手与权限校验。发现结果不充当认证依据。
3. 房间列表按 roomId 合并，排除本机；监听网络切换与服务丢失。不能把原 UDP 的固定 3 秒过期规则直接视为 NSD 事件语义。
4. 保留手动地址/端口加入；路由器环境确需 UDP 后备时再启用，避免默认并行跑两套发现流程。
5. NSD、UDP 和手动地址都无法绕过网络隔离；拒绝权限与无法连通要呈现为具体状态。

JmDNS 为 670 star，提供 Java mDNS/DNS-SD，但在 Android-only 首版中没有证明额外引入它优于平台 API。系统组件不以 GitHub star 计分。

联网核查还发现新的平台约束：官方文档说明 targetSdk 37+ 的 Android 17 本地网络保护采用 `ACCESS_LOCAL_NETWORK`；targetSdk 36 及以下有不同兼容行为。当前项目是 target 36，应按实际 target/设备组合实现，不提前照搬新权限。普通 NSD 不自动免除局域网权限；系统服务选择器有专门授权路径，但不能据此豁免手机服务器监听与所有后台扫描。[S5]

## 4. 序列化：保留现有 Kotlin Serialization

| 候选 | Star | 结论 |
|---|---:|---|
| Protobuf | 72,132 | 编码方案备选，但当前为保留 BeatSync JSON 语义与便于对拍，不换 wire 格式 |
| Gson | 24,234 | 官方明确处于维护模式，提醒 Kotlin 语义与 Android 反射/R8 问题，不作为新协议默认方案 |
| Moshi | 10,166 | 可用替代，但没有足够收益为现有 Kotlin Serialization 再加一套 codec |
| kotlinx.serialization | 5,949 | Kotlin 编译期序列化，工程已有 JSON 和 Protobuf 依赖；继续用于新协议 |

此处按协议兼容和工程成本选型。Protobuf 热度高并不构成改写 BeatSync 协议和测试夹具的理由；若以后实测消息体或解析成本成为瓶颈，再单独评估。[S6][S7]

## 5. 直接复用 TypeScript：值得补做验证

| 候选 | Star | 可复用机会 | 当前限制 |
|---|---:|---|---|
| QuickJS 上游引擎 | 11,076 | 运行编译后的 JS 核心 | 引擎本身不等于 Android/Kotlin 桥接库 |
| Rhino | 约 4,600（网页显示） | Java 实现，可作为减少 native 集成的对照 | 需验证目标 Android、JS 特性、转译输出与运行成本；主分支 Java 版本要求也需核对 |
| Zipline | 2,313 | 官方 QuickJS 嵌入与 Kotlin 接口桥接，支持 Android | 主要面向 Kotlin/JS；不能把 TS 工程直接投入即用 |
| quickjs-kt | 155 | API 更直接面向运行 JS 与 Kotlin 协程桥接 | 使用面较小，按用户偏好降低默认优先级，不能因方便就跳过维护评估 |

Zipline README 明确指出其实现对 Kotlin 编译器、serialization 等版本敏感；展示的支持表与当前工程 Kotlin 2.4.10 不同。不能把“Android 可用”当作版本完全兼容，也不能未经构建就断言不兼容。[S8][S9]

建议把首个验证限定为原始 `ntp.ts` 中的测量/过滤函数与服务端 `calculateScheduleTimeMs`：

- 固定 BeatSync commit，TypeScript 只做语法转译和显式宿主依赖抽取；纯算法不改写。
- JS 只计算测量结果、状态和动作意图；原生持有 socket、单调钟、实际 deadline 调度和播放器。
- 网络回调先在原生侧捕获 t1/t3，再传入 JS；不要到 JS 获得执行机会才记录接收时间。
- 比较原 TS 测试结果、host/JS 数字精度、调用延迟分布、取消与重连、API 28 起的运行情况、release/R8 构建和包体增量。
- 先评估 Zipline/QuickJS 与 Rhino 两条边界；若都带来明显额外改造或无法满足时序，再采用 Kotlin 逐函数移植。

这是本次对旧方案最重要的修订：用户希望尽量复用代码，应先用很小的验证证明“直接运行原算法”是否合适，再决定全量语言翻译。验证尚未执行。

## 6. 对时与同步方案

TrueTime（1,470 star）解决 Android 的真实日期/时间获取；Apache Commons Net（298 star）提供网络协议实现。它们不能直接给出 BeatSync 的主控相对时钟、成对探针污染过滤和补偿调度语义。当前保留 BeatSync 算法，不增加公网 NTP 依赖。[S10]

Snapcast（8,036 star）更值得研究完整音频同步架构：它从服务器读取 PCM、编码并带时间戳发送，客户端解码、缓存并按服务器时间输出。其文档还描述通过移除/复制采样来修正偏差，并提供 Android 音频后端。采用它意味着评估音频分发、缓冲、持续校正和新的播放链，已经改变当前“各端独立播放、零自动纠偏”的前提。[S11]

Ableton Link（1,352 star）同步节拍、速度与相位。它并非现成的曲目身份、seek、暂停和音频内容同步方案，因此不替换当前房间逻辑。[S12]

查询 `sendspin/sendspin` 时 GitHub 重定向到 `Sendspin/sendspin-python-cli`（205 star）。该计数属于 CLI 项目，不能拿来代表整个 Sendspin 协议生态；本轮仅作旁支记录，不据此作核心替换结论。

## 7. 播放与后台：保留产品链，增加测量工具

Media3（3,055 star）提供现有播放器和媒体会话能力；Oboe（4,128 star）是 C++ Android 音频库。两者抽象层不同。当前保留 PlayerManager/Media3，并优先使用 Oboe 仓库中的 OboeTester 测量延迟与 glitches；工具结果也不能代替我们实际播放器的多设备输出录音。[S13][S14]

若测量证明播放器起播路径是主要瓶颈，再做 Oboe 后端原型，同时评估解码、格式、缓存、路由、USB 与现有播放器功能的接入成本。首版不因更低层 API 的存在而直接重写播放器。

后台继续采用已有 MediaSessionService / MediaLibraryService 生命周期。官方说明播放暂停、停止或失败超过 10 分钟且无进一步交互时，服务会退出前台状态，随后可能被系统销毁。因此“挂到播放服务就能永久维持暂停房间”不能作为设计保证。需要验证暂停房间策略和退出/恢复行为，不引入所谓无限保活库。[S15]

协程继续使用已依赖的 kotlinx.coroutines（13,822 star），管理单线程事件顺序、取消和资源生命周期；其作用不等同于硬实时音频调度。

## 8. 修订后的实施验证顺序

1. 固定原始源码与许可证；建立可运行的 BeatSync TS 参照测试。
2. JS 嵌入微型验证：原算法、宿主时钟、桥接、取消与设备构建；据结果选择 JS 核心或 Kotlin 移植。
3. WS 闭环与 Ktor CIO 对照：同一套消息、探针、设备和统计，不同时改播放器。
4. NSD 发现与手动加入；覆盖 Wi-Fi、热点、切网以及目标 SDK 权限组合。
5. 接入现有播放器，运行 OboeTester 辅助测量和真正的多设备输出采集。
6. 验证后台播放、暂停超过 10 分钟、主控退出、断线恢复与资源释放。

本次不进行依赖升级。GitHub latest release API 返回 Java-WebSocket 1.6.0、Ktor 3.6.0、Zipline 1.28.0、quickjs-kt 1.0.15、Oboe 1.11.0、Media3 1.11.1；这只是查询结果，不代表它们组成已经验证兼容的一套版本。OkHttp 的 latest release endpoint 返回 404，不据此断言项目没有版本发布。

## 9. 来源与数据限制

Star、archived、pushed_at 由 GitHub API 采集。pushed_at 包含仓库各类推送，不能独自证明发布质量、维护承诺或功能健康。原始 JSON 保留 requested repo 与 canonical repo，避免旧地址重定向后张冠李戴。

本次 API 返回 `square/okhttp → lysine-dev/okhttp`、`badaix/snapcast → snapcast/snapcast`；统计按返回的项目身份保存。项目名称用于正文，不因地址迁移推断库 API 或 Maven 坐标变更。

终端抓取部分 Android 文档出现 429，后通过网页工具成功阅读官方 NSD、本地网络权限与后台播放文档。原始失败记录仍保留。Ktor supported-platforms 页面仅返回跳转提示，没有作为服务器 Android 适配的证据。

没有安装候选库、运行原型或做真机性能比较。所有包体、延迟、耗电的优劣均留给同条件实验，不提供猜测数字。

- [S1 Ktor 服务器引擎](https://ktor.io/docs/server-engines.html)
- [S2 Ktor 服务端 WebSocket](https://ktor.io/docs/server-websockets.html)
- [S3 Java-WebSocket 官方仓库](https://github.com/TooTallNate/Java-WebSocket)
- [S4 Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [S5 Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)
- [S6 Gson 官方说明](https://github.com/google/gson)
- [S7 Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization)
- [S8 Zipline](https://github.com/cashapp/zipline)
- [S9 Rhino](https://github.com/mozilla/rhino)
- [S10 TrueTime](https://github.com/instacart/truetime-android)
- [S11 Snapcast](https://github.com/snapcast/snapcast)
- [S12 Ableton Link](https://github.com/Ableton/link)
- [S13 AndroidX Media](https://github.com/androidx/media)
- [S14 Oboe 与 OboeTester](https://github.com/google/oboe)
- [S15 Media3 后台播放](https://developer.android.com/media/media3/session/background-playback)

## 附录：API Star 快照

下面的全部数字来自本轮 API 返回；Rhino 为补充网页调研，不混入精确 API 表。

| 查询项目 | 返回的 canonical repo | Star | 最近推送（API 原值） |
|---|---|---:|---|
| oven-sh/bun | oven-sh/bun | 96435 | 2026-10-27T15:57:56Z |
| protocolbuffers/protobuf | protocolbuffers/protobuf | 72132 | 2026-10-27T15:57:42Z |
| square/okhttp | lysine-dev/okhttp | 47100 | 2026-10-27T01:52:17Z |
| netty/netty | netty/netty | 35066 | 2026-10-26T23:59:02Z |
| google/gson | google/gson | 24234 | 2026-10-24T12:10:16Z |
| ktorio/ktor | ktorio/ktor | 14555 | 2026-10-27T15:00:01Z |
| Kotlin/kotlinx.coroutines | Kotlin/kotlinx.coroutines | 13822 | 2026-10-27T15:35:29Z |
| bellard/quickjs | bellard/quickjs | 11076 | 2026-10-26T15:45:36Z |
| TooTallNate/Java-WebSocket | TooTallNate/Java-WebSocket | 10803 | 2026-01-13T21:24:14Z |
| square/moshi | square/moshi | 10166 | 2026-10-20T09:05:34Z |
| badaix/snapcast | snapcast/snapcast | 8036 | 2026-10-27T11:46:54Z |
| koush/AndroidAsync | koush/AndroidAsync | 7523 | 2022-11-23T15:20:07Z |
| NanoHttpd/nanohttpd | NanoHttpd/nanohttpd | 7236 | 2023-07-25T06:59:07Z |
| Kotlin/kotlinx.serialization | Kotlin/kotlinx.serialization | 5949 | 2026-10-23T21:10:06Z |
| google/oboe | google/oboe | 4128 | 2026-10-26T09:56:17Z |
| freeman-jiang/beatsync | freeman-jiang/beatsync | 3205 | 2026-06-12T18:53:27Z |
| androidx/media | androidx/media | 3055 | 2026-10-27T15:47:35Z |
| cashapp/zipline | cashapp/zipline | 2313 | 2026-10-27T14:34:31Z |
| instacart/truetime-android | instacart/truetime-android | 1470 | 2023-06-12T05:55:17Z |
| Ableton/link | Ableton/link | 1352 | 2026-10-19T08:53:04Z |
| jmdns/jmdns | jmdns/jmdns | 670 | 2026-10-10T20:43:42Z |
| apache/commons-net | apache/commons-net | 298 | 2026-10-26T15:03:18Z |
| sendspin/sendspin | Sendspin/sendspin-python-cli | 205 | 2026-10-27T15:30:47Z |
| dokar3/quickjs-kt | dokar3/quickjs-kt | 155 | 2026-10-20T18:48:53Z |
