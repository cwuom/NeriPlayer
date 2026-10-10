# 音乐服务器接入 API 与客户端契约 v0.2

状态更新：2026-10-10。本文对照 `feat/navidrome-mvp` 及当前工作区，说明首个 PR 的实际调用与后续协议规划。已合并的源码基线为 `285beb6b`，尚未创建合并提交；本轮查询上游已前进至 `400e278f`，未对齐或验证该版本。基础请求参数采用 Subsonic 1.16.1，部分行为依赖 OpenSubsonic 约定或扩展；不声明完整实现 OpenSubsonic。标记为“后续”的方法或契约尚未接入，不代表 Navidrome 已实现所有扩展。

本文覆盖认证、曲库、搜索、播放、封面、歌词、缓存及后续扩展边界。当前用户行为见[功能说明](music-server-mvp.md)，实施差异见[设计回顾](music-server-integration.md)，逐文件职责见[文件地图](music-server-files.md)，证据见[验收总报告](music-server-acceptance.md)，排期见[开发计划](music-server-development-plan.md)。

## 1. 分层与术语

- **HTTP API**：音乐服务器实际提供的接口，Navidrome 适配使用 `/rest/<method>.view`。
- **客户端仓库契约**：`:platform` 将协议响应转换成音理的 `SongItem`、`SongUrlResult`、`LyricEntry`；页面和播放器不拼接认证 URL。
- **profileId**：本机一个服务器账号配置的 UUID。同一地址的两个账号拥有不同 ID。
- **resourceId**：服务器返回的原始字符串，区分大小写，禁止转为 Long 后替代原值。
- **当前 / 后续**：当前是本工作区已实现契约；后续是提案和待办，其首版排期仍需维护者评审，不能将标记理解为 issue 已确认的范围。

现有网易云、Bilibili、YouTube Music 客户端保持各自登录和仓库。新增适配器通过 `AppContainer` 和已有 playback host 注入；本规范不要求重建统一平台框架。

## 2. 连接与认证

### 2.1 配置

| 当前字段 | 类型 | 规则 |
| --- | --- | --- |
| id（profileId） | UUID string | 创建时生成；编辑名称、同实例地址或密码时保留；移除后重加生成新 ID |
| label | string | 用户输入优先，空白回退主机名 |
| baseUrl | HTTP(S) URL | 包含端口和反代子路径，尾部 `/`；不含 userinfo、query、fragment |
| username | string | 去首尾空白且非空；编辑界面不修改用户名，更换账号应新建配置 |
| enabled | boolean | 模型已保留，当前界面尚无停用开关 |
| revision | Long | 新建为 0，更新配置时递增；绑定请求结果与缓存有效性 |

`SubsonicProfile` 不携带密码；配置和密码保存于独立的 `EncryptedSharedPreferences`，凭据读取仅用于 IO 请求。当前没有公开的 credentialRef、图标、上游品牌信息或排序字段。protocol 固定为 Subsonic，未提供协议选择 UI。

添加和更新通过仓库先 `ping` 成功再保存；底层 accounts.save 本身不执行连接检查。更新使用 expectedRevision 检查并发变更，成功后清理该配置的扩展能力、歌词及音频元数据缓存；浏览缓存按 revision 隔离，并由账号流异步清理旧快照/任务。移除只作用于本机配置：浏览缓存订阅账号流清理旧条目，歌词/元数据/能力的进程内条目没有统一删除入口，但缺少配置时不会作为有效账号结果读取。

媒体字节缓存和图片资源引用目前不含配置 revision。编辑地址指向另一实例且原始资源 ID 重合时，仍可能复用旧媒体；此项已列为发布前待修复，不属于浏览/歌词/音频元数据修订隔离已经覆盖的范围。更换账号或实例应新增配置；现有编辑入口没有自动判断新地址是否仍属于同一实例。

### 2.2 公共请求参数

所有 JSON 请求使用 GET，路径由 URL builder 在 baseUrl 后追加 `rest/<method>.view`，所有参数各自编码，不手工拼接：

| 参数 | 值 |
| --- | --- |
| u | 当前配置用户名 |
| t | `lowercaseHex(MD5(UTF8(password + salt)))` |
| s | 每次请求重新生成的随机 salt；当前使用 16 随机字节的十六进制表示 |
| v | `1.16.1` |
| c | `NeriPlayer` |
| f | `json` |

例：配置 `https://music.example.com:4533/navidrome/`，生成路径 `/navidrome/rest/ping.view`。示意参数中的 `t=<token>&s=<salt>` 不能作为可保存播放地址。token 不是 OAuth 会话，不存在本方案可调用的“刷新 token”接口；每次根据凭据重新计算。HTTP 不加密传输，设置页应明确提示。

API Key 与表单 POST 仅在后续能力协商后增加；不得把 API Key 认证参数与用户名/token 方式混用。[公共 API 规范](https://opensubsonic.netlify.app/docs/api-reference/)

### 2.3 连接过程

1. 本地校验 URL 和必填项。
2. 使用候选配置请求 `ping`，HTTP 和业务状态都成功后才保存。
3. `getOpenSubsonicExtensions` 查询可选扩展。当前在首次读取歌词时按需发现。
4. 加载专辑或执行用户搜索；`ping` 成功不保证曲库读取权限或媒体格式可播放。
5. 认证失败提示重新登录，不循环重试密码，不替换为另一个配置。

扩展名与版本以返回值为准；未知扩展忽略。`songLyrics` 缺失表示不能按歌曲 ID 读取结构化歌词。当前扩展查询遇到协议错误 code=0/70 或 HTTP 404 时回退为不支持，其他错误继续向上传播；code=0 是通用错误，这种回退不等于已确认服务器完全不支持扩展。能力缓存绑定配置修订号，尚未接入服务器版本/TTL 失效或手动刷新；发现失败的细分与刷新属于后续完善。[能力发现](https://opensubsonic.netlify.app/docs/endpoints/getopensubsonicextensions/)

## 3. 响应与失败约定

成功示意：

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[]}}}
```

业务失败可能出现在 HTTP 200 内：

```json
{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"…"}}}
```

客户端检查 `subsonic-response.status`。错误显示使用本地描述，不直接展示服务器错误原文或完整认证 URL。当前 JSON 响应限制 4 MiB；曲库较大时依靠分页，单个超大专辑会明确失败，后续可配置上限。仓库为每次 JSON 调用设置 15 秒预算，SubsonicClient 本身不配置该预算；连接检查超时由账号 UI 单独处理，曲库/歌曲/歌词请求的内部超时转为 TIMEOUT，外部取消继续传播。

| 实际 kind / 结果 | HTTP / 业务信号 | 当前行为 |
| --- | --- | --- |
| AUTHENTICATION | HTTP 401；协议 40、41 | 认证提示；41 不支持 token 的限制见下文 |
| FORBIDDEN | HTTP 403；协议 50 | 权限不足 |
| NOT_FOUND | HTTP 404；协议 70 | 通用资源/接口不存在提示，不细分歌曲、路径或扩展 |
| UNSUPPORTED | 协议 20、30 | 协议不兼容；不自动降级认证 |
| RATE_LIMITED / SERVER | HTTP 429 / 5xx | 标记 retryable；保留 Retry-After 上限 24 小时，不意味着等待满该时长 |
| TIMEOUT / NETWORK / TLS | 超时 / 其他 IO / SSLException | TLS 不重试；网络和超时标记 retryable |
| INVALID_RESPONSE | 无法解析根 JSON、状态失败且错误码未知、响应超限 | 安全错误；缺失的具体列表字段不一定产生错误，见下文 |
| ACCOUNT_UNAVAILABLE / CONFIG_CHANGED | 配置缺失/不可用/请求修订失配；并发编辑冲突 | 当前请求停止或提示检查配置 |
| CancellationException（非 kind） | 外部取消 | 传播取消，不显示为普通网络失败 |
| 空列表（非 kind） | 正常空集合，或部分缺失列表字段 | 当前页面按空结果展示 |

当前对外使用 `SubsonicException : IOException`，保留 kind、code、httpStatus、retryAfterMs 与 retryable；HTTP 状态与协议错误可通过 httpStatus 区分，未引入公共 Result 层。页面使用本地化安全提示；取消向上传播。播放器对歌词读取失败进行降级，不中断音频；“无歌词 / 不支持 / 读取失败”的独立 UI 状态仍待后续。

协议 code=40 表示凭据错误，code=41 特指 LDAP 用户不支持 token 认证。当前客户端将二者映射到同一认证类别，且仅使用 username + token/salt；重新登录不能解决认证方式不支持的情况。OpenSubsonic 的 code=42/43/44 分别表示认证方式不支持、多个认证方式冲突和无效 API Key，当前没有精确映射，落入 INVALID_RESPONSE；helpUrl 不读取或展示。认证方式选择和更细的提示属于后续完善。[错误码定义](https://opensubsonic.netlify.app/docs/responses/error/)

当前并非严格 schema 校验：getAlbumList2 的 albumList2/album、search3 的 searchResult3/结果数组，以及 getAlbum 的 song 数组缺失时可退化为空列表；getAlbum 的 album、getSong 的 song 和曲目 id 使用必需读取，缺失会失败。列表中的非 JSONObject 项会被忽略。不能把“缺必要字段一律报 INVALID_RESPONSE”写成已实现保证；严格校验及与真实空库的区分列为后续协议健壮性检查。

retryable 只是分类。JSON client 没有额外的业务重试循环，共享 OkHttp 自身的连接恢复仍按其配置运行；媒体加载由 ServerMediaLoadErrorPolicy 对连续错误最多允许 3 次重试，超过 30 秒的 Retry-After 不在 loader 内等待；不存在跨播放会话的总重试预算。

## 4. 端点映射

下表列出本接入所需参数；每个请求还需要第 2 节的公共认证参数。协议的更多可选参数以链接文档为准。

| 阶段 | 方法 | 参数 | 成功数据路径 | 客户端职责 |
| --- | --- | --- | --- | --- |
| 当前 | ping | 无 | 响应根 | 连接检查 |
| 当前 | getOpenSubsonicExtensions | 无 | openSubsonicExtensions[] | name、versions[] |
| 当前 | getAlbumList2 | type、size、offset | albumList2.album[] | 专辑分页 |
| 当前 | getAlbum | id | album，album.song[] | 专辑和完整曲目列表 |
| 当前 | search3 | query；专辑：albumCount/albumOffset，songCount=0、artistCount=0；单曲：songCount/songOffset，albumCount=0、artistCount=0 | searchResult3.album[] 或 song[] | 不发送 artistOffset、不消费 artist[]；空 query 枚举单曲目录 |
| 当前 | getSong | id | song | 读取服务器声明的音频元数据；forceRefresh 绕过进程缓存 |
| 当前 | stream | id、format=raw | 二进制音频 | Media3 在线播放 |
| 当前 | getCoverArt | id、size | 二进制图片 | Coil 与系统封面 |
| 当前 | getLyricsBySongId | id | lyricsList.structuredLyrics[] | 需 songLyrics，逐行/普通歌词 |
| 后续 | getMusicFolders | 无 | musicFolders.musicFolder[] | 可选曲库过滤 |
| 后续 | getArtists / getArtist | musicFolderId? / id | artists / artist | 艺人浏览 |
| 后续 | getAlbumList2 | type=newest/frequent/random，size，offset | albumList2.album[] | 有明确类型的发现栏目 |
| 后续 | getRandomSongs | size、musicFolderId? | randomSongs.song[] | 随机歌曲，无 offset 分页 |
| 后续 | getStarred2 | musicFolderId? | starred2 | 服务器收藏读取 |
| 后续 | star / unstar | id / albumId / artistId，可重复 | 响应根 | 显式写操作 |
| 后续 | getPlaylists / getPlaylist | username? / id | playlists.playlist[] / playlist.entry[] | 服务器歌单读取 |
| 后续 | createPlaylist | name 或 playlistId，重复 songId | playlist | 创建或替换歌单 |
| 后续 | updatePlaylist | playlistId，name?，comment?，public?，重复 songIdToAdd / songIndexToRemove | 响应根 | 按服务器定义更新；删除索引不等于歌曲 ID |
| 后续 | deletePlaylist | id | 响应根 | 明确确认后删除 |
| 后续 | scrobble | id，time?，submission? | 响应根 | 播放事件上报，另定去重策略 |

主接口依据：[专辑列表](https://opensubsonic.netlify.app/docs/endpoints/getalbumlist2/)、[专辑详情](https://opensubsonic.netlify.app/docs/endpoints/getalbum/)、[搜索](https://opensubsonic.netlify.app/docs/endpoints/search3/)、[歌曲详情](https://opensubsonic.netlify.app/docs/endpoints/getsong/)。后续接口依据：[随机歌曲](https://opensubsonic.netlify.app/docs/endpoints/getrandomsongs/)、[收藏](https://opensubsonic.netlify.app/docs/endpoints/getstarred2/)、[歌单更新](https://opensubsonic.netlify.app/docs/endpoints/updateplaylist/)、[播放上报](https://opensubsonic.netlify.app/docs/endpoints/scrobble/)。

### 4.1 分页和搜索

当前页长为 30，专辑目录使用 `getAlbumList2(type=alphabeticalByName)`。专辑详情由 `getAlbum` 一次返回曲目，不假定支持 songOffset。

| 场景 | 方法和分页参数 | 页面行为 |
| --- | --- | --- |
| 专辑目录 | getAlbumList2，size=30、offset | 按名称浏览专辑 |
| 专辑搜索 | search3，query、albumCount=30、albumOffset；songCount=0、artistCount=0 | 按专辑或歌手查询，结果保留为专辑 |
| 单曲目录/搜索 | search3，query（目录为空串）、songCount=30、songOffset；albumCount=0、artistCount=0 | 按服务器索引查询歌曲，空词枚举曲库 |
| 专辑详情筛选 | 无新增网络请求 | 对已取得的当前专辑曲目按名称/歌手/专辑文本即时筛选 |

[OpenSubsonic search3](https://opensubsonic.netlify.app/docs/endpoints/search3/) 要求支持空 query 返回曲库数据；仅支持基础 Subsonic 的服务器是否遵循此行为需按实际响应确认。客户端不将当前几页的本地过滤当作全库搜索。

offset 按服务器返回条数递增，显示内容按完整身份去重；短页结束，满页可能再请求一次空页。服务器扫描更新时不承诺跨页快照一致性，可刷新重新加载。各服务器、分类、关键词分别保存状态；切分类保留已提交关键词，进入专辑清空详情筛选，返回恢复父列表和位置。

保持上游搜索顺序，不增加客户端相关性重排。综合搜索、歌手页和探索分页仍是后续设计；一服失败不影响其他服的聚合策略尚未实现。

### 4.2 歌曲映射

| 上游字段 | 客户端字段 | 规则 |
| --- | --- | --- |
| id | audioId、mediaUri | 原始 ID 可逆编码；profileId 参与命名空间 |
| title | SongItem.name | 缺失以原始 ID 兜底 |
| artist | artist | 原样保留；不强行拆分人名 |
| album | album | 真实专辑名，不用它承载平台标签 |
| albumId | SongItem.albumId 的 Long 代理值 | 不能用于服务器 API 请求；专辑浏览/详情使用 ServerAlbum.id 的原始字符串，歌曲模型尚未保存可逆专辑引用 |
| duration | durationMs | 当前 optLong 读取整秒，限制在 0..604800 后乘 1000；缺失为 0，不保留小数秒 |
| coverArt | coverUrl | 按原始封面 ID 创建内部引用，不能默认等于歌曲或专辑 ID |
| contentType、suffix、bitRate 等 | PlaybackAudioInfo | 来自服务器声明，不是客户端解码实测；bitRate 为 kbps，samplingRate 为 Hz，另读 bitDepth/channelCount/size |

服务器歌曲 ID 与其他平台数值 ID 没有关联。[Navidrome 接口差异](https://www.navidrome.org/docs/developers/subsonic-api/)、[Child 响应字段](https://opensubsonic.netlify.app/docs/responses/child/)

列表曲目映射也会填入音频元数据缓存；5 分钟内 playback 可能直接使用它而不发 getSong，forceRefresh 才明确绕过。MIME 优先读取以 audio/ 开头的 contentType，否则按 suffix 做有限回退；MP4/M4A 仅标容器，不断言 AAC 或 ALAC。元数据缓存的 representation 描述与媒体字节 cacheKey 是不同字段，不等于已经解决实例变更隔离。

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

当前复用现有 Media3 / Coil 的共享 OkHttp，向播放器和图片组件提供内部地址：

```text
https://neri-server.invalid/v1/<profileId>/stream/<encodedSongId>
https://neri-server.invalid/v1/<profileId>/cover/<encodedCoverId>
```

`.invalid` 地址不是服务器 API 或代理服务；专用拦截器只识别这个保留域，在客户端内部解析为当前配置的请求并补认证。解析失败就中止，不能访问该保留域。其他图片和平台请求不附带服务器凭据。返回给调用方的 request 仍为无凭据内部引用，队列恢复使用同一机制。

当前音频表示固定 raw，缓存键 `subsonic:<audioId>:raw:v1`；封面固定 600 像素，图片引用包含配置、封面 ID 和引用版本。后续增加可变尺寸或转码时必须把尺寸/格式/码率/表示版本加入缓存身份。同一 ID 内容原地替换后的主动缓存失效属于后续待办。

### 5.2 流与失败

stream 当前指定 `format=raw`，不调用 download 接口。拖动依赖 Media3、文件容器和服务器 Range 支持；转码音频的 timeOffset 需要对应扩展，不应承诺任意服务器都能转码精确拖动。媒体请求由播放器自身的加载与取消机制管理。[stream](https://opensubsonic.netlify.app/docs/endpoints/stream/)

stream / getCoverArt 成功是二进制，失败可能返回 XML/JSON，即使请求了 f=json 也不能直接交给解码器。当前检查 HTTP 状态及错误 Content-Type；无法提前识别的错误字节由媒体解码器拒绝。封面失败使用占位图，不影响音频。服务端 coverArt 字段为空时不发图片请求。[getCoverArt](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/)

移除配置后新解析会失败；已缓冲内容可能仍能播放，删除配置不等于清除媒体缓存或远程删歌。按配置清除媒体缓存仍是待办。

### 5.3 缓存与请求复用

| 内容 | 实际设施与身份 | 生命周期 / 限制 |
| --- | --- | --- |
| 音频 | 原有 Media3 播放缓存，完整引用 + raw 表示 | 普通播放和预取共用；认证 salt 不改变缓存键 |
| 图片 | 原有 Coil/系统封面链路，配置 + coverArt ID + 引用版本 | 当前请求固定 600 像素 |
| 曲库 | SubsonicBrowseCache + 原有 PlatformPlaylistCacheRoomStore | 内存最多 64 页/20000 项；持久快照最多 128 条记录/20000 项；60 秒内复用，过期刷新 |
| 搜索 | 与浏览共用内存缓存及共享请求 | 专辑/歌曲搜索不落盘；根单曲目录、根专辑目录和专辑曲目落盘 |
| 音频元数据 | getSong / 曲目映射的短期缓存 | 最多 256 项，5 分钟，按配置 revision；forceRefresh 绕过 |
| 歌词 | SubsonicLyricsCache，完整歌曲引用 + revision | 仅非空成功结果，最多 32 首，10 分钟；尚无歌词并发请求复用 |
| 扩展能力 | lyricVersions，profileId + revision | 仅进程内，配置更新失效；TTL/手动刷新待办 |

浏览键包含 profileId、revision、kind、query/albumId、offset、size。同键请求共享任务，force 绕过新鲜缓存但仍可加入同键正在进行的任务；取消一个订阅者不影响其他订阅者，最后一个离开才取消。刷新失败不覆盖有效快照，存储失败不阻断在线访问。配置更新/移除通过账号流异步清理旧修订快照和任务；请求前后另校验修订号，清空缓存后旧任务不回写。

上述容量均为当前仓库/缓存实例的总预算，不是每个服务器独享的额度；Room 128 条/20000 项预算以 platform=subsonic 汇总，专辑目录中的一张专辑也算一项。歌词、元数据及能力缓存仅进程内，不能当成离线持久数据。

页面先展示缓存快照再按新鲜度刷新。应用脱机开关与资源库公共刷新入口尚未统一接入；服务器页自己的刷新已可用。缓存快照不提供未访问内容，音频缓存不等于持久离线下载。

下一首预取在非随机、非单曲循环、下一首目标仍匹配、非计费网络、缓存可用、播放器 READY 且正播放、缓冲至少 5 秒（或已覆盖剩余短尾）时启动；列表循环可以预取回到首曲。最大取 1.5 MiB，按已知文件长度与缓存预算的 1/8 缩小；总任务预算 30 秒，媒体读取预算 10 秒，保留 1 MiB 可用空间。切歌、暂停、网络/配置变化或当前播放争用缓存时取消/让出，不等待播放持有的缓存锁。小文件可能完整落入预算，但不保证整曲缓存或持久离线可播；日志 read 字节也不等于全部新增网络下载或新增缓存写入。

HTTP 416 保留原始响应头交给 Media3，供其区分文件末尾与读取越界；真正越界和短读仍保留错误信号。服务器瞬时传输错误有限重试，失败后保留当前曲目/进度供手动重试；不承诺网络恢复自动续播或跨源替换。

## 6. 歌词

先查 `songLyrics` 扩展；当前只要求最大公布版本 >=1，未做独立 v1/v2 协商。请求为 `getLyricsBySongId(id)`，不发送 enhanced=true，读取 v1 兼容的 line 数据；v2 的 cueLine/kind/agents 不消费。不将 Long 代理 ID 发给歌词接口。[端点](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)、[扩展版本](https://opensubsonic.netlify.app/docs/extensions/songlyrics/)

示意响应的业务部分：

```json
{"lyricsList":{"structuredLyrics":[{"lang":"zho","synced":true,"offset":100,"line":[{"start":1000,"value":"第一行"},{"start":3500,"value":"第二行"}]}]}}
```

- start 和 offset 单位毫秒。正 offset 表示提前，展示起点为 `max(0, start - offset)`；当前 offset 限制为 ±86400000，start 限制为 0..604800000，负 start 的行不参与展示。
- 当前优先第一份可解析的非空 synced 歌词，否则尝试普通歌词；保留各组内的上游顺序，不把另一语言自动当翻译。多语言选择及来源状态后续接入。
- 按起点排序，以后续更大的起点作为行终点；没有更晚起点时，使用大于起点的有效歌曲时长，否则暂取起点后 5 秒。
- synced=false 使用已有普通歌词转换器；人为分配的显示时间不是服务端准确时间轴。
- 空集合是无歌词；不支持是能力缺失；超时是错误。播放器对不可展示结果降级，独立状态 UI 后续接入。
- 尊重手动保存和现有明确歌词源偏好；没有明确偏好时读取服务器歌词。其他来源的手动匹配仍走既有入口，不改写曲库文件。
- 翻译/罗马音只有真实保存或匹配结果才展示，不以服务器歌曲代理 ID 请求网易云。

offset 和结构依据：[structuredLyrics](https://opensubsonic.netlify.app/docs/responses/structuredlyrics/)。

## 7. 推荐、标签和上游扩展

本节均为后续设计提案，尚未接入当前媒体库或探索页面。本文中的匹配策略及可选契约不属于首个 PR 的实现承诺。

### 7.1 发现与匹配

后续计划提供 `newest`（最近添加）、`frequent`（常听）、`random`（随机）栏目，保持实际语义。基础协议不能保证个性化推荐；能力不支持时不显示“为你推荐”。随机结果只做本次展示内去重，不伪造连续游标。

综合匹配拟先覆盖已启用服务器，第三方平台是否参加需在 issue 另行确认。后续拟复用现有文本匹配能力：歌名和歌手为主，热度作为相似文本候选的次级顺序；不同服务器原始播放次数不直接比较。保留来源和版本差异，不自动把失败曲目换成另一个来源。若采用该策略，只对已返回候选排序，不保证全库全局最优。当前没有聚合器或这一排序实现，不是已确认规范。

### 7.2 客户端可选契约（后续提案）

```text
getServerMetadata(profileId) -> Supported(ServerMetadata) | Unsupported | Failure
ServerMetadata = { label?: string, iconRef?: ResourceRef, revision?: string }

getDiscovery(profileId, kind, page) -> DiscoverySection | Unsupported | Failure
DiscoverySection = { kind, title, albums: [...], songs: [...], nextPage? }
kind = RECENTLY_ADDED | FREQUENT | RANDOM | PERSONALIZED
```

`ping.type/serverVersion` 仅用于软件识别，`getAvatar` 是用户头像；都不能充当实例自定义标签和品牌图标。当前尚无 `getServerMetadata` 方法；该提案的基础协议回退应为 Unsupported，UI 继续使用用户配置或默认值。

上游规范扩展需另开版本化议题，先确定扩展名称、发现方式、endpoint、认证、字段上限、缓存和失败回退。目前没有可调用的 `/metadata` 或 `/recommendations` 契约，基础接入独立于个性化推荐能力。

## 8. 收藏、同步、下载与上传

- 本地收藏和服务器 star 是两份状态；当前本地收藏按钮不映射为服务端写操作。
- 更新歌单可能非幂等；失败后先读取实际结果再让用户重试，不能对新增歌曲盲目自动重试。需要处理只读权限、重复歌曲和顺序变化。
- stream 本身不等于 scrobble。后续单独决定播放上报触发点、重复事件抑制和离线补报；当前只沿用客户端本地统计。[Navidrome 说明](https://www.navidrome.org/docs/developers/subsonic-api/)
- 服务器配置和密码不进入 GitHub/WebDAV 普通同步。当前已有歌单/历史同步可能携带无凭据的歌曲引用；另一设备没有相同配置 ID 时不可播放，不自动通过同名服务器重绑定。
- 当前保留已有元数据同步映射中的条目，避免过滤混合歌单时造成删除；跨设备可播放配置迁移仍未实现。混合版本客户端、删除标记和身份/共享往返的专项回归安排在后续多服务器阶段，不作为已验收结论。
- 当前不支持永久下载与一起听，按钮显示禁用原因，实际操作路径也拒绝，不导出认证直链。
- 音频上传不在采用的基础协议内，当前未接入。后续需独立定义上传会话、权限、大小/格式限制、分片/续传、校验和、冲突策略、最终提交和取消；不得用客户端 WebDAV 设置冒充 Navidrome 上传 API。

## 9. 当前客户端仓库契约

实际方法位于 [SubsonicRepository](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt)，客户端未实现一个新的全平台公共 Provider 接口。

| 方法 | 输入 | 输出 / 行为 |
| --- | --- | --- |
| addAccount | label、address、username、password | ping 成功后保存；失败不保存 |
| updateAccount | original profile、label、address、password | 空密码沿用原密码；ping 成功后保留 ID、增加 revision，检查并发修改 |
| browseKey / browse | profileId、albumId/query、category、offset、size；force | ServerBrowseKey / ServerBrowsePage；绑定配置 revision，支持缓存及强制刷新 |
| albums | profileId、offset、size=30 | List<ServerAlbum>，根专辑目录 |
| albumSongs | profileId、albumId | List<SongItem>，专辑完整曲目 |
| search | profileId、query、offset、size=30 | List<SongItem>；保留兼容歌曲搜索入口，空词为单曲目录 |
| song | ServerSongRef | SongItem，getSong 映射 |
| playback | SongItem、forceRefresh=false | 现有 SongUrlResult，内部 URL、服务器声明的音频信息、长度、表示和缓存键 |
| cachedLyrics / lyrics | SongItem | 缓存读取 / List<LyricEntry>，以配置 revision 隔离 |

专辑搜索由 browse(category=ALBUMS, query 非空) 选择；单曲列表/搜索由 category=SONGS 选择。配置移除使用 accounts.remove；歌词成功缓存不代表歌词请求合并。

调用顺序：browseKey/albums/albumSongs/search 依赖已加载的账号快照，页面 ViewModel 先 accounts.load；playback/lyrics 自行先加载账号。profile 和 cachedLyrics 是内存读取；credentials 是阻塞加密存储读取，只供 IO worker/拦截器调用。cachedLyrics 无有效账号、缓存或修订匹配时返回 null；lyrics 无可用扩展/歌词时可返回空列表，传输错误仍抛异常。playback 的非法引用返回 Failure，缺少有效配置返回 RequiresLogin；播放器外层另校验账号并转换为安全用户提示。

### 9.1 后续修改的约束

以下是维护这套接入时的规则；已有例外和待修复项以上文实际行为为准。

- 字符串 resourceId 原样保存，API 请求使用原 ID；数字代理只适配既有模型。配置修订与媒体表示不能随意改变歌单/历史的稳定歌曲身份。
- 账号操作通过仓库先验证再保存；修改成功后才发布状态。credentials 不在 UI 主线程读取，外部取消继续传播。
- 无凭据引用留在模型/缓存，认证 URL 留在传输边界；错误类不得保存服务端原文、完整请求地址或底层带凭据 cause。新增公开元数据不得包含 password/token/salt。
- 共享组件新增参数保持原调用默认行为；公共来源分支同时核对原平台、下载/共享和历史恢复，不能只测服务器 UI。
- 新端点先在本文区分当前调用与后续提案，明确参数/单位、结果、失败、能力依赖、缓存和测试。更严格的 schema、认证窄接口和实例变更缓存隔离尚未完成，不作为已有保证。

后续增量包括能力 TTL/刷新、独立歌词状态、发现/聚合搜索、管理图标/停用/排序、服务端写操作和配置迁移。只在真实调用方需要时引入小接口。

## 10. 验收入口与证据范围

协议 URL、受控请求/解析、缓存、身份、预取及 HTTP 416 测试与真机记录统一见[验收总报告](music-server-acceptance.md)。测试覆盖与实测结论分别记录；不能把构建成功等同于全部协议和设备场景通过。

基础真机闭环按用户反馈已完成。多服务器同 ID、身份持久化往返、混合歌单同步/共享、完整弱网与取消场景的专项回归见[开发计划](music-server-development-plan.md)，不反过来扩展首个 PR 的全部前置功能范围。
