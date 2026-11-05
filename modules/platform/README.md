# 平台 / Platforms

`:platform` 集中管理 Bilibili、网易云音乐、YouTube Music、Subsonic 音乐服务器，以及评论、歌曲百科、远程歌词和元数据搜索。歌词解析和 Lyricon 接入在 `:lyrics`。

`:platform` contains Bilibili, NetEase Cloud Music, YouTube Music, and Subsonic server integrations, plus comments, song encyclopedias, remote lyrics, and metadata search. Lyric parsing and Lyricon integration belong to `:lyrics`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/platform`。

Sources are in `src/main/java/moe/ouom/neriplayer/platform`.

- `bilibili`、`netease`、`youtube`：各平台的请求、账号、缓存和播放来源 / platform requests, accounts, caches, and playback sources
- `subsonic`：Navidrome / OpenSubsonic 的协议、独立加密账号、曲库映射、浏览/歌词缓存及请求时资源认证 / server protocol, encrypted accounts, library mapping, browse/lyrics caches, and request-time media authentication
- `lyrics`、`search`：歌词检索、匹配与元数据搜索 / lyric lookup, matching, and metadata search
- `comments`：评论分页、映射与缓存 / comment pagination, mapping, and caching
- `netease/encyclopedia`：歌曲百科请求、解析与内存缓存 / song encyclopedia requests, parsing, and memory cache

各平台内部的 `api` 负责请求协议，账号和缓存由各自账号/仓库层管理。SubsonicClient 使用传入的配置和密码；SubsonicResourceInterceptor 通过宿主注入的账号提供者，在资源请求时读取当前配置并认证。Room 实体与 DAO 在 `:database`，跨平台本地歌单在 `:local`。本库还依赖 `:model`、`:lyrics`、`:common` 和 `:network`。

Each platform's `api` package handles protocols; account and repository layers manage credentials and caches. SubsonicClient receives a profile and password. SubsonicResourceInterceptor uses a host-injected account provider to authenticate resource requests with the current profile. Room entities and DAOs live in `:database`, and cross-platform local playlists in `:local`. Other dependencies are `:model`, `:lyrics`, `:common`, and `:network`.

歌曲百科通过 `SongEncyclopediaRepository` 返回成功、不支持、无资料和可重试失败四种结果；当前实现仅查询具有网易云来源身份的歌曲，不将缺失资料当作网络错误，也不为其它来源推测百科内容。

`SongEncyclopediaRepository` distinguishes success, unsupported sources, unavailable data, and retryable failures. The current implementation queries songs with a NetEase source identity, keeps missing data separate from network errors, and does not infer encyclopedia content for other sources.

YouTube 脚本在 `src/main/assets/youtube`；`youtube/api/challenge` 管理 EJS 挑战、播放器脚本缓存、共享 JavaScriptSandbox 与本地 WebView 回退。NewPipe 所需的 Rhino 反射入口由 `consumer-rules.pro` 保留。凭据文件、键名和序列化名称需兼容已安装版本。

YouTube scripts are in `src/main/assets/youtube`. `youtube/api/challenge` owns EJS challenges, player script caching, the shared JavaScriptSandbox, and the local WebView fallback. `consumer-rules.pro` preserves Rhino reflection entry points needed by NewPipe. Credential files, keys, and serialized names must remain compatible with installed versions.

## 测试 / Tests

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
./gradlew :platform:testDebugUnitTest --tests "*Subsonic*"
```

## 音乐服务器接入 / Music server integration

> [!WARNING]
> 自建音乐服务器功能目前为预览版本，缺乏充分测试。不同服务器、曲库和网络环境下的兼容性与稳定性仍需验证。
>
> Self-hosted music server support is a preview feature and has not been sufficiently tested. Compatibility and stability across servers, libraries, and network conditions still need validation.

沿用现有 `SongItem`、`SongUrlResult`、`LyricEntry` 与 playback host；账号、仓库和共享 HTTP 由 app 宿主注入。请求参数、身份和缓存语义见 [API 契约](../../docs/music-server-api.md)。

The integration reuses the existing song, playback URL, lyric, and playback host models. The app injects accounts, the repository, and shared HTTP. See the [API contract](../../docs/music-server-api.md) for request, identity, and cache semantics (Chinese).

| 核心文件 / Core files | 职责 / Responsibility |
| --- | --- |
| [SubsonicClient](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicClient.kt) | REST 参数、认证和 JSON 响应 / REST parameters, authentication, and JSON responses |
| [SubsonicResourceInterceptor](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicResourceInterceptor.kt) | 内部资源引用转换为当次认证的音频/图片请求 / Resolve internal references into authenticated media requests |
| [SubsonicAccounts](src/main/java/moe/ouom/neriplayer/platform/subsonic/auth/SubsonicAccounts.kt) | 加密配置、账号状态和修订检查 / Encrypted profiles, account state, and revision checks |
| [SubsonicRepository](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt) | 连接检查、曲库查询及歌曲/歌词映射 / Connection checks, library queries, and song/lyric mapping |
| [SubsonicBrowseCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseCache.kt)、[SubsonicBrowseRoomStore](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseRoomStore.kt)、[SubsonicCatalog](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicCatalog.kt) | 专辑、单曲与歌手完整目录、请求复用和 Room 快照 / Complete album, song, and artist directories, shared requests, and Room snapshots |
| [SubsonicLyricsCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLyricsCache.kt)、[SubsonicAudioMetadata](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicAudioMetadata.kt) | 成功歌词缓存和服务器音频信息 / Successful lyric cache and server-declared audio metadata |
| [ServerSongRef](../model/src/main/java/moe/ouom/neriplayer/data/model/server/ServerSongRef.kt)（`:model`） | 配置 UUID + 原始字符串 ID 的可逆来源引用 / Reversible source reference using profile UUID and raw string ID |
