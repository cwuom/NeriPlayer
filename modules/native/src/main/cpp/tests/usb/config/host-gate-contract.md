# Native USB Host Gate Contract

This public contract defines the reproducible boundary for host-side USB models. It contains no device-derived descriptors, identifiers, logs, or feedback traces. Real-device qualification requires evidence in the ignored private evidence tree.

If this contract changes, update `planSha256` in `run-manifest.example.yaml` to its SHA-256.

## Host Gate & CI Profiles

| Check Area | Requirement |
| --- | --- |
| Host CTest Profiles | `.github/workflows/android_native_ci.yml` runs `release-werror-asserts` (Release + `-Werror`), `asan-ubsan`, and `tsan` on native changes; all three run the same CTest inventory with assertions enabled |
| Source-Declared CTest Inventory | 37 CTest entries declared in source: 32 native C++ host model tests (using environment seed `324508639`), 1 runner self-check, and 4 fail-closed fixtures covering warning, source-fingerprint, port, and daemon failures; every entry declares a 60-second timeout |
| Compiler Warnings | Compiler warnings fail the gate unless matched by an exact environment-owned baseline entry |
| Runner Integrity | Verifies a stable source fingerprint across the entire attempt and performs resource auditing |
| Android ABI Compilation | Separate CI job verifying non-empty `lib_neri.so` outputs for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` |

## Streaming Synchronization Model Policy

The host regression matrix checks transport-setup admission across USB Audio Class synchronization modes:

| Class | Route / Synchronization Mode | Pre-Transport Model Policy |
| --- | --- | --- |
| UAC1 | Adaptive, Synchronous | Accepted |
| UAC1 | Explicit-feedback Asynchronous | Accepted; requires an explicit-feedback profile |
| UAC1 | Implicit-feedback or No-feedback Asynchronous | Rejected (fail-closed) |
| UAC2 | Adaptive, Synchronous | Accepted |
| UAC2 | Asynchronous, with explicit feedback or no feedback declared | Accepted by the policy; requires an explicit-feedback profile before transport setup |
| UAC2 | Implicit-feedback Asynchronous | Rejected (fail-closed) |
