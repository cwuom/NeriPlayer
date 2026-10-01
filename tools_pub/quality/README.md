# CRAP 与职责边界检查

需要 JDK 17、Android 构建环境和 Python 3。脚本仅使用 Python 标准库。

```bash
./gradlew :app:verifyCrap
./gradlew :app:verifyDomainDependencies
./gradlew :local:verifyCrap :local:lintDebug
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew verifyModularization
```

自有 Android 库由 `gradle/owned-modules.txt` 统一登记，共 14 个。构建、测试、覆盖率和 lint 从登记读取一级领域及二级职责目录，不依赖目录深度或旧分类前缀。远程平台、歌词来源与评论集中在 `:platform`，歌词解析与词幕输出属于 `:lyrics`。模块职责见 [根目录 README](../../README.md#模块结构)。

`verifyCrap` 运行 app 和已登记库的 Debug JVM 测试，通过 AGP ScopedArtifact.CLASSES 获取各模块自身的 Kotlin/Java 字节码，合并真实测试执行数据生成 JaCoCo XML，再计算逐方法 CRAP。库产物由 convention 的 outgoing configurations 提供。源码从实际模块的 `src/main/java` 和 `src/main/kotlin` 合并到 `app/build/reports/crap/sources`，重复路径直接报错。执行数据缺失或为空时立即失败。

`:app:check` 执行 CRAP 与计算域依赖门禁；`verifyModularization` 同时检查构建逻辑测试、所有已登记库的 lint 和无需 Android SDK 的结构门禁。库职责调整不得减少原有源码或方法检查范围，Kotlin 包名与生产类全名继续保留。

结构检查验证登记、未登记构建节点、领域依赖、循环、模型与生产包所有者、包目录一致性和容量。`:model` 不依赖项目实现，所有库不得依赖 app。平台客户端位于 `:platform` 的 `api` 协议包；同步与一起听传输分别归所属业务域。生产模型包不得留在 app 或其它库；原已迁出的生产包禁止回流。库和 `APP_FAMILIES` 区域每个目录最多 16 个直接源码文件，库主源码少于 2000 行。

`:platform` 内的协议、账号、缓存、各平台业务、评论和歌词协调保留包级单向依赖。Bilibili、网易云、YouTube 协议不得反向调用账号仓库、Room 或播放器实现；业务消费自身协议，评论和跨来源歌词组件只使用明确允许的来源能力。结构检查与编译依赖门禁共同维护这些规则，单一 classpath 不等于允许任意跨包调用。平台通过 `api(:lyrics)` 公开解析能力，`:lyrics` 不依赖平台。`:platform:verifyDomainDependencies` 从共享配置选择 `platform-` 域并检查本库真实产物，模块 `check` 同时执行复杂度与依赖检查；独立报告位于 `modules/platform/build/reports/domain-dependencies/`。

`:sync` 的传输、凭据状态与会话规则由同一库维护，Android 仓库与 WorkManager 适配归 `:local`。独立验证使用：

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
```

同步的 `data/sync/**/*.kt` 与 `api/sync/**/*.kt` 整文件受检，包括默认参数、协程、lambda 和新增子目录。`:sync:verifyCrap` 合并自身与 `:model` 中同步模型的覆盖率；域任务同时选择 `data.sync.*` 和 `api.sync.*`，保持会话、合并、远端保护、Worker 策略与传输各自的白名单。传输禁止引用 Android 仓库、播放器和合并实现；计算域只消费模型与宿主端口。

`:local:verifySyncIntegrationCrap` 独立运行同步 Android 适配的 JVM 测试，并检查本地库整个 `data/sync` 目录。阈值、执行数据校验和整文件范围不变，报告位于 `modules/local/build/reports/sync-crap/`；本地库全量测试与 `verifyCrap` 继续由原门禁执行。同步独立报告位于 `modules/sync/build/reports/crap/`。

单独查看完整报告可运行 `./gradlew :app:crapReport`，该任务要求测试和输入有效，但不会因超分退出失败。

报告位于 `app/build/reports/crap/`：

- `methods.json`：能映射到 app 和自有库主源码的全部 JVM 方法，包含签名、位置、复杂度、覆盖率和分数
- `above-8.md`：严格大于 8 的全部方法，包含门禁范围外的方法
- `scope.md`：门禁范围内的全部方法
- `coverage.xml`、`coverage/index.html`：真实测试采集结果

CRAP 是方法指标，不能单独判断类的耦合程度。这里使用公式
`C² × (1 − cov)³ + C`，`C` 为 JaCoCo COMPLEXITY 的 missed + covered，
`cov` 为 covered / C，即 JaCoCo 复杂度覆盖率，作为路径覆盖率的近似值。
它不是原版 crap4j 的严格独立路径覆盖测量，也不是行覆盖率。

本项目的 9 是严格的自定义风险门禁，原作者最初使用的阈值是 30；覆盖率达到 100% 时，
CRAP 仍等于圈复杂度。低分不能证明架构易于理解，覆盖率也不能证明断言有效。
重构应让入口、业务步骤、状态所有权和失败处理更容易追踪，减少重复概念和纯转发层，
不能仅为降低分数拆函数或增加 Coordinator、Handler、Resolver 等间接层。
审查同时依据模块依赖、行为与边界测试、lint 和必要的集成或设备验证；
对关键判断可使用 mutation test 检查测试是否能发现错误，但当前门禁未自动提供该验证。
阈值、受检范围和有效测试不得作为刷分手段调整。

来源：[CRAP 原作者公式](https://www.artima.com/weblogs/viewpost.jsp?thread=215899)、
[JaCoCo 计数器定义](https://www.jacoco.org/jacoco/trunk/doc/counters.html)。

`config/quality/crap-scope.json` 的 `source_patterns` 定义整文件检查范围，
`method_scopes` 定义方法级检查范围，包括入口方法、构造方法和 Kotlin 默认参数方法。
规则匹配不到 JaCoCo 方法时直接报错。范围外的方法仍进入 `methods.json` 和 `above-8.md`，
用于风险分析，不参与门禁判定。
范围内任意方法原始分数严格大于 9 时退出码为 1；分数等于 9 时通过。
缺少 XML、无效计数器、空范围、范围匹配不到文件或范围文件未出现在 JaCoCo 类报告中时退出码为 2。
纯接口没有可执行方法，以类记录验证源码存在；接口默认实现与其它可执行方法仍按实际复杂度检查。
不使用平均分或历史 baseline 豁免；协程和 lambda 字节码同样保留，只使用 JaCoCo 内置的编译器过滤。
新增拆分组件应位于已覆盖的文件模式内，或同时更新范围配置。原文件中业务分支发生变化的
方法也必须加入门禁；不能因为其既有分数较高而遗漏改动。如果 Android 宿主入口的变更仅将
原有逻辑委托给独立组件，可将完整的迁入逻辑和宿主参数适配器纳入门禁，并通过 JVM 测试
验证适配器实际使用的来源、模式与生命周期分支。审查时必须核对委托前后的完整 diff，
报告应区分受检组件与未执行的宿主入口，不能宣称后者已具备覆盖率。

`:playback:logic` 的 policy/runtime/audio/queue 按包目录整文件受检。policy 仅包括 `audio`、`command`、`offload`、`pending`、`progress`、`service`、`skip`、`storage`、`wake` 九个叶子包；它不引用 runtime、audio 或 queue 实现。runtime 可以消费 policy，audio 和 queue 保持窄依赖。USB policy 属于 `:playback:runtime`，不能通过宽泛的 `core.player.policy.*` 所有者规则移入计算库。

播放基础与运行两个库通过 `build-logic.android.module-quality` 提供 `verifyCrap`，从共享配置选择各自源码和原方法选择器，拒绝空范围。policy、状态协调、PCM、队列、宿主接口、展示状态、一起听映射、USB 策略和 app 的 `core/di/player` 桥接继续受检；app 的生产 `core/player` 目录必须保持不存在。包级编译依赖白名单由应用的聚合域任务检查，计算库不得依赖播放器实现、数据库或应用容器。

`:lyrics` 的 `lyrics/integration/**/*.kt` 整文件纳入 CRAP，包含新输出组件；原播放入口方法的检查继续保留。SDK 接入、位置推送、异步代次、偏移快照和共用时间偏移计算归歌词库；远程歌词仓库、匹配与回退归 `:platform` 的 `data/lyrics`。播放器通过窄加载端口提供歌词、快照与生命周期输入，歌词库不读取平台或播放器状态。`:lyrics:verifyCrap` 与全项目门禁使用同一范围和阈值。

`:listentogether` 的 `data/ltw/**/*.kt`、`api/ltw/**/*.kt` 与 `listentogether/**/*.kt` 整文件受检，包括默认参数、协程、lambda、新目录与新文件。自身 JVM 测试涵盖客户端、传输与协议，`verifyCrap` 验证执行数据并检查实际 JaCoCo 结果；报告位于 `modules/listentogether/build/reports/crap/`，任意受检方法大于 9 即失败。

`listen-together-runtime` 允许模型、传输、协程、基础工具和明确列出的 Android 线程、时钟、URI、唤醒锁能力，禁止引用 `AppContainer`、`PlayerManager` 和领域仓库。`listen-together-protocol` 只允许协议模型、序列化与标准库。平台绑定归 app 的 `core/di/ltw`，播放器适配归 `:playback:runtime` 的 `core/player/ltw`，生产一起听代码禁止返回 app。`verifyDomainDependencies` 检查同库所有实际 class 产物中的受管域，报告位于 `modules/listentogether/build/reports/domain-dependencies/`；模块 `check` 连接复杂度与域检查。

`:download:logic` 的规则组件按职责包使用 `**/*.kt` 整文件模式，包括准入、状态迁移、重试、
延后队列、清空/提交/大小/发布规则、传输槽位、看门狗、网络策略、所有权和元数据编解码。
`data/model/download/execution` 的状态契约也整文件受检。
新文件与子目录自动进入 CRAP 门禁，超分使现有 Android CI 的 `verifyModularization` 失败。
结构门禁禁止这些规则包回到 app 或进入其他库；依赖门禁限制规则只使用标准库、协程和所需模型。
传输注册表继续共用同一状态锁，FIFO、并发限制与活动心跳组件不独立修改槽位所有权。

`:database` 的 `data/local/database/migration/**/*.kt` 整文件受检，新建子目录和升级组件自动进入门禁。
其中包括历史升级 SQL、旧记录身份匹配、分页读取、JSON 映射、冲突保全、缓存和批量写入。
本地仓库中的播放历史和歌单使用记录映射也整文件受检；DAO 和实体由结构门禁验证模块归属与目录容量。
Android CI 同时运行数据库模块的独立升级测试，校验完整历史升级链、旧下载数据保全和恢复索引。

结构门禁禁止任何生产 `data` 包回流到 app，包括设置、媒体、登录、流量和同步 Android 接入。
`:local` 用职责子包管理这些实现，平台缓存归 `:platform`，不按小职责新增构建节点。
设置归一化、歌词/下载/缓存偏好政策、流量与周期统计、备份映射和仓库接入端口也纳入 CRAP 范围。
这些范围采用目录或完整文件选择器，超分仍由现有 `verifyModularization` 使 CI 失败。
本地实现可独立运行 `:local:verifyCrap`，复用同一范围配置和阈值，报告位于
`modules/local/build/reports/crap/`；模块的 `check` 也执行该门禁。
网易的 `data/platform/netease/mapping` 和 `playlist` 目录整文件受检，包括 JSON 映射、歌曲身份、远端比对、批量同步和重试；
新增文件自动纳入范围。`:platform:verifyCrap` 与该模块的 `check` 复用同一阈值，报告位于 `modules/platform/build/reports/crap/`。

下载运行实现归 `:download:runtime`，结构门禁禁止 `app/core/download` 和 `app/core/player/download` 回流。
应用只通过 `core/integration/download` 绑定环境、来源、歌词、凭据和播放接口，下载库不得引用应用容器或播放器实现。
下载请求代次、通知刷新、重试截止时间、单项异常隔离和宿主接口也按职责目录整文件受检，新文件自动进入门禁。
`./gradlew :download:logic:verifyCrap :download:runtime:verifyCrap` 独立运行下载规则和运行模块的 JVM 测试与复杂度检查；
这些任务复用 `build-logic.android.module-quality`，`check` 与 Android CI 均执行，超分仍按大于 9 失败。
共享源码契约测试工具位于 `:common` 的 test fixtures，测试随实现模块迁移，宿主集成测试留在应用。

下载条目构建将元数据、歌词和封面选择整体委托给 `catalog/assembly`，该目录整文件受检；
宿主的元数据读取选择、文件信息、封面参数与本地标签映射适配器也纳入门禁。
JVM 测试执行快速快照入口及侧载缺失回退，但不覆盖 `build` 的全部 Android I/O 挂起路径，
因此完整宿主方法仅保留在复杂度报告中，Provider 行为仍需 Android 集成验证。

Compose 生成字节码使用 [JaCoCo 内置过滤](https://www.jacoco.org/jacoco/trunk/doc/changes.html)。
复杂度和覆盖率以 `methods.json` 的实际计数器为准，源码分支数不等同于过滤后的 JVM 复杂度；
JVM 门禁通过仍不能代替设备上的 Compose 行为测试。Kotlin `internal` 方法的 JVM 名称
可能含模块后缀，移动方法时需按实际 XML 同步 `method_scopes`，不要改成宽泛匹配。

报告器回归测试：

```bash
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

结构验证需要同时审查依赖：采集器消费数据源接口，清理执行器消费清理端口，
展示层只消费快照，计算和文件遍历不得读取全局容器、Context 或数据库实例。
独立组件不以原上帝类为 receiver，也不回调原入口取得内部状态。

## 计算域依赖门禁

`verifyDomainDependencies` 通过 AGP `ScopedArtifact.CLASSES` 取得 app 和自有库的 Debug 编译产物，
使用运行 Gradle 的 JDK 所带的 `jdeps` 检查直接类依赖，使用 `javap` 检查宿主桥接的成员签名。
规则位于 `config/quality/domain-dependencies.json`；报告位于
`app/build/reports/domain-dependencies/report.json`。

同包宿主装配类可通过 `excluded_classes` 列出完整类名，仅排除该源类及其 `$` 生成内部类，不支持通配符。当前只排除归 `:local` 的 `BiliVideoSkipRepositoryProvider`，同包真实平台仓库仍受检查；受管类引用这个宿主仍须满足允许列表，排除不豁免目标引用。

- `:playback:logic` 的 `core/player/queue` 包含状态存储和编辑/导航策略，队列模型位于 `:model`；歌曲身份由接口注入，库不得依赖宿主的 `SongIdentity` 实现
- `:sync` 的 `data/sync/merge` 包含共享合并规则与宿主接口；Android 实现位于 `:local` 的 `data/sync/host`
- 同步会话、差异检测、快照清洗和远端并发保护有独立计算域规则；本地数据与网络操作通过接口注入，不允许依赖 Android 宿主实现
- `core/download/catalog/assembly` 负责下载条目的元数据优先级、歌词覆盖和封面选择，文件访问由宿主提供
- `core/download/catalog/projection` 负责编辑后的来源身份、原始标签和本地引用合并，不读写文件或目录状态
- `:download:logic` 的 `core/download/storage/metadata/codec` 只解析 JSON 与兼容旧版元数据，不调用存储入口或恢复任务
- `:download:logic` 的下载规则按职责允许状态模型、协程、网络类型或 `okhttp3.Call`，JSON 编解码域只允许模型、标准库和 `org.json`；禁止引用 Room、SAF、Android 服务和宿主全局容器
- 各计算域的全部编译类自动纳入检查，包括新类、嵌套类、lambda 和 Kotlin 生成类
- 类依赖采用允许列表，禁止直接引用播放器全局状态、数据库、网络、UI 或宿主适配器实现
- 混合文件中的身份与同步辅助函数仅允许列出的 JVM 方法签名；允许某个方法不等于允许整个文件
- 下载条目组装仅使用已列出的元数据模型和计算辅助函数，不允许调用 `ManagedDownloadStorage` 的存储入口
- 产物缺失、重复类、空计算域、工具失败或分析结果缺少目标类均使检查失败

这是直接依赖检查，不是传递依赖或反射调用分析。共享模型的 Parcelable 实现和宿主桥接的
内部平台依赖不因此被认定为纯计算代码。调整允许列表时应审查具体方法的行为，保留
`stableKey` 与 `sameIdentityAs` 的不同语义，尤其不能以 key 相等代替本地歌曲同源判断。

门禁回归使用 JDK `javac --release 17` 编译隔离夹具，再运行真实 `jdeps` 和 `javap`，
验证非法调用、同包间接引用、嵌套类、新文件和桥接成员越界均能被拒绝。
