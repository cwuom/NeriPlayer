# 音乐服务器接入 API 规范 v0.1

状态：供 issue #490 讨论的客户端接入规范。基础协议部分采用现有 Subsonic / OpenSubsonic；后续能力和扩展提案不代表 Navidrome 已实现。审阅基线：`2c68dbbbee5eb0b8f45e4d2937e7a313bbd97037`，2026-10-04。

本文覆盖认证、曲库、搜索、播放、封面、歌词、发现、收藏歌单、同步边界及上传扩展。具体原型范围见 [MVP](music-server-mvp.md)，交付顺序见 [开发计划](music-server-development-plan.md)。API 规范完整覆盖规划范围，不要求第一份实现 PR 包含全部接口。

## 1. 分层与术语

- **HTTP API**：音乐服务器实际提供的接口，Navidrome 适配使用 `/rest/<method>.view`。
- **客户端仓库契约**：`:platform` 将协议响应转换成音理的 `SongItem`、`SongUrlResult`、`LyricEntry`；页面和播放器不拼接认证 URL。
- **profileId**：本机一个服务器账号配置的 UUID。同一地址的两个账号拥有不同 ID。
- **resourceId**：服务器返回的原始字符串，区分大小写，禁止转为 Long 后替代原值。
- **P0 / P1 / P2**：今晚原型 / 可供上游评审的完整首版 / 后续能力。

现有网易云、Bilibili、YouTube Music 客户端保持各自登录和仓库。新增适配器通过 `AppContainer` 和已有 playback host 注入；本规范不要求重建统一平台框架。

## 2. 连接与认证

### 2.1 配置

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| profileId | UUID | 创建时生成；修改标签或同一实例的地址时保留；更换账号/实例创建新 ID |
| protocol | enum | 首版固定 `subsonic`；OpenSubsonic 是协商能力，Navidrome 是兼容目标 |
| baseUrl | HTTP(S) URL | 包括可选端口、反向代理子路径；规范化为尾部 `/`；不含 userinfo、query、fragment |
| username | string | 去首尾空白，不为空；不能从 URL 提取密码 |
| credentialRef | local reference | 指向受保护的凭据；不得参与普通配置/歌单导出 |
| labelOverride | string? | 用户设置优先于上游标签；都没有时使用主机名 |
| iconOverride | local/remote reference? | P1；无图时占位，不阻塞连接 |
| enabled | boolean | P1 提供启停；停用配置不参加搜索或新播放 |
| upstreamMetadata | object? | 可选缓存，不覆盖用户设置；不支持时为 null |

P0 用 `SubsonicProfile` 保存 ID、标签、地址、用户名与 enabled，使用独立的 EncryptedSharedPreferences 保存配置及密码，界面只提供新增、移除。P1 补编辑、重新认证、停用和图标。当前端口包含在完整 URL 中，无需再存一份可冲突的端口值。

### 2.2 公共请求参数

所有 JSON 请求使用 GET，路径由 URL builder 在 baseUrl 后追加 `rest/<method>.view`，所有参数各自编码，不手工拼接：

| 参数 | 值 |
| --- | --- |
| u | 当前配置用户名 |
| t | `lowercaseHex(MD5(UTF8(password + salt)))` |
| s | 每次请求重新生成的随机 salt；P0 使用 16 随机字节的十六进制表示 |
| v | `1.16.1` |
| c | `NeriPlayer` |
| f | `json` |

例：配置 `https://music.example.com:4533/navidrome/`，生成路径 `/navidrome/rest/ping.view`。示意参数中的 `t=<token>&s=<salt>` 不能作为可保存播放地址。token 不是 OAuth 会话，不存在本方案可调用的“刷新 token”接口；每次根据凭据重新计算。HTTP 不加密传输，设置页应明确提示。

API Key 与表单 POST 仅在后续能力协商后增加；不得把 API Key 认证参数与用户名/token 方式混用。[公共 API 规范](https://opensubsonic.netlify.app/docs/api-reference/)

### 2.3 连接过程

1. 本地校验 URL 和必填项。
2. 使用候选配置请求 `ping`，HTTP 和业务状态都成功后才保存。
3. `getOpenSubsonicExtensions` 查询可选扩展。P0 在首次读取歌词时按需发现。
4. 加载专辑或执行用户搜索；`ping` 成功不保证曲库读取权限或媒体格式可播放。
5. 认证失败提示重新登录，不循环重试密码，不替换为另一个配置。

扩展名与版本以返回值为准；未知扩展忽略。`songLyrics` 缺失表示不能按歌曲 ID 读取结构化歌词。扩展接口明确不存在时回退基础协议，超时/认证错误不能永久记为“不支持”。P1 能力缓存以配置修订号、服务器版本及过期时间失效。[能力发现](https://opensubsonic.netlify.app/docs/endpoints/getopensubsonicextensions/)

## 3. 响应与失败约定

成功示意：

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[]}}}
```

业务失败可能出现在 HTTP 200 内：

```json
{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"…"}}}
```

客户端必须检查 `subsonic-response.status`。错误显示使用本地描述，不直接展示服务器错误原文或完整认证 URL。P0 JSON 响应限制 4 MiB；曲库较大时依靠分页，单个超大专辑会明确失败，后续可配置上限。JSON 请求超时 15 秒，取消继续传播。

| 客户端语义 | HTTP / 业务信号 | 行为 |
| --- | --- | --- |
| AuthenticationRequired | HTTP 401；协议 40、41 | 重新认证；保留歌曲来源 |
| PermissionDenied | HTTP 403；协议 50 | 说明权限不足，不当作空曲库 |
| NotFound | HTTP 404；协议 70 | 区分不存在的歌曲、路径或可选接口 |
| Incompatible | 协议 20、30 | 版本不兼容；不能盲目降级认证 |
| Unsupported | 缺少扩展或明确不支持 | 隐藏/禁用对应能力 |
| Timeout / Network | 网络故障、超时 | 可重试；不修改账号、不缓存为空结果 |
| InvalidResponse | 非预期 JSON、缺必要字段、过大响应 | 错误状态，保留上一页有效内容 |
| Cancelled | 用户离页、切服、切搜索词 | 不展示错误；不允许旧响应覆盖新页面 |
| Empty | status=ok，集合为空 | 正常空状态 |

HTTP 错误与协议错误的代码域在 P1 的结构化结果中分开保存。P0 对外用 `SubsonicException` 和安全提示，尚无公共 Result 层。原型对歌词读取失败返回空列表，播放器不中断；P1 应补“无歌词 / 不支持 / 读取失败”的独立 UI 状态。

## 4. 端点映射

下表列出本接入所需参数；每个请求还需要第 2 节的公共认证参数。协议的更多可选参数以链接文档为准。

| 阶段 | 方法 | 参数 | 成功数据路径 | 客户端职责 |
| --- | --- | --- | --- | --- |
| P0 | ping | 无 | 响应根 | 连接检查 |
| P0 | getOpenSubsonicExtensions | 无 | openSubsonicExtensions[] | name、versions[] |
| P0 | getAlbumList2 | type、size、offset | albumList2.album[] | 专辑分页 |
| P0 | getAlbum | id | album，album.song[] | 专辑和完整曲目列表 |
| P0 | search3 | query、songCount、songOffset、albumCount、albumOffset、artistCount、artistOffset | searchResult3.song[] / album[] / artist[] | P0 只取歌曲；P1 扩展各类独立分页 |
| P0 | getSong | id | song | 同一来源资源恢复/刷新 |
| P0 | stream | id、format=raw | 二进制音频 | Media3 在线播放 |
| P0 | getCoverArt | id、size | 二进制图片 | Coil 与系统封面 |
| P0 | getLyricsBySongId | id | lyricsList.structuredLyrics[] | 需 songLyrics，逐行/普通歌词 |
| P1 | getMusicFolders | 无 | musicFolders.musicFolder[] | 可选曲库过滤 |
| P1 | getArtists / getArtist | musicFolderId? / id | artists / artist | 艺人浏览 |
| P1 | getAlbumList2 | type=newest/frequent/random，size，offset | albumList2.album[] | 有明确类型的发现栏目 |
| P1 | getRandomSongs | size、musicFolderId? | randomSongs.song[] | 随机歌曲，无 offset 分页 |
| P2 | getStarred2 | musicFolderId? | starred2 | 服务器收藏读取 |
| P2 | star / unstar | id / albumId / artistId，可重复 | 响应根 | 显式写操作 |
| P2 | getPlaylists / getPlaylist | username? / id | playlists.playlist[] / playlist.entry[] | 服务器歌单读取 |
| P2 | createPlaylist | name 或 playlistId，重复 songId | playlist | 创建或替换歌单 |
| P2 | updatePlaylist | playlistId，name?，comment?，public?，重复 songIdToAdd / songIndexToRemove | 响应根 | 按服务器定义更新；删除索引不等于歌曲 ID |
| P2 | deletePlaylist | id | 响应根 | 明确确认后删除 |
| P2 | scrobble | id，time?，submission? | 响应根 | 播放事件上报，另定去重策略 |

主接口依据：[专辑列表](https://opensubsonic.netlify.app/docs/endpoints/getalbumlist2/)、[专辑详情](https://opensubsonic.netlify.app/docs/endpoints/getalbum/)、[搜索](https://opensubsonic.netlify.app/docs/endpoints/search3/)、[歌曲详情](https://opensubsonic.netlify.app/docs/endpoints/getsong/)。后续接口依据：[随机歌曲](https://opensubsonic.netlify.app/docs/endpoints/getrandomsongs/)、[收藏](https://opensubsonic.netlify.app/docs/endpoints/getstarred2/)、[歌单更新](https://opensubsonic.netlify.app/docs/endpoints/updateplaylist/)、[播放上报](https://opensubsonic.netlify.app/docs/endpoints/scrobble/)。

### 4.1 分页和搜索

- P0 页长 30；专辑列表使用 `alphabeticalByName`。API 最大 size 为 500，客户端不能超过协议上限。
- search3 原型设置 `songCount=30`、`artistCount=0`、`albumCount=0`；另外两个结果组不会暗中占用歌曲 offset。
- offset 按服务器本次返回条数递增，再对显示内容按完整资源身份去重。短页结束；恰好满页可能多请求一次空页。
- getAlbum 返回专辑歌曲，不假设支持 songOffset。
- 每个服务器、关键词、资源类型分别维护页状态。服务器发生扫描更新时分页未必有快照一致性，允许刷新重新加载。
- 原型保持服务器的搜索顺序。综合搜索后续并发上限建议 3，各自超时和重试，不能因一台服务器失败丢掉所有结果。

### 4.2 歌曲映射

| 上游字段 | 客户端字段 | 规则 |
| --- | --- | --- |
| id | audioId、mediaUri | 原始 ID 可逆编码；profileId 参与命名空间 |
| title | SongItem.name | 缺失以原始 ID 兜底 |
| artist | artist | 原样保留；不强行拆分人名 |
| album | album | 真实专辑名，不用它承载平台标签 |
| albumId | Long 代理值及仓库原始 ID | 代理值不能用于服务器 API 请求 |
| duration | durationMs | 秒→毫秒；缺失为未知/0，不从文件大小猜测 |
| coverArt | coverUrl | 按原始封面 ID 创建内部引用，不能默认等于歌曲或专辑 ID |
| contentType、suffix、bitRate 等 | 后续 PlaybackAudioInfo | 只有实际已知才展示；bitRate 单位按协议解释 |

服务器歌曲 ID 与其他平台数值 ID 没有关联。[Navidrome 接口差异](https://www.navidrome.org/docs/developers/subsonic-api/)、[Child 响应字段](https://opensubsonic.netlify.app/docs/responses/child/)

## 5. 资源身份、播放和图片

当前实现的规范化编码：

```text
channelId = subsonic
audioId   = v1:<profileId>:<base64url(UTF8(songId))>
mediaUri  = neri-server://<profileId>/song/<同样的编码>
streamUrl = null
id        = SHA-256("song:" + audioId) 前 8 字节构造的负 Long 代理值
```

Base64URL 不带 padding。完整身份使用现有 SongIdentity(id, "subsonic", mediaUri)，Long 仅适配已有模型；不能单独承担跨服务器去重。历史只有 mediaUri 时仍可还原来源。无效的服务器引用必须失败，不落入网易云默认分支。

### 5.1 请求时认证

原型复用现有 Media3 / Coil 的共享 OkHttp，向播放器和图片组件提供内部地址：

```text
https://neri-server.invalid/v1/<profileId>/stream/<encodedSongId>
https://neri-server.invalid/v1/<profileId>/cover/<encodedCoverId>
```

`.invalid` 地址不是服务器 API 或代理服务；专用拦截器只识别这个保留域，在客户端内部解析为当前配置的请求并补认证。解析失败就中止，不能访问该保留域。其他图片和平台请求不附带服务器凭据。返回给调用方的 request 仍为无凭据内部引用，队列恢复使用同一机制。

当前音频表示固定 raw，缓存键 `subsonic:<audioId>:raw:v1`；封面固定 600 像素，图片引用包含配置、封面 ID 和引用版本。P1 增加可变尺寸或转码时必须把尺寸/格式/码率/表示版本加入缓存身份。同一 ID 内容原地替换后的主动缓存失效属于 P1。

### 5.2 流与失败

stream 原型指定 `format=raw`，不调用 download 接口。拖动依赖 Media3、文件容器和服务器 Range 支持；转码音频的 timeOffset 需要对应扩展，不应承诺任意服务器都能转码精确拖动。媒体请求由播放器自身的加载与取消机制管理。[stream](https://opensubsonic.netlify.app/docs/endpoints/stream/)

stream / getCoverArt 成功是二进制，失败可能返回 XML/JSON，即使请求了 f=json 也不能直接交给解码器。原型检查 HTTP 状态及错误 Content-Type；无法提前识别的错误字节由媒体解码器拒绝。封面失败使用占位图，不影响音频。服务端 coverArt 字段为空时不发图片请求。[getCoverArt](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/)

移除配置后新解析会失败；已缓冲内容可能仍能播放，删除配置不等于清除媒体缓存或远程删歌。P1 应提供明确的来源状态及按配置清缓存能力。

## 6. 歌词

先查 `songLyrics` 扩展；原型使用 v1 兼容的按行数据，v2 逐字能力另行实现。请求为 `getLyricsBySongId(id)`，不将 Long 代理 ID 发给歌词接口。[端点](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)

示意响应的业务部分：

```json
{"lyricsList":{"structuredLyrics":[{"lang":"zho","synced":true,"offset":100,"line":[{"start":1000,"value":"第一行"},{"start":3500,"value":"第二行"}]}]}}
```

- start 和 offset 单位毫秒。正 offset 表示提前，展示起点为 `max(0, start - offset)`。
- 原型优先第一份非空 synced 歌词，否则选第一份普通歌词。P1 增加语言选择、多份结果和来源状态。
- 按起点排序，以后续更大的起点作为行终点，最后一行用有效歌曲时长兜底。
- synced=false 使用已有普通歌词转换器；人为分配的显示时间不是服务端准确时间轴。
- 空集合是无歌词；不支持是能力缺失；超时是错误。原型播放器将后两者降级为空，规范目标保留区分。
- 尊重手动保存和现有明确歌词源偏好；没有明确偏好时读取服务器歌词。其他来源的手动匹配仍走既有入口，不改写曲库文件。
- 翻译/罗马音只有真实保存或匹配结果才展示，不以服务器歌曲代理 ID 请求网易云。

offset 和结构依据：[structuredLyrics](https://opensubsonic.netlify.app/docs/responses/structuredlyrics/)。

## 7. 推荐、标签和上游扩展

### 7.1 发现与匹配

P1 提供 `newest`（最近添加）、`frequent`（常听）、`random`（随机）栏目，保持实际语义。基础协议不能保证个性化推荐；能力不支持时不显示“为你推荐”。随机结果只做本次展示内去重，不伪造连续游标。

综合匹配范围默认仅为已启用服务器，第三方平台是否参加需在 issue 另行确认。匹配沿用 SearchTextMatcher：歌名和歌手为主，热度只作为相似文本候选的次级顺序；不同服务器原始播放次数不直接比较。保留来源和版本差异，不自动把失败曲目换成另一个来源。算法只对已返回候选排序，不保证全库全局最优。

### 7.2 客户端可选契约（P1/P2 提案）

```text
getServerMetadata(profileId) -> Supported(ServerMetadata) | Unsupported | Failure
ServerMetadata = { label?: string, iconRef?: ResourceRef, revision?: string }

getDiscovery(profileId, kind, page) -> DiscoverySection | Unsupported | Failure
DiscoverySection = { kind, title, albums: [...], songs: [...], nextPage? }
kind = RECENTLY_ADDED | FREQUENT | RANDOM | PERSONALIZED
```

`ping.type/serverVersion` 仅用于软件识别，`getAvatar` 是用户头像；都不能充当实例自定义标签和品牌图标。Navidrome 基础适配器的 `getServerMetadata` 返回 Unsupported，UI 使用用户配置或默认值。

如果维护者希望扩充上游规范，应另开版本化扩展议题，先确定扩展名称、发现方式、endpoint、认证、字段上限、缓存和失败回退。本文不擅自发明一个已经可调用的 `/metadata` 或 `/recommendations` 接口。个性化推荐缺失不影响基础接入。

## 8. 收藏、同步、下载与上传

- 本地收藏和服务器 star 是两份状态；P2 前不能把本地收藏按钮偷偷映射成服务端写操作。
- 更新歌单可能非幂等；失败后先读取实际结果再让用户重试，不能对新增歌曲盲目自动重试。需要处理只读权限、重复歌曲和顺序变化。
- stream 本身不等于 scrobble。P2 单独决定播放上报触发点、重复事件抑制和离线补报；原型只沿用客户端本地统计。[Navidrome 说明](https://www.navidrome.org/docs/developers/subsonic-api/)
- 服务器配置和密码不进入 GitHub/WebDAV 普通同步。当前已有歌单/历史同步可能携带无凭据的歌曲引用；另一设备没有相同配置 ID 时不可播放，不自动通过同名服务器重绑定。
- 原型保留已有元数据同步映射中的条目，避免过滤混合歌单时造成删除；跨设备可播放配置迁移仍未实现。发布前需专门审阅混合版本客户端、删除标记和身份往返。
- 原型不支持永久下载与一起听，实际操作路径必须拒绝，不导出认证直链。
- 音频上传不在采用的基础协议内，P2 默认 Unsupported。后续需独立定义上传会话、权限、大小/格式限制、分片/续传、校验和、冲突策略、最终提交和取消；不得用客户端 WebDAV 设置冒充 Navidrome 上传 API。

## 9. 原型仓库契约及后续增量

当前实际方法位于 `SubsonicRepository`：

| 方法 | 输入 | 输出 |
| --- | --- | --- |
| addAccount | label、address、username、password | ping 成功后保存；失败抛安全异常 |
| albums | profileId、offset、size=30 | List\<ServerAlbum\> |
| albumSongs | profileId、albumId | List\<SongItem\> |
| search | profileId、query、offset、size=30 | List\<SongItem\> |
| song | ServerSongRef | SongItem；已提供方法，原型恢复尚未主动刷新详情 |
| playback | SongItem | 现有 SongUrlResult，URL 为内部引用 |
| lyrics | SongItem | List\<LyricEntry\>；空代表原型可展示歌词为空 |

P1 增量契约：连接能力快照、账号编辑/重新认证、资源类型分别分页、发现列表、结构化歌词状态、服务器标签与图标。P2 增量契约：服务器收藏歌单写入、上传、跨设备配置映射。只在真实调用方需要时引入小接口，不预先迁移所有平台实现。

## 10. 协议验收清单

以下是待执行的验收要求，不代表本次已经完成联调：

1. HTTP 200 + status=failed、HTTP 401、非 JSON 响应、超时分别显示正确状态。
2. 根路径、端口、反代子路径和含特殊字符 ID 的 URL 编码正确。
3. 两配置具有相同 songId/coverArt 时，歌曲身份与缓存隔离。
4. 切服/切关键词时取消旧请求，分页不会串入旧列表。
5. 无 coverArt 不发错误请求；封面认证与音频认证都不落入普通持久化。
6. 缺歌词扩展、空歌词、普通歌词、正负 offset、行歌词均有对应行为。
7. 队列、历史、本地歌单恢复同一服务器引用；删除配置后来源不可用。
8. 原有平台登录播放不受影响，服务器音频不会走网易云 URL/歌词兜底。
9. 下载、一起听、同步、分享的实际入口不泄露服务器认证材料。
