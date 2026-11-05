# 通用工具 / Common

`:common` 提供日志、集合与 JSON 辅助、搜索匹配、时间与单位转换、请求代次、协程取消保护和文件原子写入，以及共用文案、图标和语言切换。

`:common` provides logging, collection and JSON helpers, search matching, time and unit conversions, request generations, cancellation handling, and atomic file writes, plus shared strings, icons, and locale support.

源码在 `src/main/java/moe/ouom/neriplayer/common`，资源在 `src/main/res`。模块不依赖其它项目实现；共享业务模型由 `:model` 维护。`src/testFixtures` 供播放和下载运行库复用。

Sources are in `src/main/java/moe/ouom/neriplayer/common`, with resources in `src/main/res`. The module has no project implementation dependencies; shared business models belong to `:model`. Playback and download runtime libraries reuse `src/testFixtures`.

app 独占的界面文案放在 `app/src/main/res`；仍被库模块消费的文案组保留在 `:common`，避免库反向依赖 app。默认 `values` 与 `values-zh` 以中文为基准，`values-en` 提供英文；`LocalizedResourceConsistencyTest` 在现有 app 单元测试中检查资源键、默认文案和格式参数一致性。

UI text used only by app belongs in `app/src/main/res`. Resource groups still consumed by libraries stay in `:common` so libraries do not depend on app. Default `values` and `values-zh` use Chinese, while `values-en` supplies English. `LocalizedResourceConsistencyTest` runs with the existing app unit tests to check resource keys, default text, and format argument compatibility.

## 账号凭据 / Account credentials

| 场景 | 行为与限制 |
| --- | --- |
| 凭据入口 | 账号凭据使用 `AccountPreferencesOpener`；同步凭据沿用 `SecurePreferencesOpener` 恢复策略 |
| 兼容存储加密 | 系统加密不可用时采用随机本地密钥 + AES-GCM；密钥与密文均存于 `noBackupFilesDir`，无 Keystore 硬件保护 |
| 读取优先级 | 已有兼容文件（含退出后的空状态）优先读取，防止恢复旧登录状态 |
| 不可持久化降级 | 无法落盘时回退至内存存储，登录入口据此拦截登录 |

| Area | Behavior and Limits |
| --- | --- |
| Credential Openers | Account credentials use `AccountPreferencesOpener`; sync credentials keep the `SecurePreferencesOpener` recovery policy |
| Compatibility Encryption | Falls back to AES-GCM with a random local key when system encryption is unavailable; both key and ciphertext stay in `noBackupFilesDir` without Keystore hardware protection |
| Read Precedence | Existing compatibility files, including empty signed-out states, take precedence to prevent restoring stale logins |
| Non-Persistent Fallback | Returns in-memory storage when persistence fails, blocking sign-in at the entry point |

| 机制 | 行为 |
| --- | --- |
| 延迟建档 | 首次实际提交时才创建正式兼容文件；打开失败或取消登录不会留下空文件遮挡恢复后的系统存储 |
| 旧 Cookie 迁移标记 | 仅新建的兼容空存储携带待迁移标记（`LEGACY_MIGRATION_PENDING`）并允许读取旧版 Cookie；保存或退出在同一提交中移除该标记，历史空退出状态保持有效 |
| 中断恢复与损坏拦截 | 首次创建使用独立初始化标记，中断后可沿用原密钥重试；密文损坏、缺失密钥或无初始化标记的残缺文件均原样保留并拒绝打开 |

| Mechanism | Behavior |
| --- | --- |
| Deferred File Creation | Formal compatibility files are created only on the first actual commit; open failures or cancelled sign-ins never leave empty files masking recovered system storage |
| Legacy Cookie Migration | Only newly initialized empty compatibility stores carry the `LEGACY_MIGRATION_PENDING` marker to read legacy cookies; saving or signing out removes it in the same commit while preserving existing empty logout states |
| Interrupted Init & Corruption | A dedicated initialization marker allows interrupted creation to retry with the original key; damaged ciphertext, missing keys, or incomplete files without the marker are preserved and rejected |

## 测试 / Tests

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```
