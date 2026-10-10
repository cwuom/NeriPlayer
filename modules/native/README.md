# Native / 原生运行库

`:native` 负责 Native 崩溃处理、音效 DSP 和 USB 独占音频的 C/C++ 实现，通过 Android library AAR 向 app 提供 `lib_neri.so`。它没有 Kotlin/Java 生产源码，也不依赖其他项目模块。

`:native` owns native crash handling, the audio effects DSP, and the C/C++ implementation of USB exclusive audio. Its Android library AAR supplies `lib_neri.so` to app. It has no Kotlin/Java production sources or project-module dependencies.

## 职责与依赖 / Responsibilities and dependencies

源码位于 [src/main/cpp](src/main/cpp)。CMake 以 `neri_usb_core` 维护可移植 USB 协议、反馈和 PCM 计算，`neri_usb_android` 维护 Android 传输与 JNI，`neri_dsp_core` 维护可移植音效 DSP，`neri_dsp_android` 维护其 JNI，`neri_crash` 维护崩溃处理，`neri_libusb` 维护第三方库。`neri_native` 组装这些目标并输出 `lib_neri.so`。

Sources are in [src/main/cpp](src/main/cpp). CMake assigns portable USB protocol, feedback, and PCM calculations to `neri_usb_core`, Android transport and JNI to `neri_usb_android`, the portable audio effects DSP to `neri_dsp_core`, its JNI to `neri_dsp_android`, crash handling to `neri_crash`, and third-party code to `neri_libusb`. `neri_native` assembles these targets and produces `lib_neri.so`.

| 路径 / Path | 职责 / Responsibility |
| --- | --- |
| `crash/` | Native 崩溃处理 / Native crash handling |
| `dsp/` | 零延迟 float 音效引擎、参数表与 JNI / Zero-latency float audio effects engine, parameter table, and JNI |
| `usb/uac1/`, `usb/uac2/` | 各协议的格式、描述符和候选模型 / Protocol-specific formats, descriptors, and candidate models |
| `usb/feedback/`, `usb/iso/` | 反馈时钟、速率估计、包调度和传输健康规则 / Feedback clocks, rate estimation, packet scheduling, and transfer health rules |
| `usb/pcm/` | PCM 编解码、缓冲和增益处理 / PCM codecs, buffering, and gain processing |
| `usb/control/` | Feature Unit 静音/音量拓扑与采样率回读判定 / Feature Unit mute/volume topology and sample-rate readback rules |
| `usb/exclusive/` | JNI 入口、会话所有权、设备选择、传输和恢复 / JNI entry points, session ownership, device selection, transport, and recovery |
| `third_party/libusb/` | libusb 及其 Android 构建配置 / libusb and its Android build configuration |
| `tests/usb/` | 可移植 USB 模型的 host 测试与门禁配置 / Host tests and gate configuration for portable USB models |
| `tests/dsp/` | 音效 DSP 的 host 测试 / Host tests for the audio effects DSP |

会话的 Kotlin 接入和播放策略属于 [`:playback:runtime`](../playback/runtime/README.md)；Native 源码不反向读取播放器、Room 或账号仓库。JNI 类全名、方法签名与 `_neri` 加载名必须保持兼容。第三方源码保留原许可证；自有源码的替代授权范围见 [Native 源码授权说明](src/main/cpp/README.md)。

Kotlin session integration and playback policies belong to [`:playback:runtime`](../playback/runtime/README.md). Native code does not read player, Room, or account repositories. JNI class names, method signatures, and the `_neri` library name must remain compatible. Third-party sources retain their original licenses; the alternative license for owned sources is defined in the [native license document](src/main/cpp/README.md).

## 构建与验证 / Build and verification

在仓库根目录运行。Debug 构建输出四个 ABI 的 `lib_neri.so`，独立 Native CI 检查每个产物非空；app 通过 `:native` AAR 打包它。模块不生成 JaCoCo 数据或运行 JVM 测试，原有 Kotlin/Java 模块继续使用各自的 JVM、CRAP 和域依赖门禁。

Run from the repository root. Debug builds produce `lib_neri.so` for all four ABIs, and the independent native CI checks that each output is non-empty; app packages them through the `:native` AAR. This module produces no JaCoCo execution data or JVM tests. Existing Kotlin/Java modules retain their JVM, CRAP, and domain dependency gates.

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

Host gate 使用 Ninja，每次创建独立构建目录，并检查断言、编译警告、测试结果、源码指纹及本次运行产生的资源。三组 profile 使用同一组测试，详见 [host gate contract](src/main/cpp/tests/usb/config/host-gate-contract.md)。host 模型测试和 ABI 编译不能证明真实 DAC 的播放、断连或恢复行为；这些仍需单独验证。

The host gate uses Ninja and a fresh build directory for each attempt. It checks assertions, compiler warnings, test results, source fingerprints, and resources created by the attempt. All three profiles use the same inventory; see the [host gate contract](src/main/cpp/tests/usb/config/host-gate-contract.md). Host model tests and ABI compilation do not establish real-DAC playback, disconnect, or recovery behavior; those require separate validation.
