# 📦 NeriPlayer 库模块

本目录提供 NeriPlayer 的共享基础能力和平台数据访问实现。Android 应用由 `:app` 负责组装，各库通过明确的依赖关系和接口协作。

模块采用 `modules/<层>/<模块>` 的目录结构，对应 Gradle 标识 `:<层>:<模块>`。例如，`modules/data/youtube` 对应 `:data:youtube`。

## 🧭 模块索引

| Gradle 模块 | 目录 | 职责 |
| --- | --- | --- |
| `:core:common` | [core/common](core/common) | 集合、并发、文件、JSON、搜索和单位工具 |
| `:core:model` | [core/model](core/model) | 歌曲、搜索和同步基础模型 |
| `:core:logging` | [core/logging](core/logging) | 日志基础设施 |
| `:core:network` | [core/network](core/network) | HTTP、安全校验、代理和网页登录前台状态 |
| `:core:lyrics` | [core/lyrics](core/lyrics) | 歌词解析、转换和翻译时间轴对齐 |
| `:core:listen-protocol` | [core/listen-protocol](core/listen-protocol) | 一起听房间、事件和传输协议模型 |
| `:data:netease` | [data/netease](data/netease) | 网易云客户端、加密、二维码登录和账号仓库 |
| `:data:bilibili` | [data/bilibili](data/bilibili) | Bilibili 客户端、评论、播放解析和账号仓库 |
| `:data:youtube` | [data/youtube](data/youtube) | YouTube 客户端、播放解析、鉴权和 JavaScript 执行支持 |
| `:data:lyrics` | [data/lyrics](data/lyrics) | 外部歌词客户端和匹配策略 |
| `:data:listen-together` | [data/listen-together](data/listen-together) | 一起听 HTTP、WebSocket、地址校验和重连策略 |

## 🏗️ 依赖边界

- `app` 可以依赖 `data` 和 `core`，`data` 可以依赖 `core`
- `core` 不得依赖 `data`，库模块不得依赖 `app`，模块之间不得形成循环依赖
- Compose 页面、播放器与下载运行时、Room、Service 和 Worker 由 `app` 管理
- 库所需的网络客户端、设备信息和配置由调用方注入，不通过 `AppContainer` 或 `PlayerManager` 获取宿主状态
- 需要反映运行时变更的设置通过 provider 读取，避免在构造时保存过期快照
- 一起听传输层接收宿主注入的 HTTP 客户端；会话状态、播放控制与生命周期由 `app` 协调
- 模块专用资源与实现共同维护；例如 YouTube 的 JavaScript assets 和 consumer R8 规则归属 `:data:youtube`

第三方 Git 子模块位于 [`np-submodule`](../np-submodule)。KSP 处理器和 Gradle convention plugin 属于构建工具，职责与运行时库分离。

## 📁 源码组织

包名必须与 `src/<sourceSet>/java` 或 `src/<sourceSet>/kotlin` 下的相对目录一致。目录按业务职责组织，同一能力的策略、协调器和辅助类型放在一起；公开入口与内部实现应便于区分。

以 `:data:youtube` 的 `core/api/youtube` 包为例：

| 目录 | 职责 |
| --- | --- |
| 根目录 | 客户端与播放仓库入口 |
| `bootstrap` | 启动信息解析、缓存和加载协调 |
| `challenge` | JavaScript 挑战求解、执行队列和播放器脚本缓存 |
| `fallback` | NewPipe 回退记录 |
| `parser` | 音乐业务响应解析 |
| `playback` | 播放源策略、音频选择、缓存和访问协调 |
| `potoken` | PO Token 获取与清单处理 |
| `protocol` | 请求模型、请求构造及播放器响应解析 |
| `transport` | HTTP 下载、状态处理和响应体读取 |

模块单元测试位于自身的 `src/test`，测试包与对应职责保持一致。需要 Android 宿主参与的集成测试位于 `app`。应用层目录索引参见 [贡献指南](../CONTRIBUTING.md)。

## 🧪 质量检查

结构检查覆盖模块登记、依赖方向、循环依赖、禁止导入、包名与目录一致性，以及源码规模约束。库主文件必须少于 2000 行，每个生产源码目录最多包含 16 个直接 Kotlin/Java 文件。

以下命令均在仓库根目录执行。结构检查无需 Android SDK：

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

运行所有自有模块的 JVM 测试、合并覆盖率、CRAP 门禁和 Android lint：

```bash
./gradlew verifyModularization
```

运行指定模块的单元测试：

```bash
./gradlew :data:youtube:testDebugUnitTest
```

构建环境和设备测试要求见 [贡献指南](../CONTRIBUTING.md)，覆盖率计算与门禁配置见 [质量工具说明](../tools_pub/quality/README.md)。

## 🛠️ 新增与调整模块

1. 在 `modules/core` 或 `modules/data` 下创建具有单一职责的模块，应用 `build-logic.android.feature-library` convention
2. 在 `settings.gradle.kts` 中通过 `includeOwnedLibrary` 登记，并加入 `app/build.gradle.kts` 的 `ownedLibraryPaths`，确保进入测试与覆盖率检查
3. 明确公开接口和依赖方向，将模块资源、consumer R8 规则和测试放在所属模块内
4. 调整包名或文件位置时，同步调用方、源码路径契约、CRAP 选择器和文档链接，保持原有检查范围
5. 运行结构检查、受影响测试及 `verifyModularization`；涉及生命周期、持久化或平台行为时补充相应集成验证
