[English](#english) | [中文](#中文)

# 通用工具 / Common

## 中文

`:common` 提供日志、集合与 JSON 辅助、搜索匹配、时间与单位转换、请求代次、协程取消保护和文件原子写入，以及共用文案、图标和语言切换。

### 结构

- 源码：`src/main/java/moe/ouom/neriplayer/common`
- 资源：`src/main/res`
- 依赖：不依赖其它项目实现，共享业务模型由 `:model` 维护
- 测试夹具：`src/testFixtures` 供播放和下载运行库复用

### 界面文案

- app 独占的文案放在 `app/src/main/res`
- 仍被库模块使用的文案组放在 `:common`，避免库反向依赖 app
- 默认 `values` 与 `values-zh` 以中文为基准，`values-en` 提供英文
- `LocalizedResourceConsistencyTest` 随 app 单元测试运行
- 该测试检查资源键、默认文案和格式参数是否一致

### 账号凭据

存储策略：

- 凭据入口：账号凭据使用 `AccountPreferencesOpener`
- 同步凭据使用 `SecurePreferencesOpener` 的恢复策略
- 兼容存储加密：系统加密不可用时采用随机本地密钥 + AES-GCM
- 密钥与密文均存于 `noBackupFilesDir`，无 Keystore 硬件保护
- 读取优先级：已有兼容文件（含退出后的空状态）优先，防止恢复旧登录状态
- 无法落盘时回退至内存存储，登录入口据此拦截登录

文件生命周期：

- 延迟建档：首次实际提交时才创建正式兼容文件
- 打开失败或取消登录不留空文件，避免遮挡恢复后的系统存储
- 旧 Cookie 迁移：仅新建的兼容空存储携带 `LEGACY_MIGRATION_PENDING`，并允许读取旧版 Cookie
- 保存或退出时在同一提交中移除该标记，历史空退出状态有效
- 中断恢复：首次创建使用独立初始化标记，中断后沿用原密钥重试
- 损坏拦截：密文损坏、缺失密钥或无初始化标记的残缺文件原样保留并拒绝打开

### 测试

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```

## English

`:common` provides logging, collection and JSON helpers, search matching, time and unit conversions, request generations, cancellation handling, and atomic file writes, plus shared strings, icons, and locale support.

### Layout

- Sources: `src/main/java/moe/ouom/neriplayer/common`
- Resources: `src/main/res`
- Dependencies: no other project implementations; shared business models belong to `:model`
- Test fixtures: `src/testFixtures`, reused by the playback and download runtime libraries

### UI text

- UI text used only by app lives in `app/src/main/res`
- Resource groups still used by libraries live in `:common`, so libraries do not depend on app
- Default `values` and `values-zh` use Chinese; `values-en` supplies English
- `LocalizedResourceConsistencyTest` runs with the app unit tests
- It checks resource keys, default text, and format arguments for consistency

### Account credentials

Storage policy:

- Credential openers: account credentials use `AccountPreferencesOpener`
- Sync credentials use the `SecurePreferencesOpener` recovery policy
- Compatibility encryption: AES-GCM with a random local key when system encryption is unavailable
- Key and ciphertext are both stored in `noBackupFilesDir`, without Keystore hardware protection
- Read precedence: existing compatibility files, including empty signed-out states, win, so stale logins are not restored
- When persistence fails, storage falls back to memory and the sign-in entry blocks login

File lifecycle:

- Deferred creation: the formal compatibility file is created on the first actual commit
- Failed opens and cancelled sign-ins leave no empty file masking the restored system storage
- Legacy cookie migration: only newly created empty compatibility stores carry `LEGACY_MIGRATION_PENDING` and may read legacy cookies
- Saving or signing out removes the marker in the same commit; existing empty signed-out states stay valid
- Interrupted init: first creation uses a dedicated initialization marker, and a retry reuses the original key
- Corruption guard: damaged ciphertext, missing keys, or incomplete files without the marker are kept as-is and rejected

### Tests

```bash
./gradlew :common:testDebugUnitTest :common:lintDebug
```
