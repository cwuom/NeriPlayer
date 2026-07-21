# Native USB Host Gate Contract

This public contract contains no device-derived descriptors, identifiers, logs,
or feedback traces. It defines the reproducible local boundary for the
feature-off USB host models:

- assertions remain enabled in every profile
- compiler warnings fail the gate unless an exact environment-owned baseline
  entry is documented
- Release, ASan+UBSan, and TSan execute the same CTest inventory
- the runner verifies the source fingerprint is stable for the whole attempt
- `.github/workflows/android_native_ci.yml` executes all three host profiles on
  native changes: Release + `-Werror`, ASan+UBSan, and TSan
- Android ABI compilation remains a separate job from the host model tests and
  verifies non-empty `lib_neri.so` outputs for `arm64-v8a`, `armeabi-v7a`,
  `x86`, and `x86_64`
- the streaming synchronization regression matrix verifies UAC1 adaptive and
  synchronous routes, UAC1 asynchronous and implicit fail-closed behavior,
  UAC2 adaptive and synchronous routes, and UAC2 explicit asynchronous and
  implicit-feedback behavior before transport setup
- real-device qualification remains blocked outside the private evidence tree
