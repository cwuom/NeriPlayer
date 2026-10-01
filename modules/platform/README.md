# 平台 / Platforms

`:platform` 集中管理 Bilibili、网易云音乐、YouTube Music，以及评论、远程歌词和元数据搜索。歌词解析和 Lyricon 接入在 `:lyrics`。

`:platform` contains Bilibili, NetEase Cloud Music, and YouTube Music integrations, plus comments, remote lyrics, and metadata search. Lyric parsing and Lyricon integration belong to `:lyrics`.

## 结构 / Layout

源码位于 `src/main/java/moe/ouom/neriplayer/platform`。

Sources are in `src/main/java/moe/ouom/neriplayer/platform`.

- `bilibili`、`netease`、`youtube`：各平台的请求、账号、缓存和播放来源 / platform requests, accounts, caches, and playback sources
- `lyrics`、`search`：歌词检索、匹配与元数据搜索 / lyric lookup, matching, and metadata search
- `comments`：评论分页、映射与缓存 / comment pagination, mapping, and caching

各平台内部的 `api` 负责请求协议，通过注入接口获取凭据；账号和缓存由仓库管理。Room 实体与 DAO 在 `:database`，跨平台本地歌单在 `:local`。本库还依赖 `:model`、`:lyrics`、`:common` 和 `:network`。

Each platform's `api` package handles protocols with injected credentials; repositories manage accounts and caches. Room entities and DAOs live in `:database`, and cross-platform local playlists in `:local`. Other dependencies are `:model`, `:lyrics`, `:common`, and `:network`.

YouTube 脚本在 `src/main/assets/youtube`，Rhino 反射入口由 `consumer-rules.pro` 保留。凭据文件、键名和序列化名称需兼容已安装版本。

YouTube scripts are in `src/main/assets/youtube`; `consumer-rules.pro` preserves Rhino reflection entry points. Credential files, keys, and serialized names must remain compatible with installed versions.

## 测试 / Tests

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
```
