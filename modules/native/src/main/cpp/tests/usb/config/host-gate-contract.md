# Native USB Host Gate Contract

[English](#english) | [中文](#中文)

## 中文

本公开契约定义 host 侧 USB 模型的可复现边界，不含任何来自设备的描述符、标识、日志或反馈轨迹。真实设备资质认定需要被忽略的私有证据树中的证据。

契约变更后，须把 `run-manifest.example.yaml` 中的 `planSha256` 更新为本文件的 SHA-256。

### Host Gate 与 CI Profile

#### Host CTest Profile

- `.github/workflows/android_native_ci.yml` 在 native 变更时运行三个 profile
- `release-werror-asserts`：Release + `-Werror`
- `asan-ubsan`
- `tsan`
- 三个 profile 运行同一份 CTest 清单，且均启用断言

#### 源码声明的 CTest 清单

- 源码共声明 37 个 CTest 条目
- 32 个 native C++ host 模型测试，使用环境种子 `324508639`
- 1 个 runner 自检
- 4 个失败关闭夹具，覆盖警告、源码指纹、端口和 daemon 失败
- 每个条目声明 60 秒超时

#### 编译警告

- 编译警告使会话失败
- 例外：与环境所有的精确基线条目匹配

#### Runner 完整性

- 在整个运行期间验证稳定的源码指纹
- 执行资源审计

#### Android ABI 编译

- 独立的 CI 任务
- 验证 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 的 `lib_neri.so` 均非空

### 流式同步模型策略

host 回归矩阵检查各 USB Audio Class 同步模式下的传输建立准入：

| 类别 | 路由 / 同步模式 | 传输前模型策略 |
| --- | --- | --- |
| UAC1 | Adaptive、Synchronous | 接受 |
| UAC1 | 显式反馈 Asynchronous | 接受，须有显式反馈 profile |
| UAC1 | 隐式反馈或无反馈 Asynchronous | 拒绝（失败关闭） |
| UAC2 | Adaptive、Synchronous | 接受 |
| UAC2 | 声明显式反馈或无反馈的 Asynchronous | 接受，传输建立前须有显式反馈 profile |
| UAC2 | 隐式反馈 Asynchronous | 拒绝（失败关闭） |

## English

This public contract defines the reproducible boundary for host-side USB models. It contains no device-derived descriptors, identifiers, logs, or feedback traces. Real-device qualification requires evidence in the ignored private evidence tree.

If this contract changes, update `planSha256` in `run-manifest.example.yaml` to its SHA-256.

### Host Gate and CI Profiles

#### Host CTest profiles

- `.github/workflows/android_native_ci.yml` runs three profiles on native changes
- `release-werror-asserts`: Release + `-Werror`
- `asan-ubsan`
- `tsan`
- All three run the same CTest inventory with assertions enabled

#### Source-declared CTest inventory

- Source declares 37 CTest entries
- 32 native C++ host model tests, using environment seed `324508639`
- 1 runner self-check
- 4 fail-closed fixtures covering warning, source-fingerprint, port, and daemon failures
- Every entry declares a 60-second timeout

#### Compiler warnings

- Compiler warnings fail the gate
- Exception: a match with an exact environment-owned baseline entry

#### Runner integrity

- Verifies a stable source fingerprint across the entire attempt
- Performs resource auditing

#### Android ABI compilation

- A separate CI job
- Verifies non-empty `lib_neri.so` outputs for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`

### Streaming Synchronization Model Policy

The host regression matrix checks transport-setup admission across USB Audio Class synchronization modes:

| Class | Route / Synchronization Mode | Pre-Transport Model Policy |
| --- | --- | --- |
| UAC1 | Adaptive, Synchronous | Accepted |
| UAC1 | Explicit-feedback Asynchronous | Accepted, needs an explicit-feedback profile |
| UAC1 | Implicit-feedback or No-feedback Asynchronous | Rejected (fail-closed) |
| UAC2 | Adaptive, Synchronous | Accepted |
| UAC2 | Asynchronous with explicit or no feedback declared | Accepted, needs an explicit-feedback profile before transport setup |
| UAC2 | Implicit-feedback Asynchronous | Rejected (fail-closed) |
