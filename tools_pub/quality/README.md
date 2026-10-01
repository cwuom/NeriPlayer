[English](./README_EN.md) | [中文](./README.md)

# 质量检查 / Quality checks

这些工具检查方法复杂度、模块归属和依赖边界。需要 JDK 17、Android 构建环境和 Python 3；Python 脚本只使用标准库。

| 检查 | 用途 |
| --- | --- |
| `verifyCrap` | 结合方法复杂度与 JVM 测试覆盖率，检查指定范围的风险分数 |
| `verifyModuleBoundaries` | 检查模块登记、源码归属、声明的依赖方向、包目录和容量 |
| `verifyDomainDependencies` | 检查受管包的直接 JVM 类依赖与宿主桥接成员调用 |

## 运行 / Commands

在仓库根目录运行：

```bash
./gradlew verifyModularization
./gradlew :app:verifyCrap :app:verifyDomainDependencies
./gradlew :app:crapReport
```

`verifyModularization` 汇总结构检查、app 和自有 JVM 库的 Debug JVM 测试、合并 CRAP 报告、域依赖、lint 和构建逻辑测试。`:app:check` 包含 CRAP 与域依赖检查。`:app:crapReport` 保留输入校验，但不会因分数超限而失败。

14 个自有库由 [gradle/owned-modules.txt](../../gradle/owned-modules.txt) 登记。`:native` 是纯 Native Android library，参与登记、结构与 lint 检查，不生成 JaCoCo 执行数据、不创建 CRAP 或 JVM 测试任务；原有 Kotlin/Java 模块仍完整收集覆盖率。Native host 三组 profile 和四 ABI 编译由独立 CI 验证，命令见 [Native 模块](../../modules/native/README.md)。只检查受影响模块时可使用：

```bash
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :download:logic:verifyCrap :download:runtime:verifyCrap
./gradlew :local:verifyCrap :local:lintDebug
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
```

独立 CRAP 任务保留相同阈值和对应源码范围。`:sync:verifyCrap` 合并同步库与 `:model` 中同步模型的覆盖率；`:local:verifySyncIntegrationCrap` 单独运行同步 Android 适配的 JVM 测试，检查本地库整个 `data/sync` 目录，不替代本地库全量门禁。模块职责见 [根目录 README](../../README.md#模块结构)。

## CRAP

`crap_report.py` 使用 `C² × (1 − cov)³ + C`：

- `C` 是 JaCoCo `COMPLEXITY` 的 missed + covered，即方法圈复杂度
- `cov` 是 covered / C，即复杂度覆盖率，作为路径覆盖率的近似值；不是行覆盖率，也不是严格的独立路径测量
- 受检方法的原始分数大于 9 时失败，等于 9 时通过，不使用平均分或历史 baseline 豁免

**9 是本项目的风险门禁，不是架构质量证明。** 即使覆盖率达到 100%，CRAP 仍等于圈复杂度。低分不证明职责清晰，覆盖率也不证明断言有效。重构应让入口、业务步骤、状态所有权和失败处理更容易追踪，不能仅为降分拆函数、增加纯转发层，或调整阈值、受检范围和有效测试。审查仍需结合依赖、行为与边界测试、lint，以及必要的集成或设备验证。Mutation testing 可检查断言能否发现错误，当前门禁未自动提供它。

公式与计数器说明：[CRAP 原作者](https://www.artima.com/weblogs/viewpost.jsp?thread=215899)、[JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/counters.html)。

### 范围 / Scope

[config/quality/crap-scope.json](../../config/quality/crap-scope.json) 使用 `source_patterns` 选择完整文件，使用 `method_scopes` 选择类与 JVM 方法名，包括构造方法和 Kotlin 默认参数方法。递归文件模式会包含新增文件与子目录；范围外的方法仍进入报告，但不决定门禁结果。

`:app:verifyCrap` 通过 AGP `ScopedArtifact.CLASSES` 取得 app 和登记库自身的 Kotlin/Java 字节码，合并真实测试执行数据生成 JaCoCo XML。源码从各 JVM 模块的 `src/main/java` 和 `src/main/kotlin` 合并到 `app/build/reports/crap/sources`，重复路径、缺失或空执行数据都会失败。

报告器遇到无效 XML、计数器、空配置、规则无匹配或缺少受检源码/方法时返回 2；超分返回 1。纯接口通过类记录验证源码存在，可执行默认实现仍计算分数。协程、lambda 与 Compose 字节码以 JaCoCo 内置过滤后的实际计数为准，源码分支数不等于报告中的 JVM 复杂度。

移动或拆分代码时，对照完整 diff 与实际 XML 更新范围，保留原有受检逻辑。Kotlin `internal` 方法可能带模块后缀，方法选择器要与真实名称一致。Android 宿主只做委托时，也要区分已测试组件与未执行入口；组件覆盖率不能证明宿主 I/O、Provider 或 Compose 行为。

### 报告 / Reports

聚合报告位于 `app/build/reports/crap/`：

| 文件 | 内容 |
| --- | --- |
| `methods.json` | 能映射到主源码的全部 JVM 方法，含签名、位置、复杂度、覆盖率、分数与受检标记 |
| `above-8.md` | 原始分数严格大于 8 的方法，含门禁范围外的方法 |
| `scope.md` | 门禁范围内的全部方法 |
| `coverage.xml`、`coverage/index.html` | 合并后的 JaCoCo 覆盖率 |

独立 CRAP 报告位于各模块的 `build/reports/crap/`；本地同步适配报告位于 `modules/local/build/reports/sync-crap/`。

## 依赖 / Dependencies

`module_boundaries.py` 无需 Android SDK，读取登记、Gradle 声明和源码，检查未登记构建节点、依赖循环、禁止引用、生产包所有者与包目录一致性。`:model` 不依赖项目实现，库不得依赖 app，受管生产包不得回流 app。库主源码文件少于 2000 行；库生产目录及脚本的 `APP_FAMILIES` 区域每个目录最多 16 个直接 Kotlin/Java 文件。

模块边界不能代替同库内部的职责边界。平台协议不应反向读取账号仓库、Room 或播放器；播放与下载规则、同步计算、歌词解析和本地存储计算分别保留自己的允许列表。具体规则在 [module_boundaries.py](./module_boundaries.py) 与 [domain-dependencies.json](../../config/quality/domain-dependencies.json)，不从目录名称推导依赖层级。

`verifyDomainDependencies` 使用运行 Gradle 的 JDK 所带的 `jdeps` 检查直接类依赖，用 `javap` 检查桥接成员的完整 JVM 签名。受管包的新类、嵌套类、lambda 和 Kotlin 生成类自动纳入检查；允许一个桥接方法不等于允许访问整个宿主类。

`excluded_classes` 只排除指定源类及其 `$` 内部类，不支持通配符；排除子包必须由另一受管域覆盖。排除源类不会放宽其它类对它的引用限制。缺失产物、重复类、空域、工具失败或分析遗漏目标类都会失败。

聚合域报告位于 `app/build/reports/domain-dependencies/report.json`；`:platform`、`:sync`、`:listentogether` 的独立报告位于各自模块的 `build/reports/domain-dependencies/report.json`。这是直接依赖检查，不分析传递依赖或反射，也不证明共享模型或桥接内部没有平台依赖。

## 工具回归 / Tool tests

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

域依赖测试使用 `javac --release 17` 编译隔离夹具，再运行真实 `jdeps` 和 `javap`，验证非法类依赖、桥接成员、嵌套类与新增文件会被拒绝。它们验证门禁工具，不替代业务测试。
