# 模块按业务域收敛设计

状态：本轮 22 库迁移已实现并验证；用户随后要求进一步合并远程平台，最终结构以 [平台收敛设计](2026-10-01-platform-consolidation-design.md) 为准

## 目标与约束

用户要求减少过细的模块分类、合并缺少独立价值的边界、纠正不合理归属，并参考大型 Android 项目。用户已选择“按业务域收敛”，并明确要求 listentogether、database 等领域直接独立列出，不再硬塞入 core 或 data；api 分类保留。词幕适配相关实现统一归歌词领域。目录首先表达业务领域，需要独立编译的内部职责再放入该领域下。

本次以当前工作区为基础，不撤销上一轮尚未提交的迁移，不新建 Worktree，不提交或推送。目标是减少理解和构建配置成本，同时保持既有行为、测试和质量检查范围。

平台配置保持 compileSdk 37、targetSdk 36、minSdk 28、Java/Kotlin JVM 17、Kotlin 2.4.10、AGP 9.4.1、Gradle 9.6.1。不得因整理模块修改存储格式、数据库版本、播放策略或下载协议。

## 迁移前基线

2026-10-01 读取 settings.gradle.kts、各库 build.gradle.kts、根 README/CONTRIBUTING、质量检查及受影响接口后确认：

- 当前自有 Android 库共 31 个，分布在 core、api、data、feature 四类。该数字不包含 app、KSP 工具、歌词上游子模块或构建逻辑。
- 自有库含 1301 个主 Kotlin/Java 源文件、750 个 JVM 测试源文件、16 个设备测试源文件及 2 个测试夹具源文件。这是文件数，不是测试用例数。
- 播放 policy/runtime/audio/queue 四库共 64 个主源码文件和 52 个 JVM 测试源文件。除应用的统一依赖登记外，runtime/audio/queue 的唯一库消费者是 feature:player；policy 的消费者是 runtime 和 feature:player。
- api:lyrics 和 api:search 共 10 个主源码文件，负责歌词来源、网易云/QQ 元数据搜索，与歌词匹配链路紧密相关。
- api:sync 8 个主源码文件，data:sync-store 19 个，data:sync 61 个；传输和凭据实现没有依赖 Android 仓库宿主。
- api:ltw 6 个主源码文件，core:ltw-protocol 4 个，data:ltw 85 个；协议和传输都是一起听领域内部实现。
- core:logging 仅有 2 个主源码文件；common 已承担共享工具、资源、语言能力与测试夹具。
- 平台 API 有多个消费者；保留平台网络客户端与账号/缓存仓库的构建边界，客户端仍位于 api，仓库按平台领域独立列出。

迁移前执行 `python3 -B tools_pub/quality/module_boundaries.py` 和 58 项边界回归，结果通过。本次按下表重新映射全部 31 个库，目标为 22 个库、api 保留 4 个库。迁移前确认声明依赖无环、src 相对路径无冲突，文档矩阵与映射结果一致；播放器额外扫描未发现跨原模块的顶层声明或 Kotlin 文件 facade 重名。实施后的编译、lint、APK 和模拟器验证结果见文末。

## 参考与取舍

- [Android 官方模块化模式](https://developer.android.com/topic/modularization/patterns)：数据模块可以同时封装仓库和数据源；高内聚、低耦合以及配置成本比模块数量更重要。
- [Now in Android 模块化说明](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)：基础模型、数据库、网络与用户功能有明确边界，拆分粒度应随项目规模和复用需求调整。
- [AntennaPod 的实际模块登记](https://github.com/AntennaPod/AntennaPod/blob/develop/settings.gradle)：播放、网络、存储按领域组织，播放服务与基础能力区分。
- [Thunderbird Android 模块规范](https://thunderbird.github.io/thunderbird-android/docs/latest/architecture/module-structure.html)：其 API 指公共契约，内部实现按领域组织。本项目按照用户要求保留 api 作为外部服务客户端分类，明确区分网络 API 客户端与公共能力契约，不照搬其多应用的成对 API/internal 库。

可选路径：

1. 只整理名称和文档：风险低，但保留多数细碎构建节点，不能解决用户的主要问题。
2. 按业务域收敛到 22 个自有库：修订方案，保留 api 网络客户端分类，移除 core/data/feature 分类，内部职责通过包与依赖检查维护。
3. 大幅并为数个大型库：模块数量更少，但平台实现和 Android 服务的变更影响范围会扩大，规则与宿主更难隔离。

采用第二条路径。22 是本次职责分析和用户分类要求共同确定的结果，不是以后新增模块的固定配额。原先 18 个库的方案包含移除 api、合并平台客户端与仓库，不再采用。

## 合并与归属矩阵

| 当前模块 | 目标模块 | 依据 |
| --- | --- | --- |
| core:common、core:logging | common | 共享基础设施，保留 logging、util 等职责包 |
| core:network | network | 通用 HTTP、Range 与 Web 登录基础能力 |
| data:model | model | 无项目实现依赖、由各领域消费的基础契约 |
| data:database | database | Room、DAO、实体、schema 与历史升级独立维护 |
| data:storage | storage | 可注入数据源的存储统计与清理能力 |
| core:player-policy、core:player-runtime、core:player-audio、core:playback-queue | playback:logic | 同一播放基础能力，保留 policy/runtime/audio/queue 包级隔离 |
| feature:player | playback:runtime | 引擎、播放服务、USB、系统音效与平台接入 |
| core:download | download:logic | 可复用的规则和基础存储工具 |
| feature:download | download:runtime | 下载执行、Room 队列、传输、恢复、Worker 与 JobService |
| core:lyrics | lyrics:parser | 多领域共用的歌词解析与转换 |
| data:lyrics、播放器中的词幕适配实现 | lyrics:runtime | 元数据候选匹配、歌词来源仓库、回退，以及 Lyricon/SuperLyric 歌词输出 |
| api:bilibili | api:bilibili | 保留 Bilibili 网络客户端与协议实现 |
| api:netease | api:netease | 保留网易云网络客户端与协议实现 |
| api:youtube | api:youtube | 保留 YouTube 网络客户端、JS 与播放协议实现 |
| api:lyrics、api:search | api:metadata | 歌曲元数据与歌词来源的网络客户端，保留 client/codec 等职责包 |
| data:bilibili | bilibili | Bilibili 账号、缓存、历史身份与应用模型映射 |
| data:netease | netease | 网易云账号、缓存、歌单同步与应用模型映射 |
| data:youtube | youtube | YouTube 账号、缓存与播放源仓库 |
| data:comments | comments | 跨平台评论分页与缓存 |
| data:repository | local | 本地应用数据与宿主接入适配，按设置、媒体、歌单、统计、备份、流量与同步接入分包 |
| api:sync、data:sync-store、data:sync | sync | 传输、凭据状态与会话合并同属同步域；Android 本地仓库适配继续在 local |
| api:ltw、core:ltw-protocol、data:ltw | listentogether | 一起听协议、传输与会话集中为一个独立领域模块 |

Gradle 路径在表中名称前加冒号。例如 `:database` 对应 `modules/database`，`:listentogether` 对应 `modules/listentogether`，`:api:youtube` 对应 `modules/api/youtube`，`:playback:logic` 对应 `modules/playback/logic`。一级目录表示具体领域或用户保留的 api 分类，只有确有独立构建边界的领域再使用第二级。

- download:logic 被本地数据复用，不能直接并入 download:runtime，否则本地数据将依赖 Android 下载服务实现。
- playback:logic 保留可独立测试的播放计算与协调规则；playback:runtime 维护 Android 运行实现，播放器继续通过 PlayerDownloadAccess 接入下载，禁止直接依赖 download:runtime。
- lyrics:parser 被 API 客户端调用，lyrics:runtime 又依赖 API 客户端。把两者直接合并，同时保留 api，会形成循环依赖，因此在歌词领域内保留两个真实构建边界。原候选名称 lyrics:matching 改为 lyrics:runtime，覆盖匹配、来源仓库与词幕输出，不增加新的词幕构建节点。
- comments 是跨平台聚合领域，不能归入任意一个平台。local 维护本地应用数据和宿主接入，不因内部职责多而继续增加小模块。
- 页面继续由 app 维护，本次不增加 UI 模块，不把通用规则移入 Android 服务实现。

最终 api 4 个、playback/download/lyrics 各 2 个、其他独立领域 12 个，共 22 个自有库。移除 core、data、feature 根分类及被合并的构建节点，不保留空壳或别名模块。共减少 9 个库：日志合并 1 个、播放基础合并 3 个、元数据 API 合并 1 个、同步合并 2 个、一起听合并 2 个；其余调整是归属和名称变化，不算合并收益。

## 依赖与内部隔离

不再用 core/data/feature 的目录名或统一数字层级判断依赖是否合法。目录表达领域，构建登记与包级规则表达真实边界；所有项目依赖必须无环，所有库禁止依赖 app 或引用应用容器、页面、活动。

model 不依赖任何项目实现；common 不依赖业务领域；database、storage、歌词解析与播放/下载规则不得依赖领域仓库或 Android 运行实现。api 的外部服务客户端可以依赖模型、网络、共享工具和歌词解析，但不能读取平台账号仓库、本地数据库或播放器状态。平台仓库消费对应 api 客户端；local 消费数据库和领域服务。播放/下载的运行模块通过宿主端口接入各领域，禁止规则反向依赖运行模块。既有播放器内部使用自身 PlayerManager 的限定例外只适用于 playback:runtime。

同步和一起听的客户端与领域实现共处一个库，平台外部 API 客户端继续单独维护。公共接口只暴露调用方需要的能力，合并时不顺便扩大可见性，也不新增为了抽象而抽象的 API 库。内部包依赖继续通过编译字节码的允许列表约束：

- 播放 policy/runtime/audio/queue 不得访问播放器宿主、数据库或应用容器。
- 为 player-policy 补充包级允许列表，保留原 Gradle 边界提供的单向约束：policy 不引用 runtime、audio 或 queue 的实现；runtime 可以消费 policy，audio 和 queue 保持各自的窄依赖。
- 播放基础 policy 的所有者和依赖域按现有九个叶子包注册：audio、command、offload、pending、progress、service、skip、storage、wake。不能使用整个 `core.player.policy.*` 前缀；既有 `policy.usb.*` 属于 playback:runtime 的 Android/USB 实现，继续保留在该模块。
- 同步传输不依赖 Android 仓库、播放器和合并实现；同步计算域只使用既有模型与宿主端口。
- 一起听协议只使用协议模型、序列化与标准库；客户端不直接访问 PlayerManager 或 AppContainer。
- 下载规则不得访问 Room、SAF、服务或宿主全局容器。

## 词幕适配归属

词幕适配统一放在 `modules/lyrics/runtime`，由 `:lyrics:runtime` 维护。`LyriconManager`、Lyricon/SuperLyric SDK 模型转换、翻译映射、Provider 注册/重连/释放、进度推送和请求代次协调都属于歌词输出职责。迁移前对应源码为播放器库的 `core/lyricon/LyriconManager.kt`、`core/lyricon/LyriconPositionFeed.kt` 和 `core/player/lyrics/LyriconUpdateCoordinator.kt`，对应两个 JVM 测试文件随实现迁移。

当前 PlayerManager 内的 `syncLyriconSong` 异步编排、请求取消/完成协调、词幕偏好来源记录和有效偏移选择也抽取到歌词输出组件，不能只迁移三个独立文件而把适配逻辑继续留在播放器。当前歌曲身份、歌词来源与偏好由接入端口提供，保留原有调用顺序和竞态保护。

播放器只提供当前歌曲、解析后的歌词、翻译、播放状态、进度、倍速、歌词偏好快照及用户偏移，并在播放生命周期触发歌词输出入口。应用提供初始化 Context 和功能开关。歌词模块不得依赖 PlayerManager、PlayerDependencies、播放服务或 AppContainer，不回调播放器读取内部状态；异步歌词读取通过窄端口和请求快照接入，过期请求校验继续由歌词输出组件维护。

跨模块接入只暴露必要的输出控制入口。`LyriconUpdateCoordinator` 和位置锚点等细节继续作为歌词模块内部实现，不能通过把全部 internal 类型改成 public 来完成迁移。现有播放器对 internal `mediaLyriconPositionMs` 的调用改为使用歌词输出入口，不跨模块直接访问内部计算。保留切歌代次校验、取消顺序、歌曲身份校验，以及歌词来源与有效偏移的对应关系。

迁移仅改变已确认的词幕实现归属。`core.player.lyrics` 中还有浮窗、蓝牙歌词等实现，不能用该包的宽泛所有者规则把无关文件一并迁移；词幕协调器需使用精确的类型归属规则，相关源码定位和调用契约测试同步更新。

`libs.lyricon.provider`、`libs.superlyricapi` 和相关 consumer R8 keep 规则随适配实现迁入歌词运行模块；播放器不再直接导入这些 SDK。现有状态栏图标继续复用 common 资源，歌词解析库不引入第三方输出 SDK。保留 200 ms 进度推送周期、400 ms 显示预推、媒体锚点与显示偏移分离、倍速重锚、翻译与逐词映射、功能关闭及服务释放行为，不因迁移改变这些语义。

应用 Manifest 中四项 `lyricon_module*` metadata 和 `arrays.xml` 中的 `lyricon_module_tags` 也随适配迁入歌词运行库，检查最终合并 Manifest 的字段与资源引用保持一致。app 和播放器当前重复声明的 SDK 可统一由歌词运行库 implementation，不在输出接口泄漏第三方 SDK 类型。R8 迁移保留最终 keep 行为与现有兼容警告处理，播放器的 USB/FFmpeg 规则继续保留在播放器库。

迁移验证至少覆盖已有 `LyriconPositionFeedTest`、`LyriconUpdateCoordinatorTest`，并验证关闭/暂停/释放、Seek/倍速变化、快速切歌和过期异步请求不能向新歌曲发布旧歌词。SDK 在系统服务上的实际展示效果仍需相应环境验证，不能由 JVM 测试推断。

新增歌词输出控制器按完整源码目录纳入 CRAP 范围，保留原播放器初始化、暂停和进度触发点的方法检查。迁移本身不能让原有受检方法失去覆盖。

## 结构与质量门禁

实施时补充：既有 `LyricDefaultOffset.kt` 只依赖模型，完整源码与对应测试移入 lyrics:parser，保留原包名和函数接口。设置与词幕输出共用同一套来源偏移和饱和运算，避免新控制器复制计算规则；设置持久化仍归 local。其模块所有者使用精确文件规则，不能把整个 settings 包改归歌词。构建产物忽略规则支持一级和二级模块目录。

既有 `domain-dependencies.json` 和 CRAP 目录范围保留，模块合并不能删除规则、缩小受检源码或放宽阈值。当前播放 policy 没有字节码依赖域，合并前必须补齐，而不能认为修改包所有者就完整保留了原来的隔离。结构检查不再依赖 core/data/feature 前缀，以实际构建登记和明确的生产包所有者检查归属；同步和一起听传输是独立领域模块内的允许归属，平台客户端仍只能位于对应 api 库，错误归属有回归检查。

库目录既有一级领域模块，也有 api/playback/download/lyrics 的嵌套模块。源文件、覆盖率和 lint 聚合统一从同一份模块登记读取实际目录，不再使用 `modules/*/*` 或层名前缀推测模块是否存在。每个登记必须指向唯一且实际存在的库，未登记的构建节点继续报错。

同步独立域任务必须同时选择 `data.sync.*` 与原有 `api.sync.*` 域，不能继续只筛选前者。一起听独立任务必须合并原客户端和协议的源码、执行数据与 class 范围；移除旧模块的聚合引用时保留全部实际受检方法。此前只由项目全量测试运行的传输测试也随实现迁入新库。

## 迁移与兼容性

调整模块归属、合并构建声明与同步相关工具引用，保持生产 Kotlin/Java 包名和类全名。词幕接入额外抽取现有协调逻辑、更新调用入口，以保留歌词模块内部可见性，不改变原有语义。特别保留 Parcelable、Room 数据库/实体、序列化类型、JNI 类、服务和 Worker 的现有全名，不修改业务状态机或网络请求。

源码、JVM/设备测试、测试资源、consumer R8 规则、数据库 schema 和必要资源必须随所属模块完整迁移。每个待合并源文件迁移前后进行相对包路径和内容核对，不覆盖同名文件。仅改归属或重命名的模块保留既有 Android namespace；合并到已有库时沿用原主实现库 namespace，新增 playback:logic 使用 `moe.ouom.neriplayer.playback.logic`，api:metadata 使用 `moe.ouom.neriplayer.api.metadata`。logging 合入 common 时明确适配生成的 BuildConfig 引用并保留 TAG 值。YouTube 的 JS assets、序列化插件和 consumer R8 配置全部保留。

必须同步 settings.gradle.kts、应用覆盖率登记、所有 project 依赖、数据库设备测试资产位置、源文件定位夹具、源码预算测试、CI、IDE 模块登记、双语 README/CONTRIBUTING 和质量文档。

模块名改变可能影响 Kotlin internal 成员的 JVM 名称和测试 friend paths。必须根据实际编译/JaCoCo 产物精确调整方法选择器，不用宽泛匹配掩盖丢失范围。当前源码路径无冲突不等于编译一定通过。

## 验证与验收

迁移按基础设施、播放规则、平台来源、同步、一起听顺序串行更新公共登记，避免代理同时写相同构建脚本。独立审查可并行，Gradle 使用同一工作区时串行运行。

验收必须满足：

1. settings、磁盘构建节点、覆盖率登记均精确列出 22 个库；保留 api 分类，没有 core/data/feature 的旧构建登记、空壳模块或循环。
2. 原有生产文件、测试、schema 和 R8 规则完整保留；本次增量 diff 无业务逻辑改变或无关内容回退。
3. `python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'` 和模块结构检查通过。
4. 受影响库 JVM 测试、独立 CRAP/域依赖检查及 lint 通过；所有旧受检包继续进入合并覆盖率，新增文件仍自动受检。
5. `./gradlew verifyModularization` 与 Debug 应用及设备测试 APK 构建通过；本次直接产生的编译器/lint 警告全部修复。
6. 在可用 Android 模拟器上运行受影响的数据库、数据适配、播放器与应用集成测试；设备不可用时明确记录未验证项。真实设备操作需另获用户确认。

构建耗时改善目前未知。不得仅凭模块数量减少声称构建更快或运行性能提升。若已有工作区缺陷阻断验证，区分原有问题与本次产生的问题，保留现场并记录实际结果。

## 已执行验收

2026-10-01 已完成矩阵中的 22 个库迁移，实际登记、磁盘节点、应用测试聚合一致；core/data/feature 的旧模块目录与别名均已移除。迁移前 3339 个文件逐一核对，缺失为零，生产包名变更为零；18 个 Room schema 与两个 YouTube JS assets 内容不变。冗余构建配置合并到领域模块，原文件与改动基线保留在工作区外。

- `python3 -B tools_pub/quality/module_boundaries.py` 通过；完整 Python 质量工具回归 129 项通过。独立审查发现的登记过滤与缓存目录漏检已修正，原复现及新增回归均拒绝无效登记
- `./gradlew verifyModularization --max-workers=2` 通过，包含 app 与 22 个库 JVM 测试、lint、合并 CRAP、领域依赖及构建逻辑测试。JUnit XML 合计 7587 项，7585 项通过，两个既有网络/凭据 smoke 用例按原 opt-in 条件跳过，失败与错误均为零
- 合并 CRAP 检查 12392 个范围内方法，没有超过 9 的方法；43 个领域依赖规则零违规。原有 34 个规则保持不变，新增九个纯播放 policy 叶子域
- 播放、歌词、一起听的独立 CRAP/领域任务，以及下载 logic/runtime 和本地同步接入的补充任务通过。词幕相关 33 项 JVM 测试通过，覆盖原有协调器、进度锚点及新增输出入口
- Debug 应用和 database/local/playback/app 设备测试 APK 构建通过；`./gradlew :app:assembleRelease -PallowUnsignedRelease=true --max-workers=2` 通过，包括 R8 与 Release 资源收缩。实际 Release APK 保留四项词幕 metadata、两个标签和 SDK consumer keep 规则
- 只读 API 37 模拟器执行 database 3 项、local 18 项、playback 22 项、app 迁移集成 42 项，共 85 项全部通过。使用明确 emulator serial，未操作实体设备；本次模拟器已关闭

独立代码与文档审查通过，完整 diff 检查通过。已有测试夹具中的 nullable File.parentFile 编译警告保持原样，本次没有新增生产编译器或 lint 警告。第三方词幕系统服务的实际展示、实体 USB DAC 和线上服务行为未验证；未提交或推送。
