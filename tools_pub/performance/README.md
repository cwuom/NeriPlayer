# Android 性能采样

`perf_trace.py` 只使用 Python 3 标准库。采集需要 ADB 和设备自带的 Perfetto；分析需要单独准备的官方 `trace_processor_shell`，通过 `--processor` 明确指定。工具不下载二进制，不使用 root，不修改系统设置，不清除应用数据，也不切换主题、启动或停止应用。

## 采集

先取得设备调试授权；实体设备操作必须获得用户明确确认。每次指定 `--serial`，避免误操作另一台设备。先在应用中选好主题、页面、同一首本地音乐、相同动效和模糊效果；将亮度、刷新率、播放状态、网络条件、温度和充电状态控制在可比范围。工具只读取这些条件的部分系统快照，不会替你调整。

从仓库根目录运行，例如：

```bash
python3 -B tools_pub/performance/perf_trace.py capture \
  --serial '<device-serial>' \
  --package moe.ouom.neriplayer \
  --theme material-you \
  --scenario interaction \
  --build-label '<git revision / APK identity>' \
  --seconds 30 \
  --notes 'same local track, cover/lyrics toggle sequence, all visual effects preserved' \
  --out .tmp/performance/material-interaction-01
```

输出 `status: ready` 表示 Perfetto 的数据源启动等待已完成，可开始执行预定动作。采集在设备后台持续 30–60 秒，本地进程等待结束后拉回 trace。不要同时启动多个采集。`ready` 的主机时刻不是设备 trace 时间戳，不能直接当作输入延迟的起点。

采集内容包括调度切换/唤醒、CPU 频率/空闲、进程及线程身份、启动相关 atrace、实际 FrameTimeline、GPU 频率事件和设备支持的电池/电源轨计数。使用 64 MiB `RING_BUFFER`，每秒流式写入 `/data/misc/perfetto-traces/` 中本轮 UUID 文件，不需要 root 或改写权限。内核、ROM 或硬件不支持的数据源可能没有数据；配置里请求了一个数据源不代表它确实可用。

GPU 硬件计数器依赖设备厂商。可以先用同一显式 serial 的 `adb shell perfetto --query` 核实 producer 名称及计数器 ID，再提供 `--gpu-source gpu.counters.<vendor>` 和一个或多个 `--gpu-counter-id`。不应猜 ID；工具保存原始值，不会自动把任意计数器命名为 GPU 利用率。默认仅请求 GPU 频率。

输出目录必须不存在，内含 `config.pbtxt`、`capture.json` 和 `trace.pftrace`。元数据记录设备硬件序列号、SoC/平台、在线 CPU 核数、调用方主题/场景/构建标签、实际包版本、动效比例、亮度/刷新率设置、电池/热状态快照、采集命令结果及 SHA-256。通过 IP 连接时用硬件序列号核对目标设备；属性不可读时保留未知状态。主题和场景标签来自调用方声明，需要另外记录页面和操作证据。

通过只读 `ps` 确认本轮 Perfetto 进程结束，并至少等待配置时长后再拉回文件；权限拒绝或进程状态不可读时保留失败诊断，不会把不可读误当退出。成功拉回 trace 后仅删除本次生成的设备临时文件。远端清理失败或超时时，采集仍标记成功，CLI 会警告并显示需要手动清理的远端路径，`capture.json` 保留清理返回码和诊断。采集失败时保留诊断信息和设备文件路径；已启动的 trace 按配置时长自行结束。未确认结束前不要删除设备文件。工具不终止其它采集或进程。

## 分析与复算

```bash
python3 -B tools_pub/performance/perf_trace.py analyze \
  --trace .tmp/performance/material-interaction-01/trace.pftrace \
  --capture-metadata .tmp/performance/material-interaction-01/capture.json \
  --processor /absolute/path/to/trace_processor_shell \
  --package moe.ouom.neriplayer \
  --out .tmp/performance/material-interaction-01-analysis
```

分析不访问设备。`--start-ms` 和 `--duration-ms` 可选取相对于 trace 起点的窗口；选择窗口前，先在 Perfetto UI 核对实际动作与场景。报告保存处理器版本/校验值、每条 SQL、CSV 和诊断、100 ms CPU 分箱及 `summary.json`。CPU 分箱按区间累加并逐行写出，不会在内存中保存全部分箱；长窗口仍会生成与时长成比例的 CSV。查询失败会保留 `returncode`；等待处理器超时时，返回码为 `null`，对应 `.stderr.txt` 保存超时原因，CSV 留空。缺数据输出 `unknown`/`null`，不会用零代替。GPU/电池等计数器查询失败或超时时，`counters.status` 为 `unknown` 并保留失败原因，不影响独立的 CPU 查询结果。

| 指标 | 方法与边界 |
| --- | --- |
| CPU 时间 | 汇总目标包及 `:子进程` 的线程调度区间，与选定窗口相交后求和；通过 UPID/UTID 避免 PID 重用误归属 |
| CPU 平均负载 | CPU 时间 / 窗口时长，单位为核；1 核 = 单核 100%，多线程可超过 100%，不是整机 CPU 容量占比 |
| CPU 峰值 | 以分析窗口起点对齐的固定、互不重叠 100 ms 分箱，取核数最大值；跨箱区间按交集分摊，末尾不足 100 ms 的部分只进入平均值，不参与峰值 |
| FrameTimeline | 仅取 actual 表，按 `(upid, surface_frame_token)` 去重，避免多图层或 expected 匹配放大样本；选取与窗口相交的 token 后，使用其全部图层判断包络是否完整位于窗口内；跨边界的 token 分类为未知且不计入持续时间；公开未知分类数和已分类分母 |
| 冷启动 | 保留 Perfetto `android_startups` 推断的类型及耗时；`cold-start` 标签本身不能证明冷启动，需检查 `startup_type`。缺记录时为未知 |
| 即时交互 | 在相同操作窗口比较 CPU 与 actual 帧卡顿；FrameTimeline 持续时间不等于输入到显示延迟，也不能证明每次操作语义都生效。需要独立动作记录/视频或相应输入延迟数据才能断言响应时延 |
| GPU | 保留频率和可选硬件计数器的原始样本及单位、样本极值；频率不是利用率；未校验厂商计数器语义和进程归属前，利用率为未知 |
| 功耗 | 保留设备实际提供的电池/电源轨计数，默认整机瓦数为未知；充电中的电池电流不能当成整机耗电，频率也不能换算成瓦数；电源轨需另行校验单位、覆盖范围和归属 |

CPU 所需查询失败、缺目标调度、目标调度未闭合、调度数据丢失或发现截断时，CPU 汇总及分箱负载均标为未知。请求时长明显不完整或元数据 SHA-256 不符也会使汇总失效。没有采集元数据时，只能描述保存下来的窗口，不能证明 trace 完整。服务级累计 `traced_chunks_discarded` 不能单独证明本次采集丢数据，保留供审查；功耗数据源报错不会被误判成 CPU 调度损坏。任何报告都应一起审查原始 health 和 stderr。

处理器的 metadata 查询是 CPU 和完整性检查的必要步骤，查询失败时无法排除关键调度 setup 失败，CPU 与完整性均为未知。采集元数据的 `requested_duration_ms` 必须是有限正数，布尔值、字符串、缺值和非正数均无效；缺少有效时长时仍可描述保存窗口中的 CPU，但完整性为未知。输入中的非有限 JSON 数字会在报告副本中保留为字符串，原采集文件不变。

健康检查保留全部非零 stats。`ftrace_setup_errors` 的 notice 计数表示部分请求项不可用，单独不能证明调度损坏；若 setup 元数据明确指出必需的 `sched_switch` 未启用，则 CPU 失效，没有具体 setup 文本时缺失项仍未知。`traced_buf_readaheads_failed` 的 info 计数可能来自正在写入的末包，不等同丢数据。实际 overrun、dropped/missing 事件、调度解析错误、缓冲区丢弃/覆盖及 `data_loss` 仍会使报告失效，包括旧的 `write_into_file + DISCARD` 配置；不会通过放宽门禁复用旧配置结果。

## 双主题、启动与交互对比

Material You 和 Clean 使用独立目录，保持同一 APK、音乐/封面/歌词、视觉效果和操作顺序。保留动效，不降低画质。每个主题分别采集冷启动和交互，至少各重复 3 次，交替执行两种主题以减少缓存和热状态的顺序偏差。先核对元数据条件，再比较每轮结果及中位数；不要把不同类型场景混为一个平均值。

冷启动前结束目标包会中断当前播放，应在本轮调试授权允许该操作时单独执行。可使用显式 serial 的 `am force-stop <package>` 后再用 `am start -W -n <activity>` 启动；禁止 `pm clear`。先准备好停止状态，再开始采集，待 `ready` 后启动，确保启动前后都有 trace；使用实际 `startup_type` 核实冷启动。`am start -W` 的主机等待和系统报告时间须与 Perfetto 启动耗时分开记录。

交互按同一节奏执行同一组页面切换、封面/歌词切换和播放控件动作。记录具体动作、顺序及设备时间或 trace 标记；不要仅凭主机 ADB 命令完成时间声称毫秒级触控延迟。若采集过程降频、后台下载、网络请求或温度条件明显不同，应保留该轮并注明无效原因，重新取得匹配条件的轮次。

## 工具测试

```bash
python3 -B -m unittest discover -s tools_pub/performance -p 'test_*.py'
```

测试使用手工可计算的调度区间、真实 SQLite 查询及 CSV/actual 帧夹具，覆盖跨窗口/跨箱、多核累加、尾箱、丢失/未知数据、进程匹配、多图层去重及显式设备选择。它们验证解析与计算逻辑，不代替真机采集、厂商计数器校验或真实界面验证。

设置 `NERIPLAYER_TRACE_PROCESSOR=/absolute/path/to/trace_processor_shell` 后，同一测试命令还会用官方本地处理器导入自包含的合成调度 trace，检查手算的 150 ms CPU 时间、0.5 平均核数、1.0 峰值核数，并确认请求时长与 trace 不符时输出未知。未指定处理器时只跳过这一可选集成测试。

## 官方依据

- [CPU 调度、线程身份与未闭合区间](https://perfetto.dev/docs/data-sources/cpu-scheduling)
- [GPU 数据源、频率与设备专属计数器](https://perfetto.dev/docs/data-sources/gpu)
- [FrameTimeline 语义](https://perfetto.dev/docs/data-sources/frametimeline)
- [电池与电源轨、充电状态限制](https://perfetto.dev/docs/data-sources/battery-counters)
- [Perfetto CLI 与 background-wait](https://perfetto.dev/docs/reference/perfetto-cli)
- [Trace Processor](https://perfetto.dev/docs/analysis/trace-processor)
- [Trace Processor stats 级别](https://perfetto.dev/docs/analysis/sql-stats)
- [缓冲区、流式写入与丢失检测](https://perfetto.dev/docs/concepts/buffers)
- [本地处理器版本对应的 stats 定义](https://github.com/google/perfetto/blob/add693d8b338ba9599dbcbc3e300b1ab8c000897/src/trace_processor/storage/stats.h)
- [readaheads_failed 的官方字段说明](https://github.com/google/perfetto/blob/add693d8b338ba9599dbcbc3e300b1ab8c000897/protos/perfetto/common/trace_stats.proto)
