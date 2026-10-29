# BeatSync 复用与 WebSocket 原型结果

日期：2026-10-28。分支：`codex/lan-beatsync-restart`；基线 `8eee851c`。

## 当前结论

1. **原始 JS 算法可直接保留。** Node、Rhino 解释模式与 Zipline 内置 QuickJS 执行同组场景均匹配。下一阶段优先验证 QuickJS 的 Android 设备运行，再决定是否采用产品方案；不再默认把全部算法翻译成 Kotlin。
2. **暂时保留 Java-WebSocket + OkHttp。** 本次 Windows 回环测试没有显示 Ktor CIO 值得替换现有候选的收益。它不构成两种库在 Android/Wi-Fi 下的性能排名。
3. **没有验证实际声音同步。** 本轮覆盖原算法执行和传输闭环，完整房间逻辑、Android 后台、跨设备输出仍待后续验证。

代码和复现入口：[原型 README](../tools_pub/beatsync-prototype/README.md)。所有新生产能力均未接入主 App。

## 1. 原始代码与测试

固定来源：`freeman-jiang/beatsync@94c38ab16ec861835ce0b4bc3a7db82dabbb9b88`。

- 原样保存 `ntp.ts`、`ws.ts`、`constants.ts`、`config.ts`、`ntp.test.ts` 和 LICENSE，共 6 个 hash 校验文件。
- Bun 1.3.8：未修改的上游 NTP 测试 **7 pass / 0 fail**，9 条 expect。
- Node 22.15.0 生成 **1,516** 组原码输出：16 个边界与行为场景、1,500 个固定种子生成场景。
- Rhino 1.8.0 解释模式：1,516 组一致，0 不匹配。
- Zipline QuickJS 1.28.0 的真实 JNI 运行时：1,516 组一致，0 不匹配。
- 数值比较阈值为绝对误差 `1e-6`；这是计算结果容差，不是音频同步精度。

实际覆盖：最小 RTT 样本选择、同 RTT 顺序、负 offset、等待量截断、RTT 提前量、探针对纯度 5ms 边界、跨组响应、pending reset、25ms 探针发送和第二探针前连接关闭。

未覆盖：完整 16 样本窗口管理、heartbeat/reconnect 状态机、RoomManager 装载协调、nudge、播放器调度。原始文件保留不代表全部符号都已经运行。

宿主边界：用测试时钟替代 `epochNow()`；提供常量和最小消息枚举；发送/定时器场景使用可控宿主替身。没有重写上述算法函数。原源码作为模块进入 esbuild；Node/QuickJS 执行 ES2015 产物，Rhino 执行 Babel 转换的 ES5 产物。

### 实际发现的兼容性问题

- Rhino 1.8.0 对本次 `for (const ... of ...)` 产物解析失败；增加标准 Babel 语法降级后通过全部对拍。没有编辑上游函数。
- Zipline 的 Windows 原生 QuickJS 可以加载并执行。早期失败由实验包装器对 undefined 返回值错误调用 `toString()` 引起，修复后通过；不能把包装器错误记成引擎不支持。
- 本实验使用 Zipline 的 QuickJs host API，没有启用 Zipline Kotlin/JS 编译插件或完整服务桥接。因此没有验证这些插件与主工程 Kotlin 2.4.10 的全部兼容性。
- 项目本来就使用 AndroidX JavaScriptSandbox，后续应加入设备端候选；当前未测，不重复引入新运行时前应比较该路径。

两种 JVM→JS 调用都进行了 1,000 次预热和 3,000 次测量，原始统计在各引擎 JSON 中。最终复现脚本测量期间有 Android 构建并行运行，因此这些耗时只能证明调用可执行，不能作为隔离性能基准。Rhino 使用预取函数的 Function.call，QuickJS 使用 evaluate(String)，不据此直接排名两种引擎速度，也不把 Windows 耗时外推到 Android。

## 2. WebSocket 对照

环境：Windows 11、JDK 21.0.12.1，本机 127.0.0.1 回环；Java-WebSocket 1.6.0 / Ktor CIO 3.6.0 / OkHttp 5.4.0。

方法：每个实现 4 轮，按 A→B、B→A 交替；每轮 10 对预热、60 对测量。每对两包间隔请求为 25ms，按真实 t0 间隔计算纯度；第二个客户端持续参与控制消息。共每实现 240 对、480 个探针样本。

| 实现 | 纯探针对 | RTT P50 | RTT P95 | RTT P99 | 最大 RTT |
|---|---:|---:|---:|---:|---:|
| Java-WebSocket | 240 / 240（100%） | 1.132ms | 1.676ms | 1.867ms | 2.628ms |
| Ktor CIO | 237 / 240（98.75%） | 1.385ms | 2.077ms | 3.850ms | 15.972ms |

所有轮次同时通过：握手前探针拒绝、版本不匹配、畸形 JSON、错误探针索引、缺失 t0、未知消息、两连接并存、关闭后重新连接与握手。

数据来自实际四时间戳：`RTT=(t3-t0)-(t2-t1)`。P95/P99 从合并样本计算，没有平均分位数。完整样本保留在 `websocket-loopback.json`，汇总脚本可重新生成 `websocket-summary.json`。

解释限制：

- Ktor 的 t1 在 incoming channel 取出消息后记录，Java-WebSocket 在回调入口记录；两个边界不同，框架内部排队时间未完全可见。
- 首轮启动/JIT、Windows 调度、GC 都可能影响样本。没有 CPU 隔离或长期压测，不能证明 3 对污染的根因。
- 服务端统一应用处理器，测试协议仅 HELLO/NTP/PING/ERROR，没有房间广播状态机。
- 没有测试 Wi-Fi/热点、丢包、慢消费者、手机后台和实际音频；不承诺毫秒级跨设备效果。

## 3. Android 独立验证包

工程在 `tools_pub/beatsync-prototype/android-smoke`，minSdk 28、targetSdk 36、compileSdk 37，包含两种 JS 引擎与两套 WS 服务端，复用桌面实验源码。仅监听本机回环，点击按钮后在前台执行，结果写入 app 私有目录。

全候选 debug APK 已构建成功并保存在原型 artifacts 目录；全候选 release 的 R8 检查发现 Rhino 的 java.beans / jdk.dynalink 和 Ktor 的 java.lang.management 引用，构建失败。完整缺失项记录已保存，没有添加全局 dontwarn 掩盖。精简推荐组合（QuickJS + Java-WebSocket + OkHttp）已通过 `assembleDebug`、`minifyReleaseWithR8`、`lintVitalRelease` 和 `assembleRelease`；release 为未签名包。设备列表为空，本轮没有 Android 运行或真机验证。

构建中已处理：AGP 内置 Kotlin 默认编译器版本过低，按主项目方式将构建 classpath 对齐 Kotlin 2.4.10；明确配置共享 Kotlin 源码目录。没有使用 `-Xskip-metadata-version-check`。候选源码切换还出现一次旧增量符号问题，禁用该实验工程的 Kotlin 增量编译后消失。

R8 使用保守 keep 规则，因此即使构建通过，也不是最终包体、混淆或正式集成策略。测试 Activity 没有后台保活或生产级生命周期管理，仅用于前台验证。

## 4. 复现与后续决策

一键入口 `tools_pub/beatsync-prototype/run.ps1` 支持 `Js`、`Network`、`All`、`AndroidBuild`；失败即停止，原始文件 hash 不符时打包失败。

下一阶段门槛：

1. Android 设备运行同样 1,516 组，验证 JNI ABI、R8、内存释放与反复创建/销毁；加入现有 JavaScriptSandbox 作为对照。
2. 在不修改上游核心算法的前提下抽取 RoomManager 与客户端状态机，增加原始行为测试和代次取消边界。
3. Android 上复测网络，尤其真实 Wi-Fi、切网和长时间后台；优先保留 Java-WebSocket，除非数据支持更换。
4. 最后接入现有播放器，用实际输出录音确认同步精度。

目前两个桌面原型已得到可重复结果；设备端结果未产生，不把桌面通过当作产品实现完成。

## 5. 可交付文件

- `tools_pub/beatsync-prototype/artifacts/beatsync-candidate-debug.apk`：推荐组合，可供后续设备测试。
- `tools_pub/beatsync-prototype/artifacts/beatsync-candidate-release-unsigned.apk`：R8 压缩构建证据，未签名。
- `tools_pub/beatsync-prototype/artifacts/beatsync-full-debug.apk`：包含 Rhino/Ktor 的全候选比较包。
- `results/apk-manifest.json`：APK 大小与 SHA-256；`results/android-build.json`：构建矩阵。

APK 是多候选开发实验，不用其总体大小推算正式 App 增量。构建提示 libquickjs.so 未剥离符号；后续正式集成还需 ABI、对齐、许可证及打包配置检查。产物目录不纳入 Git。