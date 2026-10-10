# 平台 / Platforms

`:platform` 集中管理 Bilibili、网易云音乐、YouTube Music、Subsonic 音乐服务器，以及评论、远程歌词和元数据搜索。歌词解析和 Lyricon 接入在 `:lyrics`。

`:platform` contains Bilibili, NetEase Cloud Music, YouTube Music, and Subsonic server integrations, plus comments, remote lyrics, and metadata search. Lyric parsing and Lyricon integration belong to `:lyrics`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/platform`。

Sources are in `src/main/java/moe/ouom/neriplayer/platform`.

- `bilibili`、`netease`、`youtube`：各平台的请求、账号、缓存和播放来源 / platform requests, accounts, caches, and playback sources
- `subsonic`：Navidrome / OpenSubsonic 的协议、独立加密账号、曲库映射、浏览/歌词缓存及请求时资源认证 / server protocol, encrypted accounts, library mapping, browse/lyrics caches, and request-time media authentication
- `lyrics`、`search`：歌词检索、匹配与元数据搜索 / lyric lookup, matching, and metadata search
- `comments`：评论分页、映射与缓存 / comment pagination, mapping, and caching

各平台内部的 `api` 负责请求协议，账号和缓存由各自账号/仓库层管理。SubsonicClient 使用传入的配置和密码；当前 SubsonicResourceInterceptor 经宿主注入提供者读取具体 SubsonicAccounts，仍有 api → auth 的直接耦合，需按协议包窄依赖要求继续评审。Room 实体与 DAO 在 `:database`，跨平台本地歌单在 `:local`。本库还依赖 `:model`、`:lyrics`、`:common` 和 `:network`。

Each platform's `api` package handles protocols; account and repository layers manage credentials and caches. SubsonicClient receives a profile and password. SubsonicResourceInterceptor currently reads concrete SubsonicAccounts through a host-injected provider, so its direct api-to-auth dependency still needs review against the protocol package boundary. Room entities and DAOs live in `:database`, and cross-platform local playlists in `:local`. Other dependencies are `:model`, `:lyrics`, `:common`, and `:network`.

YouTube 脚本在 `src/main/assets/youtube`，Rhino 反射入口由 `consumer-rules.pro` 保留。凭据文件、键名和序列化名称需兼容已安装版本。

YouTube scripts are in `src/main/assets/youtube`; `consumer-rules.pro` preserves Rhino reflection entry points. Credential files, keys, and serialized names must remain compatible with installed versions.

## 测试 / Tests

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
./gradlew :platform:testDebugUnitTest --tests "*Subsonic*"
```

服务器接入沿用现有 SongItem、SongUrlResult、LyricEntry 和 playback host，具体约定与已验证范围见[设计回顾](../../docs/music-server-integration.md)。

逐文件职责和测试导航见[文件地图](../../docs/music-server-files.md)；请求参数和实际失败/缓存语义见[API 契约](../../docs/music-server-api.md)。

Server integration reuses SongItem, SongUrlResult, LyricEntry, and the playback host. See the [design review](../../docs/music-server-integration.md) for contracts and validation scope (Chinese).

See the [file guide](../../docs/music-server-files.md) and [API contract](../../docs/music-server-api.md) for responsibilities, request parameters, and current failure/cache semantics (Chinese).
