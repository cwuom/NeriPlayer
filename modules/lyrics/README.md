# 🎼 歌词解析与词幕输出

`:lyrics` 维护 LRC、YRC、TTML 解析与转换、时间轴、逐字信息、翻译对齐、嵌入歌词兼容和共用时间偏移计算，以及 Lyricon/SuperLyric 词幕输出。远程歌词客户端、来源仓库、检索、匹配与回退归 `:platform`。

解析实现按 `core/lyrics` 的职责组织。共用偏移计算保留 `data/settings/lyrics` 包名，嵌入歌词兼容保留 `util/media` 包名；设置读取和持久化由 `:local` 维护。数据契约来自 `:model`，通用辅助来自 `:common`，上游解析能力来自 `:accompanist-lyrics-core`。

SDK 接入与位置锚点位于 `core/lyricon`。`lyrics/integration/LyriconPlaybackOutput` 是输出入口，直接维护歌曲和偏好快照、歌词加载与偏移发布；异步请求代次和取消由内部协调器管理。协调器保留 `core/player/lyrics` 包名和内部可见性。位置推送使用独立的 200 ms feed 与 elapsed realtime 锚点，不能随播放器 UI 刷新节奏改变前后台时序。

本库不依赖平台、账号仓库或播放器实现。播放器通过 `LyriconLyricsLoader` 窄端口提供歌词，并输入偏好、歌曲、播放状态、进度与生命周期快照；异步输出不读取播放器私有状态。解析组件保持与 SDK 输出分开的职责和窄依赖，解析模型变化需同时检查逐字、翻译与既有消费者。

词幕 SDK、Manifest 元数据、标签资源与 consumer R8 规则随本库维护。SDK 接入只能通过公开门面消费；输出内部状态、协调器和 sink 不作为跨模块调用接口。

## 🧪 验证

```bash
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :lyrics:testDebugUnitTest --tests "*Lyricon*"
python3 -B tools_pub/quality/module_boundaries.py
```

解析和输出测试随实现维护，`lyrics/integration/**/*.kt` 整文件进入 CRAP 范围，原播放入口方法的检查继续保留。JVM 测试验证取消、代次、身份、加载端口与偏移快照；第三方词幕服务的实际展示需要独立 Android 集成验证。

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
