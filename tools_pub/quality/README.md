# CRAP 与职责边界检查

需要 JDK 17、Android 构建环境和 Python 3。脚本仅使用 Python 标准库。

```bash
./gradlew :data:netease:verifyCrap :data:netease:lintDebug
./gradlew :data:repository:verifyCrap :data:repository:lintDebug
./gradlew :app:verifyCrap
./gradlew :app:verifyDomainDependencies
./gradlew :data:ltw:verifyCrap :data:ltw:verifyDomainDependencies :data:ltw:lintDebug
./gradlew verifyModularization
```

`verifyCrap` 运行 app 和自有 core/api/data/feature 库的 Debug JVM 测试，通过 AGP ScopedArtifact.CLASSES
获取每个模块自身的 Kotlin/Java 字节码，合并各测试任务的执行数据，生成 JaCoCo XML，
再计算逐方法 CRAP。库的覆盖率产物由 convention 的 outgoing configurations 提供。
源码从各模块的 `src/main/java` 和 `src/main/kotlin` 合并到 `app/build/reports/crap/sources`，
重复路径直接报错。生成报告前检查 app 和每个自有库的执行数据，缺失或空文件立即失败。
`:app:check` 执行 CRAP 与计算域依赖门禁，Android CI 的 `verifyModularization` 还执行所有自有模块的
lint 和不依赖 Android SDK 的 `verifyModuleBoundaries`。

自有库位于 `modules/core`、`modules/api` 和 `modules/data`，Gradle 标识分别为 `:core:*`、`:api:*` 和 `:data:*`。
实现依赖遵循 `app -> feature -> data -> api -> core`；各层均可引用 `:data:model`，该模型契约库禁止依赖项目实现。
其他反向依赖仍被禁止，API 实现不得放入数据模块，生产模型包不得留在 app 或其他库中。
边界检查同时验证 `includeOwnedLibrary` 登记、孤立库、包名与目录一致性和目录容量；
统一模型、网络基础能力和队列包还校验模块归属，防止源码被移回不匹配的库。
库主源码及 `module_boundaries.py` 中 `APP_FAMILIES` 登记的应用区域，每个目录最多 16 个直接源码文件，模块职责见
[根目录 README](../../README.md#模块结构)。移动路径时必须同步 CRAP source/method 选择器，不能减少原检查范围。
已迁出的 `core/api`、`core/lyrics`、`core/player/queue` 和 `data/sync/merge` 生产代码不得重新放入 `app`；
同步的 `change`、`codec`、`mapping/stats`、`remote`、`retry`、`runtime` 和 `sanitize` 包同样固定归属 `:data:sync`。
`LIBRARY_OWNED_FAMILIES` 检查这些目录及其子目录，宿主集成测试仍可保留在 `app`。
单独查看完整报告可以运行 `./gradlew :app:crapReport`，该任务仍要求测试和报告输入有效，
但不会因超分退出失败。
同步模块可独立运行 `./gradlew :data:sync:verifyCrap :data:sync:verifyDomainDependencies :data:sync:lintDebug`，
从同一份范围配置选择模块源码并执行同样的阈值，报告位于 `modules/data/sync/build/reports/crap/`。
Android CI 在全项目门禁前执行同步门禁，其他模块编译失败时仍可检查同步规则。

报告位于 `app/build/reports/crap/`：

- `methods.json`：能映射到 app 和自有库主源码的全部 JVM 方法，包含签名、位置、复杂度、覆盖率和分数
- `above-8.md`：严格大于 8 的全部方法，包含门禁范围外的方法
- `scope.md`：门禁范围内的全部方法
- `coverage.xml`、`coverage/index.html`：真实测试采集结果

CRAP 是方法指标，不能单独判断类的耦合程度。这里使用公式
`C² × (1 − cov)³ + C`，`C` 为 JaCoCo COMPLEXITY 的 missed + covered，
`cov` 为 covered / C，即 JaCoCo 复杂度覆盖率，作为路径覆盖率的近似值。
它不是原版 crap4j 的严格独立路径覆盖测量，也不是行覆盖率。

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

`:data:ltw` 的 `data/ltw/**/*.kt` 与 `:core:ltw-protocol` 的 `listentogether/**/*.kt` 整文件受检，包括默认参数、协程和 lambda；新增目录和文件自动纳入。
模块的 `verifyCrap` 执行客户端与协议库 JVM 测试、检查执行数据并生成合并的真实 JaCoCo 报告，`check` 和一起听 CI 均调用该门禁。
报告位于 `modules/data/ltw/build/reports/crap/`；分数算法与根门禁相同，任意方法大于 9 即失败。
`listen-together-runtime` 允许模型、一起听传输、协程、基础工具与明确列出的 Android 线程、时钟、URI、唤醒锁能力，禁止引用 `AppContainer`、`PlayerManager` 和数据仓库。
`listen-together-protocol` 仅允许协议模型、序列化与标准库。结构门禁禁止任何一起听生产源码回到 `app/listentogether`，平台绑定归 `app/core/di/ltw`，播放器适配归播放器的 `core/player/ltw`。
模块的 `verifyDomainDependencies` 按编译后的类验证该边界，`check` 和一起听 CI 均调用；报告位于 `modules/data/ltw/build/reports/domain-dependencies/`。

`player-policy` 的播放规则、`player-runtime` 的全部运行时组件和 `player-audio` 的 PCM/可视化
组件按包目录整文件受检，新文件无需逐一登记。结构门禁禁止这些包返回 app 或进入错误的库，
运行时与音频计算域另有字节码依赖允许列表。Android 播放器实现、资源映射和系统唤醒锁位于 `:feature:player`。
播放器的四个模块通过 `build-logic.android.module-quality` 提供独立 `verifyCrap`，从共享范围配置选择本模块的源码，保留原方法选择器并拒绝空范围。
播放器宿主接口、展示状态、一起听映射、USB 策略和 app 的 `core/di/player` 桥接按目录受检。整个 `app/core/player` 禁止出现生产源码，纯播放器库仍禁止依赖功能实现层。

`:core:download` 的规则组件按职责包使用 `**/*.kt` 整文件模式，包括准入、状态迁移、重试、
延后队列、清空/提交/大小/发布规则、传输槽位、看门狗、网络策略、所有权和元数据编解码。
`data/model/download/execution` 的状态契约也整文件受检。
新文件与子目录自动进入 CRAP 门禁，超分使现有 Android CI 的 `verifyModularization` 失败。
结构门禁禁止这些规则包回到 app 或进入其他库；依赖门禁限制规则只使用标准库、协程和所需模型。
传输注册表继续共用同一状态锁，FIFO、并发限制与活动心跳组件不独立修改槽位所有权。

下载运行实现归 `:feature:download`，结构门禁禁止 `app/core/download` 和 `app/core/player/download` 回流。
应用只通过 `core/integration/download` 绑定环境、来源、歌词、凭据和播放接口，下载库不得引用应用容器或播放器实现。
下载请求代次、通知刷新、重试截止时间、单项异常隔离和宿主接口也按职责目录整文件受检，新文件自动进入门禁。
`./gradlew :core:download:verifyCrap :feature:download:verifyCrap` 独立运行下载规则和运行模块的 JVM 测试与复杂度检查；
这些任务复用 `build-logic.android.module-quality`，`check` 与 Android CI 均执行，超分仍按大于 9 失败。
共享源码契约测试工具位于 `:core:common` 的 test fixtures，测试随实现模块迁移，宿主集成测试留在应用。

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

- `core:playback-queue` 的 `core/player/queue` 包含状态存储和编辑/导航策略，队列模型位于 `:data:model`；歌曲身份由接口注入，库不得依赖宿主的 `SongIdentity` 实现
- `:data:sync` 的 `data/sync/merge` 包含共享合并规则与宿主接口；Android 实现位于 app 的 `data/sync/host`
- 同步会话、差异检测、快照清洗和远端并发保护有独立计算域规则；本地数据与网络操作通过接口注入，不允许依赖 Android 宿主实现
- `core/download/catalog/assembly` 负责下载条目的元数据优先级、歌词覆盖和封面选择，文件访问由宿主提供
- `core/download/catalog/projection` 负责编辑后的来源身份、原始标签和本地引用合并，不读写文件或目录状态
- `core:download` 的 `core/download/storage/metadata/codec` 只解析 JSON 与兼容旧版元数据，不调用存储入口或恢复任务
- `core:download` 的下载规则按职责允许状态模型、协程、网络类型或 `okhttp3.Call`，JSON 编解码域只允许模型、标准库和 `org.json`；禁止引用 Room、SAF、Android 服务和宿主全局容器
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

`:data:database` 的 `data/local/database/migration/**/*.kt` 整文件受检，新建子目录和升级组件自动进入门禁。
其中包括历史升级 SQL、旧记录身份匹配、分页读取、JSON 映射、冲突保全、缓存和批量写入。
数据仓库中的播放历史和歌单使用记录映射也整文件受检；DAO 和实体由结构门禁验证模块归属与目录容量。
Android CI 同时运行数据库模块的独立升级测试，校验完整历史升级链、旧下载数据保全和恢复索引。

结构门禁禁止任何生产 `data` 包回流到 app，包括设置、媒体、登录、流量和同步 Android 接入。
`:data:repository` 用职责子包管理这些实现，平台缓存仍归各平台数据模块，不按小职责新增构建节点。
设置归一化、歌词/下载/缓存偏好政策、流量与周期统计、备份映射和仓库接入端口也纳入 CRAP 范围。
这些范围采用目录或完整文件选择器，超分仍由现有 `verifyModularization` 使 CI 失败。
数据实现可独立运行 `:data:repository:verifyCrap`，复用同一范围配置和阈值，报告位于
`modules/data/repository/build/reports/crap/`；模块的 `check` 也执行该门禁。
网易的 `data/platform/netease/mapping` 和 `playlist` 目录整文件受检，包括 JSON 映射、歌曲身份、远端比对、批量同步和重试；
新增文件自动纳入范围。`:data:netease:verifyCrap` 与该模块的 `check` 复用同一阈值，报告位于 `modules/data/netease/build/reports/crap/`。
