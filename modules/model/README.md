[English](#english) | [中文](#中文)

# 数据模型 / Models

## 中文

`:model` 定义跨模块的歌曲、歌单、播放与下载状态、音效、歌曲百科、平台响应、歌词、设置、存储统计、同步和一起听协议模型，不依赖其它项目实现。

### 结构

- 主要源码：`src/main/java/moe/ouom/neriplayer/data/model`，按业务分包
- 少量共享类型位于 `data/sync/model` 和 `ui/viewmodel`

### 兼容性

- 字段名、默认值和 Protobuf 字段编号须兼容旧缓存、备份和同步快照
- 部分类型支持 Parcelable
- `consumer-rules.pro` 保留历史 Bilibili 缓存和播放状态的 Gson 字段

### DSP 参数

- `playback/effects/NeriDspParams.kt` 的参数数量、下标和枚举值须与 `:native` 的 `dsp/neri_dsp_params.h` 同步
- `AudioEffectsSettings` 的归一化和路由选择决定是否启用 DSP
- 音效总开关关闭时，外放优化同样旁路
- USB bit-perfect 模式始终旁路 DSP

### 测试

```bash
./gradlew :model:testDebugUnitTest :model:lintDebug
```

## English

`:model` defines shared models for songs, playlists, playback and downloads, audio effects, song encyclopedias, platform responses, lyrics, settings, storage statistics, sync, and Listen Together. It has no project implementation dependencies.

### Layout

- Main sources: `src/main/java/moe/ouom/neriplayer/data/model`, grouped by business area
- A few shared types live in `data/sync/model` and `ui/viewmodel`

### Compatibility

- Field names, defaults, and Protobuf field numbers must stay compatible with old caches, backups, and sync snapshots
- Some types support Parcelable
- `consumer-rules.pro` keeps Gson fields for historical Bilibili caches and playback state

### DSP parameters

- The parameter count, indexes, and enum values in `playback/effects/NeriDspParams.kt` must match `dsp/neri_dsp_params.h` in `:native`
- `AudioEffectsSettings` normalization and route selection decide whether DSP is active
- Speaker optimization is also bypassed when the effects master switch is off
- USB bit-perfect mode always bypasses DSP

### Tests

```bash
./gradlew :model:testDebugUnitTest :model:lintDebug
```
