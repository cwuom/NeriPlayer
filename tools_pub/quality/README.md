[English](./README_EN.md) | [中文](./README.md)

# 质量检查 / Quality checks

这些工具检查方法复杂度、模块归属和依赖边界。需要 JDK 17、Android 构建环境和 Python 3；Python 脚本只使用标准库。包含 `:playback:runtime` JVM 测试的门禁还需可被 Gradle 发现的 JDK 21，生产编译目标仍为 17。

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

| 任务 | 检查范围与行为 |
| --- | --- |
| `verifyModularization` | 汇总结构检查、`app` 与自有 JVM 库的 Debug JVM 测试、合并 CRAP 报告、域依赖、lint 与构建逻辑测试 |
| `:app:check` | 包含 CRAP 与域依赖检查 |
| `:app:crapReport` | 校验输入并生成报告，分数超限不失败 |
| `:sync:verifyCrap` | 合并 `:sync` 与 `:model` 中同步模型的覆盖率 |
| `:local:verifySyncIntegrationCrap` | 运行同步 Android 适配的 JVM 测试并检查 `:local` 下整个 `data/sync` 目录，不替代 `:local` 全量门禁 |

14 个自有库登记在 [gradle/owned-modules.txt](../../gradle/owned-modules.txt)。各模块职责见 [实现概览](../../docs/implementation.md#模块结构)：

- **Kotlin/Java 模块**：完整收集覆盖率；独立 CRAP 任务沿用统一阈值与各自源码范围。
- **`:native` 模块**：纯 Native Android library，仅参与模块登记、结构与 lint 检查，不生成 JaCoCo 数据、CRAP 或 JVM 测试任务。Native USB host 三组 profile 与四 ABI 编译由独立 CI 检查；`tests/dsp/` 是另一套独立 CMake/CTest 测试。这些检查不在 JVM 门禁内，命令见 [Native 模块](../../modules/native/README.md)。

按受影响模块单独执行：

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

## CRAP

`crap_report.py` 按 `C² × (1 − cov)³ + C` 计算方法风险分：

| 指标 / 规则 | 定义 |
| --- | --- |
| `C` | JaCoCo `COMPLEXITY` 的 `missed + covered`，即方法圈复杂度 |
| `cov` | `covered / C`（复杂度覆盖率），作为路径覆盖率的近似值，不等同于行覆盖率或严格独立路径测量 |
| 通过条件 | 受检方法未取整原始分 `<= 9` 通过，`> 9` 失败；无平均分折算或历史 baseline 豁免 |
| 满覆盖下限 | `cov = 100%` 时 `CRAP = C` |

阈值 `9` 用于检查复杂度与覆盖率风险。重构应让入口、业务步骤、状态所有权与失败处理更易追踪，禁止仅为降分拆分函数、增加纯转发层，或放宽阈值、缩小受检范围、弱化有效测试。当前门禁未集成自动变异测试（Mutation testing），审查需结合模块依赖、行为与边界测试、lint 以及必要的集成或设备验证，评估职责划分与断言有效性。

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

依赖门禁分为源码结构检查与字节码直接依赖检查，规则定义在 [module_boundaries.py](./module_boundaries.py) 与 [domain-dependencies.json](../../config/quality/domain-dependencies.json)，不按目录名称推导依赖层级：

| 层级 | 入口 | 检查工具与规则 |
| --- | --- | --- |
| 源码与模块边界 | `module_boundaries.py` | 无需 Android SDK；读取模块登记、Gradle 声明与源码，检查未登记构建节点、依赖循环、禁止引用、生产包归属与包目录一致性。`:model` 不依赖项目实现，库不依赖 `app`，受管生产包不回流 `app`。库主源码单文件少于 2000 行；库生产目录及 `APP_FAMILIES` 每个目录最多 16 个直接 Kotlin/Java 文件。 |
| 字节码域依赖 | `verifyDomainDependencies` | 使用运行 Gradle 的 JDK 自带 `jdeps` 检查直接类依赖，并用 `javap` 校验桥接成员的完整 JVM 签名。平台协议不得反向读取账号仓库、Room 或播放器；播放与下载规则、同步计算、歌词解析与本地存储计算各自维护允许列表。 |

### 域配置与检查边界

- **自动纳入与最小授权**：受管包内的新类、嵌套类、lambda 和 Kotlin 生成类自动受检；放行单个桥接方法不会放行整个宿主类。
- **排除规则**：`excluded_classes` 仅排除指定源类及其 `$` 内部类，不支持通配符；排除子包必须由另一受管域接管，且排除源类不会放宽其它类对它的引用限制。
- **失败条件**：缺失产物、重复类、空域、工具执行失败或分析遗漏目标类均直接失败。
- **输出与分析范围**：聚合报告生成于 `app/build/reports/domain-dependencies/report.json`，`:platform`、`:sync`、`:listentogether` 的独立报告位于各自模块的 `build/reports/domain-dependencies/report.json`。该任务检查直接字节码依赖，不追踪传递依赖或反射调用；共享模型和桥接实现内部的平台依赖仍需单独审查。

## 工具回归 / Tool tests

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

域依赖测试使用 `javac --release 17` 编译隔离夹具，再运行真实 `jdeps` 和 `javap`，验证非法类依赖、桥接成员、嵌套类与新增文件会被拒绝。它们验证门禁工具，不替代业务测试。
