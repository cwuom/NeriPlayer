[English](#english) | [中文](#中文)

# 平台 / Platforms

## 中文

`:platform` 集中管理 Bilibili、网易云音乐、YouTube Music、Subsonic 音乐服务器，以及评论、歌曲百科、远程歌词和元数据搜索。歌词解析和 Lyricon 接入在 `:lyrics`。

### 结构

源码位于 `src/main/java/moe/ouom/neriplayer/platform`：

- `bilibili`、`netease`、`youtube`：各平台的请求、账号、缓存和播放来源
- `subsonic`：Navidrome / OpenSubsonic 的协议、独立加密账号、曲库映射、浏览与歌词缓存，以及请求时资源认证
- `lyrics`、`search`：歌词检索、匹配与元数据搜索
- `comments`：评论分页、映射与缓存
- `netease/encyclopedia`：歌曲百科请求、解析与内存缓存

分层与依赖：

- 各平台的 `api` 包负责请求协议，账号和缓存由账号层与仓库层管理
- `SubsonicClient` 使用传入的配置和密码
- `SubsonicResourceInterceptor` 通过宿主注入的账号提供者，在资源请求时读取当前配置并认证
- Room 实体与 DAO 在 `:database`，跨平台本地歌单在 `:local`
- 其它依赖：`:model`、`:lyrics`、`:common`、`:network`

### 歌曲百科

`SongEncyclopediaRepository` 返回四种结果：成功、不支持、无资料、可重试失败。

- 仅查询具有网易云来源身份的歌曲
- 缺失资料与网络错误分开处理
- 其它来源不推测百科内容

### YouTube

- 脚本位于 `src/main/assets/youtube`
- `youtube/api/challenge` 管理 EJS 挑战、播放器脚本缓存、共享 JavaScriptSandbox 与本地 WebView 回退
- `consumer-rules.pro` 保留 NewPipe 所需的 Rhino 反射入口
- 凭据文件、键名和序列化名称须兼容已安装版本

### 测试

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
./gradlew :platform:testDebugUnitTest --tests "*Subsonic*"
```

<a id="音乐服务器接入--music-server-integration"></a>

### 音乐服务器接入

> [!WARNING]
> 自建音乐服务器功能目前为预览版本，缺乏充分测试。不同服务器、曲库和网络环境下的兼容性与稳定性仍需验证。

- 复用现有 `SongItem`、`SongUrlResult`、`LyricEntry` 与 playback host
- 账号、仓库和共享 HTTP 由 app 宿主注入
- 请求参数、身份和缓存语义见 [API 契约](../../docs/music-server-api.md)

核心文件：

- [SubsonicClient](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicClient.kt)：REST 参数、认证和 JSON 响应
- [SubsonicResourceInterceptor](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicResourceInterceptor.kt)：把内部资源引用转换为当次认证的音频或图片请求
- [SubsonicAccounts](src/main/java/moe/ouom/neriplayer/platform/subsonic/auth/SubsonicAccounts.kt)：加密配置、账号状态和修订检查
- [SubsonicRepository](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt)：连接检查、曲库查询及歌曲与歌词映射
- [SubsonicBrowseCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseCache.kt)、[SubsonicBrowseRoomStore](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseRoomStore.kt)、[SubsonicCatalog](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicCatalog.kt)：专辑、单曲与歌手完整目录、请求复用和 Room 快照
- [SubsonicLyricsCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLyricsCache.kt)、[SubsonicAudioMetadata](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicAudioMetadata.kt)：成功歌词缓存和服务器音频信息
- [ServerSongRef](../model/src/main/java/moe/ouom/neriplayer/data/model/server/ServerSongRef.kt)（`:model`）：配置 UUID + 原始字符串 ID 的可逆来源引用

## English

`:platform` contains Bilibili, NetEase Cloud Music, YouTube Music, and Subsonic server integrations, plus comments, song encyclopedias, remote lyrics, and metadata search. Lyric parsing and Lyricon integration belong to `:lyrics`.

### Layout

Sources are in `src/main/java/moe/ouom/neriplayer/platform`:

- `bilibili`, `netease`, `youtube`: platform requests, accounts, caches, and playback sources
- `subsonic`: Navidrome / OpenSubsonic protocol, encrypted accounts, library mapping, browse and lyrics caches, and request-time media authentication
- `lyrics`, `search`: lyric lookup, matching, and metadata search
- `comments`: comment pagination, mapping, and caching
- `netease/encyclopedia`: song encyclopedia requests, parsing, and memory cache

Layers and dependencies:

- Each platform's `api` package handles protocols; account and repository layers manage credentials and caches
- `SubsonicClient` uses the profile and password it is given
- `SubsonicResourceInterceptor` uses a host-injected account provider to read the current profile and authenticate resource requests
- Room entities and DAOs live in `:database`; cross-platform local playlists live in `:local`
- Other dependencies: `:model`, `:lyrics`, `:common`, `:network`

### Song encyclopedia

`SongEncyclopediaRepository` returns one of four results: success, unsupported source, unavailable data, or retryable failure.

- Only songs with a NetEase source identity are queried
- Missing data is kept separate from network errors
- Encyclopedia content is not inferred for other sources

### YouTube

- Scripts are in `src/main/assets/youtube`
- `youtube/api/challenge` owns EJS challenges, player script caching, the shared JavaScriptSandbox, and the local WebView fallback
- `consumer-rules.pro` keeps the Rhino reflection entry points NewPipe needs
- Credential files, keys, and serialized names must stay compatible with installed versions

### Tests

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
./gradlew :platform:testDebugUnitTest --tests "*Subsonic*"
```

### Music server integration

> [!WARNING]
> Self-hosted music server support is a preview feature and has not been sufficiently tested. Compatibility and stability across servers, libraries, and network conditions still need validation.

- Reuses the existing song, playback URL, lyric, and playback host models
- The app injects accounts, the repository, and shared HTTP
- See the [API contract](../../docs/music-server-api.md) (Chinese) for request, identity, and cache semantics

Core files:

- [SubsonicClient](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicClient.kt): REST parameters, authentication, and JSON responses
- [SubsonicResourceInterceptor](src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicResourceInterceptor.kt): resolves internal references into per-request authenticated audio or image requests
- [SubsonicAccounts](src/main/java/moe/ouom/neriplayer/platform/subsonic/auth/SubsonicAccounts.kt): encrypted profiles, account state, and revision checks
- [SubsonicRepository](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt): connection checks, library queries, and song and lyric mapping
- [SubsonicBrowseCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseCache.kt), [SubsonicBrowseRoomStore](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseRoomStore.kt), [SubsonicCatalog](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicCatalog.kt): complete album, song, and artist directories, shared requests, and Room snapshots
- [SubsonicLyricsCache](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLyricsCache.kt), [SubsonicAudioMetadata](src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicAudioMetadata.kt): successful lyric cache and server-declared audio metadata
- [ServerSongRef](../model/src/main/java/moe/ouom/neriplayer/data/model/server/ServerSongRef.kt) (`:model`): reversible source reference from profile UUID and raw string ID
