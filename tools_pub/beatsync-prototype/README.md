# BeatSync 原码与 WebSocket 验证原型

独立实验工程，不加入正式 App 的 settings 或依赖图。生产代码、协议和播放器尚未修改。

## 已实现

- 固定 BeatSync `94c38ab16ec861835ce0b4bc3a7db82dabbb9b88` 的原始源码及 MIT LICENSE；`js/upstream/UPSTREAM.json` 记录源路径和 SHA-256。每次打包验证所有源文件 hash。
- Bun 1.3.8 执行未修改的上游 NTP 测试文件；测试模块依赖通过 `js/tsconfig.json` 指向最小宿主边界。
- esbuild 仅打包并去除类型；Babel 另生成 Rhino 需要的 ES5 版本，不修改上游文件。
- Node 执行原码生成参照；Rhino 解释模式和 Zipline 内置 QuickJS 分别执行同一组 1,516 个场景，逐字段比较，数值绝对误差阈值 `1e-6`。
- 同一个 OkHttp 客户端对 Java-WebSocket 与 Ktor CIO 做 4 轮交替测试；每轮 10 对预热、60 对测量，同时保持第二连接发送控制消息。服务只绑定 `127.0.0.1`，使用系统分配端口。
- 独立 Android smoke 工程复用实验代码，可在前台手动运行。它不播放音频，也不是生产后台服务。

## 范围

直接运行的原逻辑包括 `ntp.ts` 中 NTP 测量、探针对过滤、最小 RTT 选取、等待时间、探针发送/reset，以及服务端 `config.ts` 的 RTT 提前量。

本轮没有移植完整 `global.tsx` / `RoomManager.ts`，没有验证 FIFO 窗口、房间装载状态机、重连退避、手动 nudge 和播放器。WebSocket 实验里的 HELLO/PING/ERROR 是测试协议，不是最终 BeatSync LAN 协议。

`shared.ts` 只提供单调时钟输入、常量和 NTP 消息类型；发送测试的 socket/定时器由 `entry.ts` 注入。生产方案仍应在原生网络回调捕获 t1/t3，不能用 JS 获得运行机会的时间替代。

## 运行（Windows）

需要 Node 22、JDK 21；Android 构建另需 SDK platform 37。Bun Windows x64 测试运行时与构建工具由 package-lock 固定。首次运行安装到本目录的 node_modules，不进行全局安装。

在仓库根目录：

```powershell
./tools_pub/beatsync-prototype/run.ps1 -Phase All -JavaHome '你的 JDK 21 路径'
./tools_pub/beatsync-prototype/run.ps1 -Phase AndroidBuild -JavaHome '你的 JDK 21 路径' -AndroidSdk '你的 Android SDK 路径'
```

`Js` 只运行原测试、参照生成和两个引擎；`Network` 只运行 WS；`All` 不自动安装或启动 Android App。每个 native 命令失败立即停止，不把旧报告当作新成功。

AndroidBuild 默认构建推荐组合（QuickJS + Java-WebSocket + OkHttp，`-Pcandidate=true`）。传 `-FullAndroidComparison` 可复现包含 Rhino/Ktor 的全候选构建；其 release 当前预期会因缺失桌面 JDK 类而失败，证据保留在 results。全候选 debug APK 已保存为 `artifacts/beatsync-full-debug.apk`。

最新构建的 debug APK 位于 `android-smoke/build/outputs/apk/debug/`。release 为未签名的 R8 验证包，不用于安装发布。设备运行需手动点击 Run candidate validation 并保持前台；它没有生产级后台/重入生命周期管理。结果在应用私有目录 `files/results/`。推荐组合的 debug 包可通过 `adb shell run-as moe.ouom.neriplayer.beatsynccandidate cat files/results/zipline-quickjs.json` 读取结果；全候选包 id 为 `moe.ouom.neriplayer.beatsyncprototype`。候选包测 JS 同组场景和单次回环握手，全候选包才运行完整网络对照。

## 结果解释

- `node-oracle.json`：场景组成和固定随机种子。16 个边界/行为场景、1,500 个生成场景。
- `rhino.json` / `zipline-quickjs.json`：一致性与桥接耗时。Rhino 为预取 Function.call；QuickJS 为 evaluate(String)。这两条调用路径不同，不得据它们的耗时排名引擎。
- `websocket-loopback.json`：完整 t0/t1/t2/t3 原始样本、纯度、每轮分位数。
- `websocket-summary.json`：从原始样本合并后的统计，不平均各轮 P95。
- Ktor 在 incoming channel 取出 frame 后记录 t1；Java-WebSocket 在 onMessage 回调入口记录 t1。这些边界本身不同，且都不是网卡时间戳。
- 本机测试包含 JVM/JNI、OS 调度、框架队列和编码开销；不能据此承诺 Wi-Fi、Android 或实际出声同步精度。
- R8 配置对 Rhino 和 QuickJS 保守保留类，仅用于验证可构建，不是包体优化结论。

## 源码与更新

上游文件不允许手动修改；更新时换完整 commit、重新导入源文件与许可证、更新 manifest，再重新跑原测试及差异检查。

当前仅是源码执行可行性实验。参照结果是每次从原码重新生成的临时文件；后续正式实现应把审阅过的场景/黄金结果冻结到 CI，避免 oracle 随实现一起漂移。

TypeScript 算法原码版权归上游作者，许可证见 `js/upstream/LICENSE`。其余依赖沿各自许可证；原型不改变主项目许可。

## Production Web Audio extraction

`node js/build-audio.mjs` validates the upstream manifest, locates 10 symbols in the pinned `global.tsx` AST, copies their exact source spans, and bundles them with the unmodified `audioContextManager.ts`. Platform adapters live in `js/audio/`; output is the owned module assets directory. `node js/audio-test.mjs` executes the built product bundle against a simulated audio clock to check absolute deadlines, cancellation, latency filtering and NTP. This is not an acoustic accuracy measurement.

The actual Android application has shell-only Debug diagnostics in DebugLanReceiver; no separate test app is needed. Release excludes this receiver. Signed audio URLs are never exported in diagnostic output.
