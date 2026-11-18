# 音乐服务器 API 与客户端契约

> [!WARNING]
> 自建音乐服务器功能为预览版本，缺乏充分测试。不同服务器、曲库和网络环境下的兼容性与稳定性仍需验证。

本文记录 Navidrome / OpenSubsonic 接入的 HTTP 请求、模型映射与客户端仓库约定。基础参数使用 Subsonic 1.16.1，优先读取可选的 `songLyrics` 扩展，并兼容标准歌词接口。接入范围是本文列出的端点，不覆盖完整 OpenSubsonic。

用户操作见 [README](../README.md#连接音乐服务器)，核心文件职责见 [平台模块说明](../modules/platform/README.md#音乐服务器接入--music-server-integration)。

## 1. 分层与术语

- **HTTP API**：音乐服务器提供的接口，Navidrome 适配使用 `/rest/<method>.view`。
- **客户端仓库契约**：`:platform` 将协议响应转换成音理的 `SongItem`、`SongUrlResult`、`LyricEntry`。
- **认证 URL**：由 `:platform` 拼接，页面和播放器不接触。
- **profileId**：本机一个服务器账号配置的 UUID，同一地址的两个账号拥有不同 ID。
- **resourceId**：服务器返回的原始字符串，区分大小写，禁止转为 Long 后替代原值。

网易云、Bilibili、YouTube Music 客户端各自维护登录和仓库。服务器适配器通过 `AppContainer` 和已有 playback host 注入，不引入统一平台框架。

## 2. 连接与认证

### 2.1 配置

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| id（profileId） | UUID string | 创建时生成，编辑名称或密码时保留，移除后重加生成新 ID |
| label | string | 用户输入优先，空白时回退主机名 |
| baseUrl | HTTP(S) URL | 含端口和反代子路径，以 `/` 结尾，不含 userinfo、query、fragment |
| username | string | 去首尾空白且非空 |
| enabled | boolean | 模型字段，界面暂无停用开关 |
| revision | Long | 新建为 0，更新配置时递增，绑定请求结果与缓存有效性 |

- `SubsonicProfile` 不携带密码。
- 配置和密码保存于独立的 `EncryptedSharedPreferences`。
- 凭据只在 IO 请求中读取。
- 模型中没有 credentialRef、图标、上游品牌信息和排序字段。
- protocol 固定为 Subsonic，无协议选择 UI。

保存与更新规则：

- 添加和更新由仓库先 `ping` 成功再保存，底层 accounts.save 不做连接检查。
- 更新使用 expectedRevision 检查并发变更。
- 更新成功后清理该配置的扩展能力、歌词及音频元数据缓存。
- 浏览缓存按 revision 隔离，旧快照和任务由账号流异步清理。
- 移除只作用于本机配置，浏览缓存订阅账号流清理旧条目。
- 歌词、元数据、能力的进程内条目没有统一删除入口，但缺少配置时不会作为有效账号结果读取。

地址与用户名不可更改：

- 编辑界面禁用这两项，仓库更新和账号存储也拒绝同一 ID 下改变地址或用户名。
- 更换地址或账号需新增配置，同一实例的另一反代地址也使用新 UUID。
- 原歌单和历史引用不会自动重绑定。
- 媒体字节、预取、封面与旧引用因此保持原配置命名空间，不同实例的同资源 ID 不会串用缓存。
- 限制：媒体字节缓存和图片引用不含 revision，同一地址下同 ID 资源原地替换时无法主动失效。

### 2.2 公共请求参数

所有 JSON 请求使用 GET。URL builder 在 baseUrl 后追加 `rest/<method>.view`，各参数分别编码。

| 参数 | 值 |
| --- | --- |
| u | 当前配置用户名 |
| t | `lowercaseHex(MD5(UTF8(password + salt)))` |
| s | 每次请求重新生成的随机 salt，使用 16 随机字节的十六进制表示 |
| v | `1.16.1` |
| c | `NeriPlayer` |
| f | `json` |

- 配置 `https://music.example.com:4533/navidrome/` 生成路径 `/navidrome/rest/ping.view`。
- 示意参数 `t=<token>&s=<salt>` 不能作为可保存的播放地址。
- token 不是 OAuth 会话，没有可调用的刷新接口，每次根据凭据重新计算。
- HTTP 明文传输，设置页须明确提示。
- 仅支持用户名 + token/salt 认证，不支持 API Key 与表单 POST，两种认证参数不得混用。

参考：[公共 API 规范](https://opensubsonic.netlify.app/docs/api-reference/)

### 2.3 连接过程

1. 本地校验 URL 和必填项。
2. 使用候选配置请求 `ping`，HTTP 和业务状态都成功后才保存。
3. `getOpenSubsonicExtensions` 查询可选扩展，在首次读取歌词时按需发现。
4. 加载专辑或执行用户搜索。`ping` 只确认连接，曲库读取需对应权限，媒体播放取决于格式与设备。
5. 认证失败时提示重新登录，不循环重试密码，不替换为另一个配置。

扩展发现规则：

- 扩展名与版本以返回值为准，未知扩展忽略。
- `songLyrics` 缺失表示不能按歌曲 ID 读取结构化歌词。
- 查询遇到协议错误 code=0/70 或 HTTP 404/501 时回退为不支持，其他错误继续向上传播。
- code=0 是通用错误，此处仅按可选能力缺失处理。
- 能力缓存绑定配置修订号。
- 限制：无服务器版本/TTL 失效与手动刷新，发现失败也不细分原因。

参考：[能力发现](https://opensubsonic.netlify.app/docs/endpoints/getopensubsonicextensions/)

## 3. 响应与失败约定

成功示意：

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[]}}}
```

业务失败可能出现在 HTTP 200 内：

```json
{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"…"}}}
```

客户端检查 `subsonic-response.status`。

- 错误显示使用本地描述，不展示服务器错误原文或完整认证 URL。
- JSON 响应上限 4 MiB，曲库较大时依靠分页，单个超大专辑会明确失败，上限暂不可配置。
- 仓库为每次 JSON 调用设置 15 秒预算，SubsonicClient 本身不配置该预算。
- 连接检查超时由账号 UI 单独处理。
- 曲库、歌曲、歌词请求的内部超时转为 TIMEOUT，外部取消继续传播。

| kind / 结果 | HTTP / 业务信号 | 行为 |
| --- | --- | --- |
| AUTHENTICATION | HTTP 401；协议 40、44 | 凭据不可用，不自动切换认证方式 |
| FORBIDDEN | HTTP 403；协议 50 | 权限不足 |
| NOT_FOUND | HTTP 404；协议 70 | 通用资源/接口不存在提示，不细分歌曲、路径或扩展 |
| UNSUPPORTED | 协议 20、30；41、42、43 | 提示协议或认证方式不兼容，不自动降级认证 |
| RATE_LIMITED / SERVER | HTTP 429 / 5xx | 标记 retryable，Retry-After 上限 24 小时，不等待满该时长 |
| TIMEOUT / NETWORK / TLS | 超时 / 其他 IO / SSLException | TLS 不重试，网络和超时标记 retryable |
| INVALID_RESPONSE | 无法解析根 JSON、状态失败且错误码未知、响应超限 | 安全错误，缺失的具体列表字段不一定产生错误，见下文 |
| ACCOUNT_UNAVAILABLE / CONFIG_CHANGED | 配置缺失/不可用/请求修订失配；并发编辑冲突 | 当前请求停止或提示检查配置 |
| CancellationException（非 kind） | 外部取消 | 传播取消，不显示为网络失败 |
| 空列表（非 kind） | 正常空集合，或部分缺失列表字段 | 页面按空结果展示 |

异常类型：

- 对外使用 `SubsonicException : IOException`，保留 kind、code、httpStatus、retryAfterMs 与 retryable。
- HTTP 状态与协议错误通过 httpStatus 区分，无公共 Result 层。
- 页面使用本地化安全提示，取消向上传播。
- 播放器对歌词读取失败降级，不中断音频。
- 限制：“无歌词 / 不支持 / 读取失败”暂无独立 UI 状态。

认证类错误码：

- code=40 表示凭据错误。
- code=41 特指 LDAP 用户不支持 token 认证。
- 41/42/43 归为认证方式不支持，提示检查服务器认证设置，不报密码错误。
- code=44 表示无效 API Key，归为认证失败。
- 不提供明文或 API Key 登录，不读取或展示 helpUrl。

参考：[错误码定义](https://opensubsonic.netlify.app/docs/responses/error/)

响应结构校验：

- 目录、专辑详情和兼容搜索检查响应容器、数组类型和条目字符串 ID，异常不覆盖已有完整快照。
- 容器中的列表字段缺失或为 null 表示空集合。
- getAlbum、getArtist、getSong 核对返回对象的原始字符串 ID 与请求一致。
- 缺失、类型错误或身份不匹配均返回安全的 INVALID_RESPONSE，不保存错误歌曲的音频元数据。

重试规则：

- retryable 只是分类，JSON client 没有额外的业务重试循环。
- 共享 OkHttp 的连接恢复按其配置运行。
- 媒体加载由 ServerMediaLoadErrorPolicy 对连续错误最多重试 3 次。
- 超过 30 秒的 Retry-After 不在 loader 内等待。
- 无跨播放会话的总重试预算。

## 4. 端点映射

下表列出本接入所需参数，每个请求还需要第 2 节的公共认证参数。更多可选参数以链接文档为准。

| 方法 | 参数 | 成功数据路径 | 客户端职责 |
| --- | --- | --- | --- |
| ping | 无 | 响应根 | 连接检查 |
| getOpenSubsonicExtensions | 无 | openSubsonicExtensions[] | name、versions[] |
| getAlbumList2 | type、size、offset | albumList2.album[] | 专辑分页 |
| getAlbum | id | album，album.song[] | 专辑和完整曲目列表 |
| getArtists | 无 | artists.index[].artist[] | 按 ID3 歌手分类，保留原始歌手 ID |
| getArtist | id | artist，artist.album[] | 指定歌手的专辑，不预取各专辑的歌曲 |
| search3 | query；专辑：albumCount/albumOffset；单曲：songCount/songOffset；歌手：artistCount/artistOffset；其余两类 count=0 | searchResult3.album[]、song[] 或 artist[] | 提供分页仓库入口，页面的单曲目录使用空 query |
| getSong | id | song | 读取服务器声明的音频元数据，forceRefresh 绕过进程缓存 |
| stream | id、format=raw | 二进制音频 | Media3 在线播放 |
| download | id、format=raw | 二进制音频 | 原文件下载，遵循服务器下载权限 |
| getCoverArt | id、size | 二进制图片 | Coil 与系统封面 |
| getLyricsBySongId | id | lyricsList.structuredLyrics[] | 需已知 songLyrics 版本，逐行/普通歌词 |
| getLyrics | artist、title（省略空字段） | lyrics.value | 不支持结构化歌词时的标准文本/LRC 回退 |

主接口依据：[专辑列表](https://opensubsonic.netlify.app/docs/endpoints/getalbumlist2/)、[专辑详情](https://opensubsonic.netlify.app/docs/endpoints/getalbum/)、[搜索](https://opensubsonic.netlify.app/docs/endpoints/search3/)、[歌曲详情](https://opensubsonic.netlify.app/docs/endpoints/getsong/)。

### 4.1 分页和搜索

- 页面自动加载完整元数据目录，专辑和单曲以 500 条为一页遍历。
- 分页仓库入口默认 30 条。
- 专辑目录使用 `getAlbumList2(type=alphabeticalByName)`。
- 专辑详情由 `getAlbum` 一次返回曲目，不假定支持 songOffset。

| 场景 | 方法和分页参数 | 页面行为 |
| --- | --- | --- |
| 专辑目录 | getAlbumList2，size=500、offset | 自动遍历全部页，完成后保存完整快照 |
| 单曲目录 | search3，query 为空串、songCount=500、songOffset；albumCount=0、artistCount=0 | 自动遍历全部页，完成后保存完整快照 |
| 歌手目录 | getArtists | 保存完整 ID3 歌手列表，同名不同 ID 不合并 |
| 歌手专辑 | getArtist，id 为原始歌手 ID | 按需加载专辑元数据，点击专辑后使用共享歌单详情 |
| 分类内搜索 | 无额外搜索请求 | 对完整目录按名称、歌手或专辑文本筛选，歌手分类按名称筛选 |
| 专辑详情筛选 | 无新增网络请求 | 对当前专辑已取得的曲目按名称/歌手/专辑文本即时筛选 |

- [OpenSubsonic search3](https://opensubsonic.netlify.app/docs/endpoints/search3/) 要求支持空 query 返回曲库数据。
- 仅支持基础 Subsonic 的服务器是否遵循此行为，需按实际响应确认。
- 分类本地搜索使用完整目录，扫描中未完成的部分页面不作为完整结果。
- 歌手接口见 [getArtists](https://opensubsonic.netlify.app/docs/endpoints/getartists/) 与 [getArtist](https://opensubsonic.netlify.app/docs/endpoints/getartist/)。

分页与状态：

- offset 按服务器返回条数递增，显示内容按完整身份去重。
- 短页结束，满页可能再请求一次空页。
- 服务器扫描更新可能改变分页结果，刷新可重新加载。
- 各服务器、分类、关键词分别保存状态。
- 切分类保留已提交关键词，进入专辑清空详情筛选，返回时恢复父列表和位置。
- 本地筛选保留目录顺序，不做相关性重排。
- 限制：不支持跨服务器综合搜索。
- 服务器不提供可靠的全库变更校验标记，客户端不用文件系统索引的修改时间推断 ID3 元数据未变化。

### 4.2 歌曲映射

| 上游字段 | 客户端字段 | 规则 |
| --- | --- | --- |
| id | audioId、mediaUri | 原始 ID 可逆编码，profileId 参与命名空间 |
| title | SongItem.name | 缺失以原始 ID 兜底 |
| artist | artist | 原样保留，不拆分人名 |
| album | album | 真实专辑名，不承载平台标签 |
| albumId | SongItem.albumId 的 Long 代理值 | 不能用于服务器 API 请求；专辑浏览/详情使用 ServerAlbum.id 的原始字符串，歌曲模型未保存可逆专辑引用 |
| duration | durationMs | optLong 读取整秒，限制在 0..604800 后乘 1000，缺失为 0，不保留小数秒 |
| coverArt | coverUrl | 按原始 `coverArt` ID 构造带 `neri-cover=v1:<scope>` 来源标记的真实 `getCoverArt` URL，不用歌曲或专辑 ID 替代 |
| contentType、suffix、bitRate 等 | PlaybackAudioInfo | 来自服务器声明，不是解码实测；bitRate 为 kbps，samplingRate 为 Hz，另读 bitDepth/channelCount/size |

服务器歌曲 ID 与其他平台数值 ID 没有关联。参考：[Navidrome 接口差异](https://www.navidrome.org/docs/developers/subsonic-api/)、[Child 响应字段](https://opensubsonic.netlify.app/docs/responses/child/)

音频元数据缓存：

- 列表曲目映射也会填入音频元数据缓存。
- 5 分钟内 playback 可能直接使用缓存而不发 getSong，forceRefresh 才绕过。
- MIME 优先读取以 audio/ 开头的 contentType，否则按 suffix 做有限回退。
- MP4/M4A 仅标容器，不断言 AAC 或 ALAC。
- 元数据缓存的 representation 描述与媒体字节 cacheKey 是不同字段，不构成实例变更隔离。

## 5. 资源身份、播放和图片

规范化编码：

```text
channelId = subsonic
audioId   = v1:<profileId>:<base64url(UTF8(songId))>
mediaUri  = neri-server://<profileId>/song/<同样的编码>
streamUrl = null
id        = SHA-256("song:" + audioId) 前 8 字节构造的负 Long 代理值
```

- Base64URL 不带 padding。
- 完整身份使用 SongIdentity(id, "subsonic", mediaUri)，Long 仅适配已有模型，不能单独承担跨服务器去重。
- 历史只有 mediaUri 时仍可还原来源。
- 无效的服务器引用必须失败，不落入网易云默认分支。

### 5.1 请求时认证

认证复用 Media3 / Coil / 下载器的共享 OkHttp。播放和下载使用内部地址，歌曲、缓存和下载身份由此保持稳定：

```text
https://neri-server.invalid/v1/<profileId>/stream/<encodedSongId>
https://neri-server.invalid/v1/<profileId>/download/<encodedSongId>
```

- `.invalid` 地址不是服务器 API 或代理服务。
- 专用拦截器在客户端内部将其解析为当前配置的请求并补认证。
- 解析失败即中止，不访问该保留域。
- 返回给调用方的 request 是无凭据引用，队列恢复使用同一机制。

封面使用真实服务器端点，原始 coverArt ID 由 URL builder 编码：

```text
https://music.example.com/navidrome/rest/getCoverArt.view?id=<coverArtId>&size=600#neri-cover=v1:<scope>
scope = lowercaseHex(SHA-256(UTF8(normalizedBaseUrl + "\n" + username.trim())))
```

- normalizedBaseUrl 与服务器配置使用相同的 HTTP URL 规范化，保留端口和反代子路径，以单个斜杠结尾。
- scope 是稳定账号指纹，不是登录凭据。
- 同地址同用户名在不同设备上配置 UUID 不同，仍匹配同一封面来源。
- 用户名大小写不折叠，不按歌曲 ID、域名或唯一账号猜测映射。

fragment 的解析与认证：

- fragment 是客户端来源标记，不发送到服务器。
- 解析只接受 v1 标记、64 位小写十六进制 scope、精确的 rest/getCoverArt.view 路径，以及各出现一次的 id 和 size=600 参数。
- 拦截器只向地址和 scope 均精确匹配的本机已启用账号请求补认证。
- 未标记的普通图片不附加服务器认证。
- 封面模型和同步载荷不保存密码、u/p/t/s、API Key 等认证参数。
- 网络请求按当前账号即时签名，返回的 response.request 不携带认证参数。

旧封面引用：

- 旧格式 `https://neri-server.invalid/v1/<profileId>/cover/<encodedCoverId>` 仍可加载。
- Android 在后台准备账号后，导出同步时将本机可解析的旧封面引用投影为真实端点，不额外下载图片，不改原歌曲身份。
- 无法解析的旧引用原样保留，不推断未知服务器或账号。
- 相同本机账号下，新链接可读取旧磁盘封面缓存，保留原有版本和尺寸键。
- 缓存清理和估算按同一映射保留图片，无需重新下载。

同步与跨设备：

- 同步使用 `coverUrl` / `customCoverUrl` / `originalCoverUrl` 字段及 JSON、Protobuf 和 V4 归档结构。
- 收藏歌单、历史和播放统计封面遵循同一投影规则。
- 认证凭据不随同步迁移，另一台 Android 设备需自行添加相同的服务器地址与用户名。
- 服务器歌曲的 `audioId` / `mediaUri` 绑定原配置的 profile UUID，跨设备播放、歌词和下载不会自动重绑账号。
- 桌面端只保留无凭据的封面 URL，不支持服务器封面加载。

缓存身份：

- 音频表示固定为 raw，缓存键为 `subsonic:<audioId>:raw:v1`。
- 封面固定 600 像素，图片引用包含真实端点、封面 ID、尺寸和账号 scope。
- 增加可变尺寸或转码时，须把尺寸/格式/码率/表示版本加入缓存身份。
- 限制：同一 ID 内容原地替换后无法主动失效缓存。

### 5.2 流与失败

- 播放使用 `stream(format=raw)`，本地下载使用 `download(format=raw)`，均读取原文件。
- 显式指定 raw，避免 Navidrome 的自动下载转码设置改变文件格式。
- 服务器禁用下载或账号无权限时保留权限错误，不回退播放接口。
- 拖动和续传依赖文件容器与服务器 Range 支持。
- 转码音频的 timeOffset 需要对应扩展，不保证任意服务器都能转码精确拖动。
- 播放请求由播放器管理，下载请求由下载队列管理。

参考：[stream](https://opensubsonic.netlify.app/docs/endpoints/stream/)、[download](https://opensubsonic.netlify.app/docs/endpoints/download/)、[Navidrome 下载实现](https://github.com/navidrome/navidrome/blob/v0.64.2/server/subsonic/stream.go)

二进制响应的失败处理：

- stream / download / getCoverArt 成功时为二进制，失败时可能返回 XML/JSON，即使请求了 f=json。
- 客户端检查 HTTP 状态及错误 Content-Type，无法提前识别的错误字节由媒体解码器拒绝。
- 音频 HTTP 416 保留 Content-Range 与无凭据请求，交给播放器判断文件末尾，或交给下载器重新开始传输。
- 封面失败不影响音频，专辑和歌曲缺少 coverArt 时使用占位图。

参考：[getCoverArt](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/)

歌手头像：

- 优先使用服务端 coverArt，其次使用有效的公开 HTTP(S) artistImageUrl。
- 服务器头像缺失或加载失败时，使用完整单曲目录中该歌手最后一首有封面歌曲的图片。
- 仍无图则显示占位图。
- 关联使用原始 artistId/artists/albumArtistId/albumArtists，不按同名推断。
- 头像索引随单曲快照保存，已发现的详情头像可离线复用。
- 补头像不逐个预取专辑。
- 外链头像不附带服务器凭据。
- 带 userinfo、认证参数或保留资源域的外链不作为公开图片保存。

参考：[歌手字段](https://opensubsonic.netlify.app/docs/responses/artistid3/)

配置移除后：

- 新的在线解析会失败。
- 已下载的受管本地文件优先解析，不依赖服务器配置。
- 已缓冲内容可能仍能播放。
- 删除配置不清除媒体缓存，也不删除远程歌曲。
- 限制：暂不支持按配置清除媒体缓存。

### 5.3 缓存与请求复用

| 内容 | 设施与身份 | 生命周期 / 限制 |
| --- | --- | --- |
| 音频 | Media3 播放缓存，完整引用 + raw 表示 | 普通播放和预取共用，认证 salt 不改变缓存键 |
| 图片 | Coil/系统封面链路，配置 + coverArt ID + 引用版本 | 请求固定 600 像素 |
| 完整曲库 | SubsonicBrowseCache + PlatformPlaylistCacheRoomStore | 按 profile/revision 保存专辑、单曲、歌手三类完整快照，复用 24 小时 |
| 普通分页与详情 | 同一缓存与 Room 存储 | 内存页最多 64 页/20000 项，持久记录最多 128 条/20000 项；分页与专辑曲目 60 秒，歌手专辑 24 小时 |
| 搜索 | 页面筛选完整目录；仓库保留分页搜索 | 页面搜索不发额外请求，仓库搜索页只存内存 |
| 音频元数据 | getSong / 曲目映射的短期缓存 | 最多 256 项，5 分钟，按配置 revision，forceRefresh 绕过 |
| 歌词 | SubsonicLyricsCache，完整歌曲引用 + revision | 最多 32 首，非空成功 10 分钟，确定的空结果 60 秒 |
| 扩展能力 | lyricVersions，profileId + revision | 仅进程内，配置更新时失效 |

补充规则：

- 完整曲库快照全部页成功后才原子替换，不受普通分页预算淘汰。
- 歌词异常不缓存，空结果不抹除有效歌词。
- 限制：歌词暂无并发请求复用，能力缓存无 TTL 与手动刷新。

专辑页面：

- 先显示同配置、同修订的缓存曲目，再检查有效期并刷新。
- 冷启动后缓存过期时，断网或刷新失败仍保留已有曲目并显示安全错误。
- 切换账号、专辑或配置修订时，晚到的旧缓存与请求结果不覆盖当前页面。
- 浏览快照只提供曲目信息，离线播放需已下载文件或音频缓存。

浏览请求复用：

- 浏览键包含 profileId、revision、kind、query/albumId、offset、size。
- 同键请求共享任务，force 绕过新鲜缓存，但仍可加入同键正在进行的任务。
- 取消一个订阅者不影响其他订阅者，最后一个离开才取消任务。
- 刷新失败不覆盖有效快照，存储失败不阻断在线访问。
- 配置更新/移除通过账号流异步清理旧修订快照和任务。
- 请求前后另校验修订号，清空缓存后旧任务不回写。

容量与持久性：

- 普通分页容量是当前仓库/缓存实例的总预算，不是每个服务器独享的额度。
- 完整目录按配置单独保留，清除服务器浏览缓存时一并清除。
- 歌词、音频元数据及能力缓存仅在进程内，不作离线持久数据。

目录刷新：

- 页面先展示完整缓存快照，再按新鲜度刷新，首次打开预热其余两个分类。
- 服务器页可手动刷新当前分类，刷新失败时保留旧完整目录。
- 扫描中已完成的短期分页可被普通重试复用。
- 没有同键进行中的扫描时，手动强制刷新重取所有页，在途同键请求按共享规则复用。
- 全量目录只保存元数据，封面按需加载，音频缓存不等于持久离线下载。
- 限制：应用脱机开关与资源库公共刷新入口尚未统一接入。

下一首预取的启动条件：

- 非随机、非单曲循环，列表循环可预取首曲。
- 下一首目标仍匹配。
- 非计费网络，缓存可用。
- 播放器处于 `READY` 且正在播放。
- 已缓冲至少 5 秒，或已覆盖剩余短尾。

预取预算：

- 单次上限 1.5 MiB，并按已知文件长度与缓存预算的 1/8 缩减。
- 总任务预算 30 秒，媒体读取预算 10 秒。
- 保留 1 MiB 可用空间。
- 切歌、暂停、网络或配置变化时取消。
- 与当前播放争用缓存锁时让出，不等待播放持有的锁。
- 预取只作有限缓存，持久离线播放请使用下载功能。
- 日志中的 `read` 字节数包含缓存读取，不等于新增网络下载或缓存写入。

错误处理：

- HTTP 416 保留原始响应头交给 Media3，由其区分文件末尾与读取越界，真正越界和短读仍保留错误信号。
- 服务器瞬时传输错误有限重试，仍失败时暂停并保留当前曲目与进度。
- 网络恢复后需手动重试，不自动跨源替换。

## 6. 歌词

- 先查 `songLyrics` 扩展，只接受已知版本 1 或 2，未知版本不假定兼容。
- 请求为 `getLyricsBySongId(id)`，不发送 enhanced=true。
- 读取 v1 兼容的 line 数据，不消费 v2 的 cueLine/kind/agents。
- 不将 Long 代理 ID 发给歌词接口。

参考：[端点](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)、[扩展版本](https://opensubsonic.netlify.app/docs/extensions/songlyrics/)

标准接口回退：

- 触发条件：服务器缺少已知扩展版本，或可选歌词端点返回 HTTP 404/501、Subsonic 协议错误 0/70。
- 回退到[标准歌词](https://opensubsonic.netlify.app/docs/endpoints/getlyrics/)接口 `getLyrics(artist,title)`，缺失标签自动省略。
- 返回文本复用 LRC 解析器，无时间轴时按普通文本歌词展示。
- 该接口按歌手与歌名检索，匹配精度可能与按 ID 检索的端点不同。
- 结构化歌词端点成功响应但返回空歌词时，不再请求标准接口。
- 认证、权限、限流、网络、TLS、服务器错误及外部取消继续向上传播。
- 协议错误 0 仅在此可选能力路径上回退，不泛化为“不支持”。

示意响应的业务部分：

```json
{"lyricsList":{"structuredLyrics":[{"lang":"zho","synced":true,"offset":100,"line":[{"start":1000,"value":"第一行"},{"start":3500,"value":"第二行"}]}]}}
```

- start 和 offset 单位为毫秒。
- 正 offset 表示提前，展示起点为 `max(0, start - offset)`。
- offset 限制为 ±86400000，start 限制为 0..604800000，负 start 的行不参与展示。
- 优先第一份可解析的非空 synced 歌词，否则尝试普通歌词。
- 保留各组内的上游顺序，不把另一语言自动当翻译。
- 限制：暂无多语言选择与来源状态。
- 按起点排序，以后续更大的起点作为行终点。
- 没有更晚起点时，使用大于起点的有效歌曲时长，否则取起点后 5 秒。
- synced=false 使用普通歌词转换器，人为分配的显示时间不是服务端准确时间轴。
- 空集合表示无歌词，不支持表示能力缺失，超时表示错误。
- 播放器对不可展示结果降级，暂无独立状态 UI。
- 手动保存的歌词和明确的歌词源偏好优先，没有明确偏好时读取服务器歌词。
- 其他来源的手动匹配走既有入口，不改写曲库文件。
- 翻译/罗马音只展示真实保存或匹配的结果，不以服务器歌曲代理 ID 请求网易云。

offset 和结构依据：[structuredLyrics](https://opensubsonic.netlify.app/docs/responses/structuredlyrics/)。

## 7. 本地操作与能力边界

- 收藏和添加歌单只修改音理本地数据，不调用服务端 star/歌单写接口，也不上传播放统计。
- GitHub/WebDAV 同步携带无凭据歌曲引用，不携带服务器配置和密码。
- 另一设备缺少相同配置 ID 时不可播放，不按同名服务器自动重绑定。
- 本地下载复用持久队列、工作文件、受管目录和 SAF 提交流程。
- 每次来源解析重新读取 getSong 的原文件格式、长度和时长，传输时才认证。
- 恢复信息不保存服务器密码或认证直链。
- 同一歌曲 ID 以配置 ID 隔离，已下载文件不随配置移除。
- 下载遇到认证、权限、资源或配置失效时停止自动重试，临时错误进入重试流程。
- 服务器返回的正值 Retry-After 会保留到网络恢复和进程重启之后，用户显式重试沿用原有恢复语义。
- 音频提交后的歌词或封面失败交给后处理恢复，不删除已提交音频。
- 一起听在 UI 及操作路径上拒绝服务器歌曲，不导出认证直链。
- 限制：探索综合搜索/发现、推荐、上传与配置迁移未接入。

## 8. 客户端仓库契约

方法位于 [SubsonicRepository](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt)，不实现全平台公共 Provider 接口。

| 方法 | 输入 | 输出 / 行为 |
| --- | --- | --- |
| addAccount | label、address、username、password | ping 成功后保存，失败不保存 |
| updateAccount | original profile、label、address、password | address 规范化后须与原地址一致；空密码沿用原密码；ping 成功后保留 ID、增加 revision，并检查并发修改 |
| browseKey / browse | profileId、albumId/query、category、offset、size；force | ServerBrowseKey / ServerBrowsePage，绑定配置 revision，支持缓存及强制刷新 |
| catalogKey / catalog | profileId、category；force | 完整分类快照键 / ServerBrowsePage，复用 24 小时，默认 500 条自动遍历 |
| artistAlbumsKey / artistAlbums | profileId、artistId；force | 歌手专辑快照键 / ServerBrowsePage，含专辑与服务器歌手信息，缓存 24 小时 |
| cachedArtist / cachedArtists | 歌手摘要 / profileId、歌手摘要列表 | 只读缓存，合并服务器头像与歌曲封面，批量读取不逐歌手请求网络或 Room |
| albums | profileId、offset、size=30 | `List<ServerAlbum>`，根专辑目录 |
| albumSongs | profileId、albumId | `List<SongItem>`，专辑完整曲目 |
| search | profileId、query、offset、size=30 | `List<SongItem>`，兼容歌曲搜索入口，空词为单曲目录 |
| song | ServerSongRef | SongItem，getSong 映射 |
| playback | SongItem、forceRefresh=false | SongUrlResult，含内部 URL、服务器声明的音频信息、长度、表示和缓存键 |
| download | SongItem | SubsonicDownloadSource，重新读取 getSong 并校验歌曲 ID，返回无凭据 download 引用、原文件后缀、MIME、长度及毫秒时长 |
| cachedLyrics / lyrics | SongItem；lyrics 可指定 forceRefresh=false | 缓存读取 / `List<LyricEntry>`，按配置 revision 隔离，强制刷新绕过该歌曲缓存 |

- 页面使用 catalog 获取完整分类后本地筛选。
- browse 提供 ALBUMS、SONGS、ARTISTS 分类的兼容分页查询。
- 配置移除使用 accounts.remove。

调用顺序：

- 曲库浏览与歌手专辑接口依赖已加载的账号快照，页面 ViewModel 先 accounts.load。
- playback/download/lyrics 自行先加载账号。
- profile 和 cachedLyrics 是内存读取。
- credentials 是阻塞的加密存储读取，只供 IO worker/拦截器调用。

返回与异常：

- cachedLyrics 无有效账号、缓存或修订匹配时返回 null。
- lyrics 无可用歌词时可返回空列表，传输错误仍抛异常。
- playback 的非法引用返回 Failure，缺少有效配置返回 RequiresLogin，播放器外层另校验账号并转换为安全用户提示。
- download 的非法引用、歌曲 ID 不匹配、缺少配置或配置修订变化均抛安全异常，不回退到其他平台。

### 8.1 维护约束

已有例外和限制以上文行为为准。

- 字符串 resourceId 原样保存，API 请求使用原 ID，数字代理只适配既有模型。
- 配置修订与媒体表示不得改变歌单/历史的稳定歌曲身份。
- 账号操作通过仓库先验证再保存，修改成功后才发布状态。
- credentials 不在 UI 主线程读取，外部取消继续传播。
- 无凭据引用留在模型/缓存，认证 URL 留在传输边界。
- 错误类不得保存服务端原文、完整请求地址或带凭据的底层 cause。
- 新增公开元数据不得包含 password/token/salt。
- 共享组件新增参数时，原调用保持默认行为。
- 公共来源分支同时核对原平台、下载/共享和历史恢复，不只测服务器 UI。
- 新端点同步记录参数/单位、结果、失败、能力依赖、缓存和测试。
- 限制：更严格的 schema、认证窄接口和实例变更缓存隔离尚未完成。
