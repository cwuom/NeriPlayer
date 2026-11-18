# Native / 原生运行库

[English](#english) | [中文](#中文)

## 中文

`:native` 负责 Native 崩溃处理、音效 DSP 和 USB 独占音频的 C/C++ 实现，通过 Android library AAR 向 app 提供 `lib_neri.so`。它没有 Kotlin/Java 生产源码，也不依赖其他项目模块。

### 职责与依赖

源码位于 [src/main/cpp](src/main/cpp)。CMake 目标分工：

- `neri_usb_core`：可移植的 USB 协议、反馈和 PCM 计算
- `neri_usb_android`：Android 传输与 JNI
- `neri_dsp_core`：可移植的音效 DSP
- `neri_dsp_android`：音效 DSP 的 JNI
- `neri_crash`：崩溃处理
- `neri_libusb`：第三方库
- `neri_native`：组装以上目标，输出 `lib_neri.so`

目录职责：

| 路径 | 职责 |
| --- | --- |
| `crash/` | Native 崩溃处理 |
| `dsp/` | 零延迟 float 音效引擎、参数表与 JNI |
| `usb/uac1/`、`usb/uac2/` | 各协议的格式、描述符和候选模型 |
| `usb/feedback/`、`usb/iso/` | 反馈时钟、速率估计、包调度和传输健康规则 |
| `usb/pcm/` | PCM 编解码、缓冲和增益处理 |
| `usb/control/` | Feature Unit 静音/音量拓扑与采样率回读判定 |
| `usb/exclusive/` | JNI 入口、会话所有权、设备选择、传输和恢复 |
| `third_party/libusb/` | libusb 及其 Android 构建配置 |
| `tests/usb/` | 可移植 USB 模型的 host 测试与门禁配置 |
| `tests/dsp/` | 音效 DSP 的 host 测试 |
| `cmake/`、各级 `CMakeLists.txt` | 自有目标的编译规则、目标拆分和构建适配 |

边界与兼容：

- 会话的 Kotlin 接入和播放策略属于 [`:playback:runtime`](../playback/runtime/README.md)
- Native 源码不读取播放器、Room 或账号仓库
- JNI 类全名、方法签名与 `_neri` 加载名必须保持兼容
- 第三方源码沿用原许可证
- 自有源码的替代授权范围见 [Native 源码授权说明](src/main/cpp/README.md)

### 构建与验证

在仓库根目录运行：

- Debug 构建输出四个 ABI 的 `lib_neri.so`
- 独立的 Native CI 检查每个产物非空
- app 通过 `:native` AAR 打包它
- 模块不生成 JaCoCo 数据，也不运行 JVM 测试
- Kotlin/Java 模块使用各自的 JVM、CRAP 和域依赖门禁

```bash
./gradlew :native:externalNativeBuildDebug :native:lintDebug --warning-mode all
./gradlew :app:assembleDebug

for profile in release-werror-asserts asan-ubsan tsan; do
  tools_pub/usb-async-lab host-test \
    --manifest modules/native/src/main/cpp/tests/usb/config/run-manifest.example.yaml \
    --profile "$profile"
done

for sanitizer in none asan-ubsan; do
  cmake -S modules/native/src/main/cpp/tests/dsp -B "build/dsp-host-$sanitizer" \
    -DCMAKE_BUILD_TYPE=Release -DDSP_TEST_SANITIZER="$sanitizer"
  cmake --build "build/dsp-host-$sanitizer"
  ctest --test-dir "build/dsp-host-$sanitizer" --output-on-failure
done
```

#### USB host gate

- 使用 Ninja，每次运行创建独立构建目录
- 检查断言、编译警告、测试结果、源码指纹及本次运行产生的资源
- 三组 profile 使用同一组测试，详见 [host gate contract](src/main/cpp/tests/usb/config/host-gate-contract.md)
- 运行 32 个 Native 测试及 5 个 runner 自检/失败关闭检查，每项超时 60 秒
- host 模型测试和 ABI 编译不能证明真实 DAC 的播放、断连或恢复行为，这些需单独验证

#### DSP host 测试

- 由独立的 CMake 工程运行，超时 120 秒
- Native CI 目前自动执行 USB 三组 profile 和 Android ABI 编译
- 上面的 DSP 命令需要单独执行

## English

`:native` owns native crash handling, the audio effects DSP, and the C/C++ implementation of USB exclusive audio. Its Android library AAR supplies `lib_neri.so` to app. It has no Kotlin/Java production sources and no project-module dependencies.

### Responsibilities and dependencies

Sources are in [src/main/cpp](src/main/cpp). CMake target roles:

- `neri_usb_core`: portable USB protocol, feedback, and PCM calculations
- `neri_usb_android`: Android transport and JNI
- `neri_dsp_core`: portable audio effects DSP
- `neri_dsp_android`: JNI for the audio effects DSP
- `neri_crash`: crash handling
- `neri_libusb`: third-party library
- `neri_native`: assembles these targets and produces `lib_neri.so`

Directory roles:

| Path | Responsibility |
| --- | --- |
| `crash/` | Native crash handling |
| `dsp/` | Zero-latency float effects engine, parameter table, and JNI |
| `usb/uac1/`, `usb/uac2/` | Protocol-specific formats, descriptors, and candidate models |
| `usb/feedback/`, `usb/iso/` | Feedback clocks, rate estimation, packet scheduling, and transfer health rules |
| `usb/pcm/` | PCM codecs, buffering, and gain processing |
| `usb/control/` | Feature Unit mute/volume topology and sample-rate readback rules |
| `usb/exclusive/` | JNI entry points, session ownership, device selection, transport, and recovery |
| `third_party/libusb/` | libusb and its Android build configuration |
| `tests/usb/` | Host tests and gate configuration for portable USB models |
| `tests/dsp/` | Host tests for the audio effects DSP |
| `cmake/`, each `CMakeLists.txt` | Compile rules, target composition, and build adapters for owned code |

Boundaries and compatibility:

- Kotlin session integration and playback policies belong to [`:playback:runtime`](../playback/runtime/README.md)
- Native code does not read player, Room, or account repositories
- JNI class names, method signatures, and the `_neri` library name must stay compatible
- Third-party sources keep their original licenses
- The alternative license scope for owned sources is defined in the [native license document](src/main/cpp/README.md)

### Build and verification

Run from the repository root:

- Debug builds produce `lib_neri.so` for all four ABIs
- The independent native CI checks that each output is non-empty
- app packages them through the `:native` AAR
- The module produces no JaCoCo execution data and runs no JVM tests
- Kotlin/Java modules use their own JVM, CRAP, and domain dependency gates

```bash
./gradlew :native:externalNativeBuildDebug :native:lintDebug --warning-mode all
./gradlew :app:assembleDebug

for profile in release-werror-asserts asan-ubsan tsan; do
  tools_pub/usb-async-lab host-test \
    --manifest modules/native/src/main/cpp/tests/usb/config/run-manifest.example.yaml \
    --profile "$profile"
done

for sanitizer in none asan-ubsan; do
  cmake -S modules/native/src/main/cpp/tests/dsp -B "build/dsp-host-$sanitizer" \
    -DCMAKE_BUILD_TYPE=Release -DDSP_TEST_SANITIZER="$sanitizer"
  cmake --build "build/dsp-host-$sanitizer"
  ctest --test-dir "build/dsp-host-$sanitizer" --output-on-failure
done
```

#### USB host gate

- Uses Ninja and a fresh build directory for each run
- Checks assertions, compiler warnings, test results, source fingerprints, and resources created by the run
- All three profiles use the same test inventory, see the [host gate contract](src/main/cpp/tests/usb/config/host-gate-contract.md)
- Runs 32 native tests and 5 runner self-check/fail-closed checks, each with a 60-second timeout
- Host model tests and ABI compilation do not establish real-DAC playback, disconnect, or recovery behavior, which needs separate validation

#### DSP host tests

- Run from a separate CMake project with a 120-second timeout
- Native CI currently runs the three USB profiles and Android ABI compilation
- Run the DSP commands above separately
