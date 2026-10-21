# 通用工具 / Common

`:common` 提供日志、集合与 JSON 辅助、搜索匹配、时间与单位转换、请求代次、协程取消保护和文件原子写入，以及共用文案、图标和语言切换。

`:common` provides logging, collection and JSON helpers, search matching, time and unit conversions, request generations, cancellation handling, and atomic file writes, plus shared strings, icons, and locale support.

源码在 `src/main/java/moe/ouom/neriplayer/common`，资源在 `src/main/res`。模块不依赖其它项目实现；共享业务模型由 `:model` 维护。`src/testFixtures` 供播放和下载运行库复用。

Sources are in `src/main/java/moe/ouom/neriplayer/common`, with resources in `src/main/res`. The module has no project implementation dependencies; shared business models belong to `:model`. Playback and download runtime libraries reuse `src/testFixtures`.

账号凭据通过 `AccountPreferencesOpener` 打开系统加密存储。系统存储不可用时，兼容存储使用随机本地密钥和 AES-GCM；密钥与密文均位于 `noBackupFilesDir`，不提供 Keystore 硬件保护。兼容文件存在后优先读取，包括退出后的空状态，避免恢复旧登录。无法持久化时返回内存存储，登录入口据此阻止登录。同步凭据仍沿用 `SecurePreferencesOpener` 的原有恢复策略。

Account credentials use `AccountPreferencesOpener`. When system encryption is unavailable, compatibility storage uses AES-GCM with a random local key; both key and ciphertext stay in `noBackupFilesDir`, without Keystore hardware protection. Existing compatibility storage, including an empty logout state, takes precedence over old credentials. Failed persistence falls back to memory so login can be blocked. Sync credentials retain the existing `SecurePreferencesOpener` recovery policy.

兼容存储在第一次实际提交时才创建正式文件，打开失败或取消登录不会留下空文件遮挡恢复后的系统存储。新建兼容存储携带待迁移标记，只有这类空存储允许读取旧版 Cookie。保存或退出在同一次提交中移除标记，已有的空退出状态保持有效。首次创建使用独立的初始化标记，中断后可沿用原密钥重试；已有密文损坏、缺少密钥或无初始化标记的残缺文件仍会被保留并拒绝打开。

Compatibility files are created only on the first actual commit, so opening failures or cancelled sign-ins cannot leave empty files masking recovered system storage. New compatibility storage records pending legacy migration, allowing old cookies to be read only for newly initialized empty storage. Saving or signing out removes the marker in the same commit, preserving existing empty logout states. A separate initialization marker permits interrupted creation to resume with its original key. Damaged ciphertext, missing keys, and incomplete files without an initialization marker remain preserved and rejected.

## 测试 / Tests

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```
