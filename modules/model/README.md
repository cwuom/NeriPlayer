# 数据模型 / Models

`:model` 定义跨模块的歌曲、歌单、播放与下载状态、音效、歌曲百科、平台响应、歌词、设置、存储统计、同步和一起听协议模型，不依赖其它项目实现。

`:model` defines shared models for songs, playlists, playback and downloads, audio effects, song encyclopedias, platform responses, lyrics, settings, storage statistics, sync, and Listen Together. It has no project implementation dependencies.

主要源码在 `src/main/java/moe/ouom/neriplayer/data/model`，按业务分包；少量共享类型位于 `data/sync/model` 和 `ui/viewmodel`。

Most sources are in `src/main/java/moe/ouom/neriplayer/data/model`, grouped by business area. A few shared types live in `data/sync/model` and `ui/viewmodel`.

## 兼容性 / Compatibility

字段名、默认值和 Protobuf 字段编号需兼容旧缓存、备份和同步快照。部分类型支持 Parcelable；历史 Bilibili 缓存和播放状态的 Gson 字段保留规则在 `consumer-rules.pro`。

Field names, defaults, and Protobuf field numbers must remain compatible with older caches, backups, and sync snapshots. Some types support Parcelable. `consumer-rules.pro` preserves Gson fields for historical Bilibili caches and playback state.

`playback/effects/NeriDspParams.kt` 的参数数量、下标和枚举值须与 `:native` 的 `dsp/neri_dsp_params.h` 同步；`AudioEffectsSettings` 的归一化和路由选择决定是否启用 DSP。音效总开关关闭时也旁路外放优化，USB bit-perfect 模式保持旁路。

The parameter count, indexes, and enum values in `playback/effects/NeriDspParams.kt` must match `:native`'s `dsp/neri_dsp_params.h`. `AudioEffectsSettings` normalization and route selection determine whether DSP is active. Disabling the effects master switch also bypasses speaker optimization, and USB bit-perfect mode remains bypassed.

## 测试 / Tests

```bash
./gradlew :model:testDebugUnitTest :model:lintDebug
```
