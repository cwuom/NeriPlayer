# 音乐服务器 API 与客户端契约

> [!WARNING]
> 自建音乐服务器功能目前为预览版本，缺乏充分测试。不同服务器、曲库和网络环境下的兼容性与稳定性仍需验证。

本文记录 Navidrome / OpenSubsonic 接入的实际 HTTP 请求、模型映射与客户端仓库约定。基础参数使用 Subsonic 1.16.1，优先读取可选的 `songLyrics` 扩展，并兼容标准歌词接口；不声明完整实现 OpenSubsonic。用户操作见 [README](../README.md#连接音乐服务器)，核心文件职责见 [平台模块说明](../modules/platform/README.md#音乐服务器接入--music-server-integration)。

## 1. 分层与术语

- **HTTP API**：音乐服务器实际提供的接口，Navidrome 适配使用 `/rest/<method>.view`。
- **客户端仓库契约**：`:platform` 将协议响应转换成音理的 `SongItem`、`SongUrlResult`、`LyricEntry`；页面和播放器不拼接认证 URL。
- **profileId**：本机一个服务器账号配置的 UUID。同一地址的两个账号拥有不同 ID。
- **resourceId**：服务器返回的原始字符串，区分大小写，禁止转为 Long 后替代原值。

现有网易云、Bilibili、YouTube Music 客户端保持各自登录和仓库。新增适配器通过 `AppContainer` 和已有 playback host 注入；本规范不要求重建统一平台框架。

## 2. 连接与认证

### 2.1 配置

| 当前字段 | 类型 | 规则 |
| --- | --- | --- |
| id（profileId） | UUID string | 创建时生成；编辑名称或密码时保留；移除后重加生成新 ID |
| label | string | 用户输入优先，空白回退主机名 |
| baseUrl | HTTP(S) URL | 包含端口和反代子路径，尾部 `/`；不含 userinfo、query、fragment |
| username | string | 去首尾空白且非空；编辑界面不修改用户名，更换账号应新建配置 |
| enabled | boolean | 模型已保留，当前界面尚无停用开关 |
| revision | Long | 新建为 0，更新配置时递增；绑定请求结果与缓存有效性 |

`SubsonicProfile` 不携带密码；配置和密码保存于独立的 `EncryptedSharedPreferences`，凭据读取仅用于 IO 请求。当前没有公开的 credentialRef、图标、上游品牌信息或排序字段。protocol 固定为 Subsonic，未提供协议选择 UI。

添加和更新通过仓库先 `ping` 成功再保存；底层 accounts.save 本身不执行连接检查。更新使用 expectedRevision 检查并发变更，成功后清理该配置的扩展能力、歌词及音频元数据缓存；浏览缓存按 revision 隔离，并由账号流异步清理旧快照/任务。移除只作用于本机配置：浏览缓存订阅账号流清理旧条目，歌词/元数据/能力的进程内条目没有统一删除入口，但缺少配置时不会作为有效账号结果读取。

配置地址与用户名创建后不可更改：编辑界面禁用这两项，仓库更新和账号存储也拒绝同一 ID 下改变地址或用户名。更换地址或账号需新增配置，即使是同一实例的另一反代地址，也使用新 UUID；原歌单/历史引用不会自动重绑定。这使媒体字节、预取、封面与旧引用均保持原配置命名空间，避免不同实例同资源 ID 串用缓存。媒体字节缓存和图片引用不含 revision，同一地址下同 ID 资源原地替换的主动失效仍未实现。

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

当前不支持 API Key 与表单 POST；不得把 API Key 认证参数与用户名/token 方式混用。[公共 API 规范](https://opensubsonic.netlify.app/docs/api-reference/)

### 2.3 连接过程

1. 本地校验 URL 和必填项。
2. 使用候选配置请求 `ping`，HTTP 和业务状态都成功后才保存。
3. `getOpenSubsonicExtensions` 查询可选扩展。当前在首次读取歌词时按需发现。
4. 加载专辑或执行用户搜索；`ping` 只确认连接，曲库读取需对应权限，媒体播放还取决于格式与设备。
5. 认证失败提示重新登录，不循环重试密码，不替换为另一个配置。

扩展名与版本以返回值为准；未知扩展忽略。`songLyrics` 缺失表示不能按歌曲 ID 读取结构化歌词。当前扩展查询遇到协议错误 code=0/70 或 HTTP 404/501 时回退为不支持，其他错误继续向上传播；code=0 是通用错误，在此仅按可选能力缺失处理。能力缓存绑定配置修订号，尚未接入服务器版本/TTL 失效或手动刷新；发现失败的细分与刷新属于后续完善。[能力发现](https://opensubsonic.netlify.app/docs/endpoints/getopensubsonicextensions/)

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
| AUTHENTICATION | HTTP 401；协议 40、44 | 凭据不可用，不自动切换认证方式 |
| FORBIDDEN | HTTP 403；协议 50 | 权限不足 |
| NOT_FOUND | HTTP 404；协议 70 | 通用资源/接口不存在提示，不细分歌曲、路径或扩展 |
| UNSUPPORTED | 协议 20、30；41、42、43 | 分别提示协议或认证方式不兼容，不自动降级认证 |
| RATE_LIMITED / SERVER | HTTP 429 / 5xx | 标记 retryable；保留 Retry-After 上限 24 小时，不意味着等待满该时长 |
| TIMEOUT / NETWORK / TLS | 超时 / 其他 IO / SSLException | TLS 不重试；网络和超时标记 retryable |
| INVALID_RESPONSE | 无法解析根 JSON、状态失败且错误码未知、响应超限 | 安全错误；缺失的具体列表字段不一定产生错误，见下文 |
| ACCOUNT_UNAVAILABLE / CONFIG_CHANGED | 配置缺失/不可用/请求修订失配；并发编辑冲突 | 当前请求停止或提示检查配置 |
| CancellationException（非 kind） | 外部取消 | 传播取消，不显示为普通网络失败 |
| 空列表（非 kind） | 正常空集合，或部分缺失列表字段 | 当前页面按空结果展示 |

当前对外使用 `SubsonicException : IOException`，保留 kind、code、httpStatus、retryAfterMs 与 retryable；HTTP 状态与协议错误可通过 httpStatus 区分，未引入公共 Result 层。页面使用本地化安全提示；取消向上传播。播放器对歌词读取失败进行降级，不中断音频；“无歌词 / 不支持 / 读取失败”的独立 UI 状态仍待后续。

协议 code=40 表示凭据错误，code=41 特指 LDAP 用户不支持 token 认证。客户端使用 username + token/salt；41/42/43 归为认证方式不支持并提示检查服务器认证设置，不误报密码错误。code=44 表示无效 API Key，归为认证失败。当前不新增明文或 API Key 登录，也不读取或展示 helpUrl。[错误码定义](https://opensubsonic.netlify.app/docs/responses/error/)

目录、专辑详情和兼容搜索检查响应容器、数组类型和条目字符串 ID，异常不覆盖已有完整快照；容器中的列表字段缺失或为 null 仍可表示空集合。getAlbum、getArtist、getSong 还核对返回对象的原始字符串 ID 是否与请求一致，缺失、类型错误或身份不匹配均返回安全的 INVALID_RESPONSE，不保存错误歌曲的音频元数据。

retryable 只是分类。JSON client 没有额外的业务重试循环，共享 OkHttp 自身的连接恢复仍按其配置运行；媒体加载由 ServerMediaLoadErrorPolicy 对连续错误最多允许 3 次重试，超过 30 秒的 Retry-After 不在 loader 内等待；不存在跨播放会话的总重试预算。

## 4. 端点映射

下表列出本接入所需参数；每个请求还需要第 2 节的公共认证参数。协议的更多可选参数以链接文档为准。

| 方法 | 参数 | 成功数据路径 | 客户端职责 |
| --- | --- | --- | --- |
| ping | 无 | 响应根 | 连接检查 |
| getOpenSubsonicExtensions | 无 | openSubsonicExtensions[] | name、versions[] |
| getAlbumList2 | type、size、offset | albumList2.album[] | 专辑分页 |
| getAlbum | id | album，album.song[] | 专辑和完整曲目列表 |
| getArtists | 无 | artists.index[].artist[] | 按 ID3 歌手分类，保留原始歌手 ID |
| getArtist | id | artist，artist.album[] | 指定歌手的专辑，不预取各专辑的歌曲 |
| search3 | query；专辑：albumCount/albumOffset；单曲：songCount/songOffset；歌手：artistCount/artistOffset；其余两类 count=0 | searchResult3.album[]、song[] 或 artist[] | 保留分页仓库入口；页面的单曲目录使用空 query |
| getSong | id | song | 读取服务器声明的音频元数据；forceRefresh 绕过进程缓存 |
| stream | id、format=raw | 二进制音频 | Media3 在线播放 |
| download | id、format=raw | 二进制音频 | 原文件下载，遵循服务器下载权限 |
| getCoverArt | id、size | 二进制图片 | Coil 与系统封面 |
| getLyricsBySongId | id | lyricsList.structuredLyrics[] | 需已知 songLyrics 版本，逐行/普通歌词 |
| getLyrics | artist、title（省略空字段） | lyrics.value | 不支持结构化歌词时的标准文本/LRC 回退 |

主接口依据：[专辑列表](https://opensubsonic.netlify.app/docs/endpoints/getalbumlist2/)、[专辑详情](https://opensubsonic.netlify.app/docs/endpoints/getalbum/)、[搜索](https://opensubsonic.netlify.app/docs/endpoints/search3/)、[歌曲详情](https://opensubsonic.netlify.app/docs/endpoints/getsong/)。

### 4.1 分页和搜索

页面默认自动加载完整元数据目录，专辑和单曲以 500 条为一页遍历；兼容分页仓库入口仍默认 30 条。专辑目录使用 `getAlbumList2(type=alphabeticalByName)`。专辑详情由 `getAlbum` 一次返回曲目，不假定支持 songOffset。

| 场景 | 方法和分页参数 | 页面行为 |
| --- | --- | --- |
| 专辑目录 | getAlbumList2，size=500、offset | 自动遍历全部页，完成后保存完整快照 |
| 单曲目录 | search3，query 为空串、songCount=500、songOffset；albumCount=0、artistCount=0 | 自动遍历全部页，完成后保存完整快照 |
| 歌手目录 | getArtists | 保存完整 ID3 歌手列表，同名不同 ID 不合并 |
| 歌手专辑 | getArtist，id 为原始歌手 ID | 按需加载专辑元数据，点击专辑后使用共享歌单详情 |
| 分类内搜索 | 无额外搜索请求 | 对完整目录按名称、歌手或专辑文本筛选，歌手分类按名称筛选 |
| 专辑详情筛选 | 无新增网络请求 | 对已取得的当前专辑曲目按名称/歌手/专辑文本即时筛选 |

[OpenSubsonic search3](https://opensubsonic.netlify.app/docs/endpoints/search3/) 要求支持空 query 返回曲库数据；仅支持基础 Subsonic 的服务器是否遵循此行为需按实际响应确认。分类本地搜索使用完整目录，不把扫描中尚未完成的部分页面作为完整结果。歌手接口见 [getArtists](https://opensubsonic.netlify.app/docs/endpoints/getartists/) 与 [getArtist](https://opensubsonic.netlify.app/docs/endpoints/getartist/)。

offset 按服务器返回条数递增，显示内容按完整身份去重；短页结束，满页可能再请求一次空页。服务器扫描更新可能改变分页结果，刷新可重新加载。各服务器、分类、关键词分别保存状态；切分类保留已提交关键词，进入专辑清空详情筛选，返回恢复父列表和位置。

本地筛选保留目录顺序，不增加相关性重排。跨服务器综合搜索尚未实现。服务器不提供可靠的全库变更校验标记，客户端不使用文件系统索引的修改时间推断全部 ID3 元数据未变化。

### 4.2 歌曲映射

| 上游字段 | 客户端字段 | 规则 |
| --- | --- | --- |
| id | audioId、mediaUri | 原始 ID 可逆编码；profileId 参与命名空间 |
| title | SongItem.name | 缺失以原始 ID 兜底 |
| artist | artist | 原样保留；不强行拆分人名 |
| album | album | 真实专辑名，不用它承载平台标签 |
| albumId | SongItem.albumId 的 Long 代理值 | 不能用于服务器 API 请求；专辑浏览/详情使用 ServerAlbum.id 的原始字符串，歌曲模型尚未保存可逆专辑引用 |
| duration | durationMs | 当前 optLong 读取整秒，限制在 0..604800 后乘 1000；缺失为 0，不保留小数秒 |
| coverArt | coverUrl | 按原始 `coverArt` ID 构造带 `neri-cover=v1:<scope>` 来源标记的真实 `getCoverArt` URL，不能默认用歌曲或专辑 ID 替代 |
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

当前复用现有 Media3 / Coil / 下载器的共享 OkHttp。播放和下载继续使用内部地址，保持已有歌曲、缓存和下载身份：

```text
https://neri-server.invalid/v1/<profileId>/stream/<encodedSongId>
https://neri-server.invalid/v1/<profileId>/download/<encodedSongId>
```

`.invalid` 地址不是服务器 API 或代理服务；专用拦截器在客户端内部解析为当前配置的请求并补认证。解析失败就中止，不能访问该保留域。返回给调用方的 request 仍为无凭据引用，队列恢复使用同一机制。

新封面使用真实服务器端点，原始 coverArt ID 仍由 URL builder 编码：

```text
https://music.example.com/navidrome/rest/getCoverArt.view?id=<coverArtId>&size=600#neri-cover=v1:<scope>
scope = lowercaseHex(SHA-256(UTF8(normalizedBaseUrl + "\n" + username.trim())))
```

normalizedBaseUrl 使用与服务器配置相同的 HTTP URL 规范化，保留端口和反代子路径，并以单个斜杠结尾。scope 是稳定账号指纹，不是登录凭据；同地址同用户名在不同设备生成的配置 UUID 不同，也能匹配同一封面来源。用户名大小写不折叠，不按歌曲 ID、域名或唯一账号猜测映射。

fragment 是客户端来源标记，不发送到服务器。解析仅接受 v1 标记、64 位小写十六进制 scope、精确的 rest/getCoverArt.view 路径，以及各出现一次的 id 和 size=600 参数。拦截器只向精确地址和 scope 均匹配的本机已启用账号请求补认证；未标记的普通图片不会自动附加服务器认证。密码、u/p/t/s、API Key 等认证参数不保存在封面模型或同步载荷中，网络请求仍按当前账号即时签名；返回的 response.request 不携带认证参数。

旧 https://neri-server.invalid/v1/<profileId>/cover/<encodedCoverId> 仍可加载。Android 在后台准备账号后，导出同步时将本机能解析的旧封面引用投影为真实端点，不额外下载图片、不改原歌曲身份；无法解析的旧引用保留，不能据此推断未知服务器或账号。相同本机账号下，新链接可读取旧磁盘封面缓存，保留原有版本和尺寸键；缓存清理和估算也按同一映射保留图片，不需要重新下载。

同步继续使用既有的 `coverUrl` / `customCoverUrl` / `originalCoverUrl` 字段及 JSON、Protobuf 和 V4 归档结构，收藏歌单、历史和播放统计封面遵循同一投影规则。认证凭据不随同步迁移，另一台 Android 设备需自行添加相同的服务器地址与用户名；服务器歌曲的 `audioId` / `mediaUri` 仍绑定原配置的 profile UUID，跨设备播放、歌词和下载不会自动重绑账号。桌面端仅保留这些不含凭据的封面 URL，尚未实现服务器封面加载。

当前音频表示固定 raw，缓存键 `subsonic:<audioId>:raw:v1`；封面固定 600 像素，新图片引用包含真实端点、封面 ID、尺寸和账号 scope。后续增加可变尺寸或转码时必须把尺寸/格式/码率/表示版本加入缓存身份。同一 ID 内容原地替换后的主动缓存失效属于后续待办。

### 5.2 流与失败

播放使用 `stream(format=raw)`，本地下载使用 `download(format=raw)`，均读取原文件。显式指定 raw 避免 Navidrome 自动下载转码设置改变文件格式；服务器禁用下载或账号无权限时保留权限错误，不回退播放接口。拖动和续传依赖文件容器与服务器 Range 支持；转码音频的 timeOffset 需要对应扩展，不应承诺任意服务器都能转码精确拖动。播放请求由播放器管理，下载请求由现有下载队列管理。[stream](https://opensubsonic.netlify.app/docs/endpoints/stream/)、[download](https://opensubsonic.netlify.app/docs/endpoints/download/)、[Navidrome 下载实现](https://github.com/navidrome/navidrome/blob/v0.64.2/server/subsonic/stream.go)

stream / download / getCoverArt 成功是二进制，失败可能返回 XML/JSON，即使请求了 f=json 也不能直接交给解码器或保存为音频。当前检查 HTTP 状态及错误 Content-Type；无法提前识别的错误字节由媒体解码器拒绝。音频 HTTP 416 保留 Content-Range 与无凭据请求，交给播放器判断文件末尾或下载器重新开始传输。封面失败不影响音频，专辑和歌曲缺少 coverArt 时使用占位图。[getCoverArt](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/)

歌手头像优先使用服务端 coverArt，其次使用有效的公开 HTTP(S) artistImageUrl；服务器头像缺失或加载失败时，使用完整单曲目录中该歌手最后一首有封面歌曲的图片，仍无图则显示占位图。关联使用原始 artistId/artists/albumArtistId/albumArtists，不按同名推断。头像索引随单曲快照保存，已发现的详情头像也可离线复用；补头像不逐个预取专辑。外链头像不附带服务器凭据，带 userinfo、认证参数或保留资源域的外链不会作为公开图片保存。[歌手字段](https://opensubsonic.netlify.app/docs/responses/artistid3/)

移除配置后新的在线解析会失败；已下载的受管本地文件优先解析，不依赖服务器配置。已缓冲内容可能仍能播放，删除配置不等于清除媒体缓存或远程删歌。按配置清除媒体缓存仍是待办。

### 5.3 缓存与请求复用

| 内容 | 实际设施与身份 | 生命周期 / 限制 |
| --- | --- | --- |
| 音频 | 原有 Media3 播放缓存，完整引用 + raw 表示 | 普通播放和预取共用；认证 salt 不改变缓存键 |
| 图片 | 原有 Coil/系统封面链路，配置 + coverArt ID + 引用版本 | 当前请求固定 600 像素 |
| 完整曲库 | SubsonicBrowseCache + 原有 PlatformPlaylistCacheRoomStore | 按 profile/revision 保存专辑、单曲、歌手三类完整快照，24 小时复用；全部页成功后才原子替换，不受普通分页预算淘汰 |
| 普通分页与详情 | 同一缓存与 Room 存储 | 普通内存页最多 64 页/20000 项；普通持久记录最多 128 条/20000 项；分页与专辑曲目 60 秒，歌手专辑 24 小时 |
| 搜索 | 页面筛选完整目录；兼容仓库分页搜索保留 | 页面搜索不发额外请求；兼容搜索页只存内存 |
| 音频元数据 | getSong / 曲目映射的短期缓存 | 最多 256 项，5 分钟，按配置 revision；forceRefresh 绕过 |
| 歌词 | SubsonicLyricsCache，完整歌曲引用 + revision | 最多 32 首；非空成功结果 10 分钟，确定的空结果 60 秒；异常不缓存，空结果不抹除有效歌词；尚无歌词并发请求复用 |
| 扩展能力 | lyricVersions，profileId + revision | 仅进程内，配置更新失效；TTL/手动刷新待办 |

专辑页面先显示同配置、同修订的缓存曲目，再检查有效期并刷新。冷启动后即使缓存已过期，断网或刷新失败也保留已有曲目并显示安全错误；切换账号、专辑或配置修订时，晚到的旧缓存与请求结果不能覆盖当前页面。浏览快照只提供曲目信息，实际离线播放仍需已下载文件或音频缓存。

浏览键包含 profileId、revision、kind、query/albumId、offset、size。同键请求共享任务，force 绕过新鲜缓存但仍可加入同键正在进行的任务；取消一个订阅者不影响其他订阅者，最后一个离开才取消。刷新失败不覆盖有效快照，存储失败不阻断在线访问。配置更新/移除通过账号流异步清理旧修订快照和任务；请求前后另校验修订号，清空缓存后旧任务不回写。

普通分页容量是当前仓库/缓存实例的总预算，不是每个服务器独享的额度；完整目录按配置单独保留，清除服务器浏览缓存仍会一并清除。歌词、音频元数据及能力缓存仅进程内，不能当成离线持久数据。

页面先展示完整缓存快照再按新鲜度刷新，首次打开会预热其余两个分类。服务器页可手动刷新当前分类；刷新失败继续保留旧完整目录。扫描中已完成的短期分页可被普通重试复用；没有同键进行中的扫描时，手动强制刷新会重取所有页，在途同键请求仍按共享规则复用。应用脱机开关与资源库公共刷新入口尚未统一接入。全量目录只保存元数据，封面按需加载，音频缓存不等于持久离线下载。

下一首预取仅在非随机、非单曲循环、下一首目标仍匹配、非计费网络、缓存可用、播放器处于 `READY` 且正在播放、已缓冲至少 5 秒（或已覆盖剩余短尾）时启动；列表循环可预取首曲。单次上限为 1.5 MiB，并按已知文件长度与缓存预算的 1/8 缩减；总任务预算 30 秒，媒体读取预算 10 秒，同时保留 1 MiB 可用空间。发生切歌、暂停、网络或配置变化，或与当前播放争用缓存锁时取消/让出，不等待播放持有的锁。预取仅作有限缓存，持久离线播放请使用下载功能；日志中的 `read` 字节数包含缓存读取，不等于新增网络下载或新增缓存写入。

HTTP 416 保留原始响应头交给 Media3，供其区分文件末尾与读取越界；真正越界和短读仍保留错误信号。服务器瞬时传输错误会有限重试，仍失败时暂停并保留当前曲目与进度，网络恢复后需手动重试；不会自动跨源替换。

## 6. 歌词

先查 `songLyrics` 扩展，只接受已知版本 1 或 2；未知扩展版本不假定兼容。请求为 `getLyricsBySongId(id)`，不发送 enhanced=true，读取 v1 兼容的 line 数据；v2 的 cueLine/kind/agents 不消费。不将 Long 代理 ID 发给歌词接口。[端点](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)、[扩展版本](https://opensubsonic.netlify.app/docs/extensions/songlyrics/)

当服务器缺少已知扩展版本，或可选歌词端点返回 HTTP 404/501、Subsonic 协议错误 0/70 时，回退至[标准歌词](https://opensubsonic.netlify.app/docs/endpoints/getlyrics/)接口 `getLyrics(artist,title)`（缺失标签自动省略）。返回文本复用 LRC 解析器，无时间轴时按普通文本歌词展示；该接口按歌手与歌名检索，匹配精度可能与按 ID 检索的端点不同。若结构化歌词端点成功响应但返回空歌词，则不再请求标准接口。认证、权限、限流、网络、TLS、服务器错误及外部取消会继续向上传播；仅在此可选能力路径上对协议错误 0 执行回退，不将通用协议错误 0 泛化为“不支持”。

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

## 7. 本地操作与能力边界

- 收藏和添加歌单修改音理本地数据，不调用服务端 star/歌单写接口，也不上传播放统计。
- 普通 GitHub/WebDAV 同步可以携带无凭据歌曲引用，不携带服务器配置和密码；另一设备缺少相同配置 ID 时不可播放，不按同名服务器自动重绑定。
- 本地下载复用持久队列、工作文件、受管目录和 SAF 提交流程。每次来源解析重新读取 getSong 的原文件格式、长度和时长，传输时才认证；恢复信息不保存服务器密码或认证直链。同一歌曲 ID 以配置 ID 隔离，已下载文件不随配置移除。
- 下载遇到认证、权限、资源或配置失效时停止自动重试；临时错误进入现有重试流程。服务器返回的正值 Retry-After 会保留到网络恢复和进程重启之后，用户显式重试仍沿用原有恢复语义。音频已提交后的歌词或封面失败交给后处理恢复，不删除已提交音频。
- 一起听、探索综合搜索/发现、推荐、上传与配置迁移尚未接入；一起听仍在 UI 及实际操作路径拒绝服务器歌曲，不导出认证直链。

## 8. 客户端仓库契约

实际方法位于 [SubsonicRepository](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt)，客户端未实现一个新的全平台公共 Provider 接口。

| 方法 | 输入 | 输出 / 行为 |
| --- | --- | --- |
| addAccount | label、address、username、password | ping 成功后保存；失败不保存 |
| updateAccount | original profile、label、address、password | address 规范化后须与原地址一致；空密码沿用原密码；ping 成功后保留 ID、增加 revision，检查并发修改 |
| browseKey / browse | profileId、albumId/query、category、offset、size；force | ServerBrowseKey / ServerBrowsePage；绑定配置 revision，支持缓存及强制刷新 |
| catalogKey / catalog | profileId、category；force | 完整分类快照键 / ServerBrowsePage；24 小时复用，默认 500 条自动遍历 |
| artistAlbumsKey / artistAlbums | profileId、artistId；force | 歌手专辑快照键 / ServerBrowsePage；包含专辑与服务器歌手信息，缓存 24 小时 |
| cachedArtist / cachedArtists | 歌手摘要 / profileId、歌手摘要列表 | 只读缓存，合并服务器头像与歌曲封面；批量读取不逐歌手请求网络或 Room |
| albums | profileId、offset、size=30 | List<ServerAlbum>，根专辑目录 |
| albumSongs | profileId、albumId | List<SongItem>，专辑完整曲目 |
| search | profileId、query、offset、size=30 | List<SongItem>；保留兼容歌曲搜索入口，空词为单曲目录 |
| song | ServerSongRef | SongItem，getSong 映射 |
| playback | SongItem、forceRefresh=false | 现有 SongUrlResult，内部 URL、服务器声明的音频信息、长度、表示和缓存键 |
| download | SongItem | SubsonicDownloadSource；重新读取 getSong，校验歌曲 ID，返回无凭据 download 引用、原文件后缀、MIME、长度及毫秒时长 |
| cachedLyrics / lyrics | SongItem；lyrics 可指定 forceRefresh=false | 缓存读取 / List<LyricEntry>，以配置 revision 隔离，强制刷新绕过该歌曲缓存 |

页面使用 catalog 获取完整分类后本地筛选；browse 继续提供 ALBUMS、SONGS、ARTISTS 分类的兼容分页查询。配置移除使用 accounts.remove；歌词成功缓存不代表歌词请求合并。

调用顺序：曲库浏览与歌手专辑接口依赖已加载的账号快照，页面 ViewModel 先 accounts.load；playback/download/lyrics 自行先加载账号。profile 和 cachedLyrics 是内存读取；credentials 是阻塞加密存储读取，只供 IO worker/拦截器调用。cachedLyrics 无有效账号、缓存或修订匹配时返回 null；lyrics 无可用歌词时可返回空列表，传输错误仍抛异常。playback 的非法引用返回 Failure，缺少有效配置返回 RequiresLogin；播放器外层另校验账号并转换为安全用户提示。download 的非法引用、歌曲 ID 不匹配、缺少配置或配置修订变化均抛安全异常，不回退到其他平台。

### 8.1 维护约束

以下是维护这套接入时的规则；已有例外和待修复项以上文实际行为为准。

- 字符串 resourceId 原样保存，API 请求使用原 ID；数字代理只适配既有模型。配置修订与媒体表示不能随意改变歌单/历史的稳定歌曲身份。
- 账号操作通过仓库先验证再保存；修改成功后才发布状态。credentials 不在 UI 主线程读取，外部取消继续传播。
- 无凭据引用留在模型/缓存，认证 URL 留在传输边界；错误类不得保存服务端原文、完整请求地址或底层带凭据 cause。新增公开元数据不得包含 password/token/salt。
- 共享组件新增参数保持原调用默认行为；公共来源分支同时核对原平台、下载/共享和历史恢复，不能只测服务器 UI。
- 新端点同步记录实际参数/单位、结果、失败、能力依赖、缓存和测试。更严格的 schema、认证窄接口和实例变更缓存隔离尚未完成，不作为已有保证。
