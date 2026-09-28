# CRAP 与职责边界检查

需要 JDK 17、Android 构建环境和 Python 3。脚本仅使用 Python 标准库。

```bash
./gradlew :app:verifyCrap
./gradlew :app:verifyDomainDependencies
./gradlew verifyModularization
```

`verifyCrap` 运行 app 和自有 core/data 库的 Debug JVM 测试，通过 AGP ScopedArtifact.CLASSES
获取每个模块自身的 Kotlin/Java 字节码，合并各测试任务的执行数据，生成 JaCoCo XML，
再计算逐方法 CRAP。库的覆盖率产物由 convention 的 outgoing configurations 提供。
源码从各模块的 `src/main/java` 和 `src/main/kotlin` 合并到 `app/build/reports/crap/sources`，
重复路径直接报错。生成报告前检查 app 和每个自有库的执行数据，缺失或空文件立即失败。
`:app:check` 执行 CRAP 与计算域依赖门禁，Android CI 的 `verifyModularization` 还执行所有自有模块的
lint 和不依赖 Android SDK 的 `verifyModuleBoundaries`。

自有库位于 `modules/core` 和 `modules/data`，Gradle 标识分别为 `:core:*` 和 `:data:*`。
边界检查同时验证 `includeOwnedLibrary` 登记、孤立库、包名与目录一致性和目录容量；
库主源码及 `module_boundaries.py` 中 `APP_FAMILIES` 登记的应用区域，每个目录最多 16 个直接源码文件，模块职责见
[根目录 README](../../README.md#模块结构)。移动路径时必须同步 CRAP source/method 选择器，不能减少原检查范围。
已迁出的 `core/api/search`、`core/api/lyrics` 和 `core/lyrics` 生产代码不得重新放入 `app`；
`LIBRARY_OWNED_FAMILIES` 检查这些目录及其子目录，宿主集成测试仍可保留在 `app`。
单独查看完整报告可以运行 `./gradlew :app:crapReport`，该任务仍要求测试和报告输入有效，
但不会因超分退出失败。

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
缺少 XML、无效计数器、空范围、范围匹配不到文件或范围文件无被测方法时退出码为 2。
不使用平均分或历史 baseline 豁免；协程和 lambda 字节码同样保留，只使用 JaCoCo 内置的编译器过滤。
新增拆分组件应位于已覆盖的文件模式内，或同时更新范围配置。原文件中业务分支发生变化的
方法也必须加入门禁；不能因为其既有分数较高而遗漏改动。如果 Android 宿主入口的变更仅将
原有逻辑委托给独立组件，可将完整的迁入逻辑和宿主参数适配器纳入门禁，并通过 JVM 测试
验证适配器实际使用的来源、模式与生命周期分支。审查时必须核对委托前后的完整 diff，
报告应区分受检组件与未执行的宿主入口，不能宣称后者已具备覆盖率。

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

`verifyDomainDependencies` 通过 AGP `ScopedArtifact.CLASSES` 取得 app 自身的 Debug 编译产物，
使用运行 Gradle 的 JDK 所带的 `jdeps` 检查直接类依赖，使用 `javap` 检查宿主桥接的成员签名。
规则位于 `config/quality/domain-dependencies.json`；报告位于
`app/build/reports/domain-dependencies/report.json`。

- `core/player/queue` 包含队列模型、状态存储和编辑/导航策略；新增队列计算代码放入该包
- `data/sync/merge` 包含共享合并规则与宿主接口；Android 实现在相邻的 `data/sync/host`
- `core/download/catalog/assembly` 负责下载条目的元数据优先级、歌词覆盖和封面选择，文件访问由宿主提供
- 各计算域的全部编译类自动纳入检查，包括新类、嵌套类、lambda 和 Kotlin 生成类
- 类依赖采用允许列表，禁止直接引用播放器全局状态、数据库、网络、UI 或宿主适配器实现
- 混合文件中的身份与同步辅助函数仅允许列出的 JVM 方法签名；允许某个方法不等于允许整个文件
- `CoverUrlMapper` 只允许出现在既有清洗函数的类型签名中，不允许计算域调用其成员
- 下载条目组装仅使用已列出的元数据模型和计算辅助函数，不允许调用 `ManagedDownloadStorage` 的存储入口
- 产物缺失、重复类、空计算域、工具失败或分析结果缺少目标类均使检查失败

这是直接依赖检查，不是传递依赖或反射调用分析。共享模型的 Parcelable 实现和宿主桥接的
内部平台依赖不因此被认定为纯计算代码。调整允许列表时应审查具体方法的行为，保留
`stableKey` 与 `sameIdentityAs` 的不同语义，尤其不能以 key 相等代替本地歌曲同源判断。

门禁回归使用 JDK `javac --release 17` 编译隔离夹具，再运行真实 `jdeps` 和 `javap`，
验证非法调用、同包间接引用、嵌套类、新文件和桥接成员越界均能被拒绝。
