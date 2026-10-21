# 平台 / Platforms

`:platform` 集中管理 Bilibili、网易云音乐、YouTube Music，以及评论、歌曲百科、远程歌词和元数据搜索。歌词解析和 Lyricon 接入在 `:lyrics`。

`:platform` contains Bilibili, NetEase Cloud Music, and YouTube Music integrations, plus comments, song encyclopedias, remote lyrics, and metadata search. Lyric parsing and Lyricon integration belong to `:lyrics`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/platform`。

Sources are in `src/main/java/moe/ouom/neriplayer/platform`.

- `bilibili`、`netease`、`youtube`：各平台的请求、账号、缓存和播放来源 / platform requests, accounts, caches, and playback sources
- `lyrics`、`search`：歌词检索、匹配与元数据搜索 / lyric lookup, matching, and metadata search
- `comments`：评论分页、映射与缓存 / comment pagination, mapping, and caching
- `netease/encyclopedia`：歌曲百科请求、解析与内存缓存 / song encyclopedia requests, parsing, and memory cache

各平台内部的 `api` 负责请求协议，通过注入接口获取凭据；账号和缓存由仓库管理。Room 实体与 DAO 在 `:database`，跨平台本地歌单在 `:local`。本库还依赖 `:model`、`:lyrics`、`:common` 和 `:network`。

Each platform's `api` package handles protocols with injected credentials; repositories manage accounts and caches. Room entities and DAOs live in `:database`, and cross-platform local playlists in `:local`. Other dependencies are `:model`, `:lyrics`, `:common`, and `:network`.

歌曲百科通过 `SongEncyclopediaRepository` 返回成功、不支持、无资料和可重试失败四种结果；当前实现仅查询具有网易云来源身份的歌曲，不将缺失资料当作网络错误，也不为其它来源推测百科内容。

`SongEncyclopediaRepository` distinguishes success, unsupported sources, unavailable data, and retryable failures. The current implementation queries songs with a NetEase source identity, keeps missing data separate from network errors, and does not infer encyclopedia content for other sources.

YouTube 脚本在 `src/main/assets/youtube`；`youtube/api/challenge` 管理 EJS 挑战、播放器脚本缓存、共享 JavaScriptSandbox 与本地 WebView 回退。NewPipe 所需的 Rhino 反射入口由 `consumer-rules.pro` 保留。凭据文件、键名和序列化名称需兼容已安装版本。

YouTube scripts are in `src/main/assets/youtube`. `youtube/api/challenge` owns EJS challenges, player script caching, the shared JavaScriptSandbox, and the local WebView fallback. `consumer-rules.pro` preserves Rhino reflection entry points needed by NewPipe. Credential files, keys, and serialized names must remain compatible with installed versions.

## 测试 / Tests

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
```
