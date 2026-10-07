# 自定义音乐服务器接入设计草案

建议先以 Navidrome 为兼容目标，接入 Subsonic / OpenSubsonic；沿用现有的歌曲模型、搜索页面、播放器和歌词匹配能力，在音乐源设置中增加多服务器配置。

本文保留整体接入设计。源码审阅基于 `2c68dbbbee5eb0b8f45e4d2937e7a313bbd97037`，协议资料查阅日期为 2026-10-04。当前已开始实现本地 Android 原型；设计目标不等于实现或实测状态。

详细契约见 [API 规范](music-server-api.md)，已实现范围与构建/验收状态见 [MVP](music-server-mvp.md)，交付顺序见 [开发计划](music-server-development-plan.md)。这三份文件是当前执行依据。

## 本轮要确定的内容

1. 明确协议能覆盖的能力，以及缺失能力的处理方式。
2. 确定多服务器配置、歌曲身份和现有模块的接入点。
3. 在已有 issue #490 对齐范围，再准备带实现和精简文档的 Draft PR。

完整首版的目标包含：多服务器管理、探索与资源库的“我的音乐”、单服务器及综合搜索、专辑浏览、在线播放与队列恢复、封面、服务端歌词和现有歌词补全入口。推荐先提供有明确来源的发现栏目。

第三方平台账号继续使用现有登录流程，用户可同时使用网易云、Bilibili、YouTube Music 和自定义服务器。跨设备服务器配置同步、永久下载、收藏与歌单回写、音频上传分别安排后续交付。

## 现有能力如何复用

[贡献指南中的扩展路径](../CONTRIBUTING.md#常见扩展路径--extension-paths)已经区分了探索搜索、播放页元数据补全、在线播放和下载；本方案沿这些入口接入。

| 现有位置 | 直接复用 | 需要补充 |
| --- | --- | --- |
| `:model` 的 `SongItem` | 歌曲展示字段、`channelId`、`audioId`、`mediaUri` | 定义服务器资源编码；优先保持模型字段不变 |
| `ExploreViewModel` / `ExploreSearchResult.Song` | 搜索状态与歌曲结果展示 | “我的音乐”来源、服务器选择、分服务器分页 |
| `LibraryViewModel` / `LibraryScreen` | 资源库导航与列表组件 | “我的音乐”页签和二级服务器选择 |
| `SearchTextMatcher` | 文本归一化、拼音及相关性计算 | 针对服务器结果补充小范围排序规则 |
| `RequestGeneration`、协程与现有网络层 | 请求失效控制、取消和传输 | 按服务器分别维护状态 |
| `AppPlayerBindings` / `PlayerRepositoryDependencies` | 播放仓库注入 | 增加服务器播放仓库依赖 |
| `SongUrlResult` / 现有 Media3 播放链路 | 播放结果、队列、控制与缓存机制 | 服务器地址解析、缓存身份和来源分支 |
| Coil / 通知栏封面解析 | 图片展示与缓存 | 服务器封面引用解析 |
| `PlayerLyricsProvider` / `EditableLyricsMatcher` | 歌词展示、来源选择、匹配和手动修正 | 按服务器歌曲 ID 读取歌词 |

当前 `SearchApi` 用于播放页元数据搜索，返回模型不能完整表达服务器曲库、认证和播放。服务器客户端应放入 `:platform/platform/subsonic/`，按现有平台包划分 api、auth、repository、mapping；共享数据契约放在 `:model`，确实需要 Room 时将实体与 DAO 放在 `:database`。依赖方向遵循 [平台模块说明](../modules/platform/README.md)。

本次新增的仓库只服务于自定义服务器。已有平台继续通过原有仓库提供能力；先利用已有 host 注入方式连接播放模块，避免把新功能扩大为全平台接口迁移。

## 协议覆盖情况

网络请求优先使用已有的 Subsonic 接口，客户端仓库负责适配为音理已有模型。Navidrome 声明兼容 Subsonic 1.16.1，并有实现差异；兼容性最终应按服务器版本和运行时响应确认。[Navidrome 兼容说明](https://www.navidrome.org/docs/developers/subsonic-api/)

| 需求 | 接口或协议能力 | 首版处理 |
| --- | --- | --- |
| 连接与认证检查 | `ping` | 检查 HTTP 和协议响应体中的成功状态 |
| 扩展发现 | `getOpenSubsonicExtensions` | 记录扩展名称与版本；旧服务器没有该接口时按基础能力运行 |
| 可访问音乐库 | `getMusicFolders` | 库筛选可选，遵循当前账号权限 |
| 专辑列表和详情 | `getAlbumList2`、`getAlbum` | 分页浏览，保留字符串资源 ID |
| 歌曲恢复与搜索 | `getSong`、`search3` | 恢复原来源曲目，支持搜索分页 |
| 播放、封面 | `stream`、`getCoverArt` | 请求时认证，运行时解析资源 |
| 按歌曲取歌词 | `getLyricsBySongId`，`songLyrics` 扩展 | 按扩展版本适配；缺失时允许现有歌词补全 |
| 收藏、歌单 | `getStarred2`、`getPlaylists`、`getPlaylist` 等 | 协议覆盖，界面与回写另分步骤实现 |
| 发现内容 | `getAlbumList2` 的列表类型、`getRandomSongs` | 提供“最近添加”“常听”“随机发现”等明确栏目 |
| 个性化推荐 | 不能由基础列表接口直接保证 | 可选能力，未支持时不展示个性化推荐入口 |
| 服务器标签与图片 | 基础接口没有完整的实例品牌信息约定 | 用户可自定义；预留上游元数据适配能力 |
| 音频上传与同步上传 | 本方案采用的基础协议没有可直接使用的统一上传契约 | 后续单独设计能力和冲突处理 |

`search3` 分别提供歌曲、专辑、艺人的 count / offset，客户端须独立保存分页状态。[搜索接口](https://opensubsonic.netlify.app/docs/endpoints/search3/)

`getAlbumList2` 提供 random、newest、frequent 等类型，可以支持发现栏目；这些类型本身不表示个性化推荐。综合页的发现内容先按服务器分组展示。[专辑列表接口](https://opensubsonic.netlify.app/docs/endpoints/getalbumlist2/)

`ping` 响应可携带服务器软件类型及版本，不能据此推定存在用户自定义实例名称和图标。Navidrome 的 `getAvatar` 是用户头像能力，也不应充当服务器标签图片。[ping 响应](https://opensubsonic.netlify.app/docs/endpoints/ping/)；[Navidrome 接口差异](https://www.navidrome.org/docs/developers/subsonic-api/)

### 请求与错误约定

- 基础地址保留协议、端口及反向代理子路径，由 URL 构建器追加 `rest/<method>.view`，使用 JSON 响应。
- 首个认证实现采用用户名与 Subsonic token 方式；请求携带 `u`、`t`、`s`、`v`、`c`、`f`。`t` 按协议计算为 `md5(password + salt)`，这不是 OAuth 会话 token，不假定有刷新接口。认证材料单独存储。
- API Key 等扩展按声明能力再适配。密码哈希不替代 HTTPS；服务端认证与已有第三方平台登录相互独立。
- 区分认证失败、权限不足、资源不存在、不支持、超时和响应无效。歌词空结果属于成功但无内容，不能当作网络故障。
- 取消继续向上传播；复用 `runCatchingNonCancellation` 等已有辅助函数，避免切换服务器后旧请求覆盖新页面。

认证参数与可选 POST 的使用遵循 [OpenSubsonic 公共 API 规范](https://opensubsonic.netlify.app/docs/api-reference/)，不假定所有服务器支持同一组扩展。

### 客户端仓库的最小契约

下表是拟新增仓库的方法职责，名称待代码评审时确定。它们是客户端调用契约，网络层仍请求上表中的现有接口。

| 操作 | 输入 | 输出与约束 |
| --- | --- | --- |
| `connect` | 配置 ID | 连接状态、协议版本、扩展与可选能力快照 |
| `search` | 配置 ID、关键字、各类 offset / limit | 歌曲映射为 `SongItem`；专辑、艺人保留原始 ID；分页和错误按服务器返回 |
| `listAlbums` / `getAlbum` | 配置 ID、列表类型或专辑 ID | 专辑摘要或曲目列表，保留所属服务器 |
| `getSong` | 配置 ID、原始歌曲 ID | 可恢复的 `SongItem`，不把失效曲目替换成别服同名歌曲 |
| `resolvePlayback` | 歌曲引用、播放策略 | 现有 `SongUrlResult`；认证地址仅用于当次播放 |
| `resolveArtwork` | 配置 ID、封面 ID、尺寸 | 图片请求所需信息和稳定缓存键 |
| `getLyrics` | 配置 ID、原始歌曲 ID | 映射后的 `LyricEntry` 列表及同步类型、语言等必要信息 |
| `getDiscovery` | 配置 ID、栏目、分页 | 带明确类型的专辑或歌曲栏目 |
| `getServerMetadata` | 配置 ID | 可选的上游标签及图标引用；基础适配器允许无结果 |

能力快照只表达 UI 确实需要判断的项目，例如歌词扩展版本、发现栏目类型、上游元数据是否可用。超时不能被永久记为“不支持”；连接、账号或服务器版本变化后重新获取。可选能力不支持时明确返回该状态，避免用空结果掩盖错误。

上游实例标签、图标及个性化推荐的 HTTP 扩展尚未定案。首版保留适配器返回这些数据的入口；若需要实现新的网络扩展，应另附版本、发现方式、请求与响应、认证和兼容回退说明，经过讨论后再实现。

## 多服务器配置

在“音乐源设置”下增加“自定义音乐服务器”，支持新增、编辑、禁用、删除和连接检查。每个配置对应一个服务器上的一个账号；同一地址的不同账号也分开保存。

| 字段 | 约定 |
| --- | --- |
| `profileId` | 本机生成的稳定 UUID，作为资源和缓存的隔离维度 |
| `protocol` | 首版可选 Subsonic / OpenSubsonic；Navidrome 是兼容目标 |
| `baseUrl` | 包含协议、地址、可选端口和子路径；端口输入最终归一到此值 |
| `credentialRef` | 指向独立凭据存储，不把密码放进可导出的配置对象 |
| `labelOverride`、`iconOverride` | 用户设置的标签和标签图片 |
| `upstreamLabel`、`upstreamIconRef` | 可选上游元数据；不覆盖用户设置 |
| `enabled`、排序位置 | 决定二级 TAB 和综合搜索参与者 |

标签优先级：用户设置 → 可用的上游标签 → 用户可辨认的默认名称。图片采用同样的覆盖原则，没有图片时显示统一占位图。

仅修改标签、图标或同一实例的访问地址时保留 `profileId`；更换账号或替换为另一实例时创建新配置。删掉后重加默认视为新配置。移除配置后，原有队列条目显示来源不可用，不静默关联到其他配置。

普通配置与认证材料分开存储，凭据实现沿用项目账号仓库的依赖方式，并在实现时确认受保护的存储方案。服务器配置和凭据的跨设备同步暂不包含在首版。

## 歌曲身份与持久化

Navidrome 的资源 ID 是字符串，不能直接写入音理的 `Long id` 或 `Long albumId`。[Navidrome ID 说明](https://www.navidrome.org/docs/developers/subsonic-api/)

优先复用 `SongItem` 的来源字段，新增集中管理的编码与解码辅助函数，建议如下：

```text
channelId = "subsonic"
audioId   = "v1:<profileId>:<base64url(原始 songId 的 UTF-8 字节)>"
mediaUri  = "neri-server://<profileId>/song/<同样编码的原始 songId>"
streamUrl = null
```

原型已采用以上 URI scheme，并在来源分流与身份辅助函数中适配；它是内部资源引用，不包含主机地址、用户名或密码。编码应可逆、保留大小写，不以字符串分割猜测上游 ID。原始专辑与封面 ID 保存在服务器摘要或缓存中；缓存失效可通过 `getSong` 补回。`album` 继续保存真实专辑名。

原型使用 SHA-256 前 8 字节生成兼容 `Long` 的负数代理值，但它不能替代完整资源身份。普通远程来源的身份归一化目前会丢弃 `mediaUri`，因此需要为新来源增加小范围分支：用现有 `SongIdentity` 同时保留代理 ID、来源名称与规范化的内部 URI。`sourceStableKey` 若使用，应由现有序列化函数生成，遵循既有格式。

落地时至少覆盖下面几条往返路径：

- 搜索结果 → 队列 → Room → 重启恢复：还原相同的 `profileId` 和原始歌曲 ID，再生成请求认证。
- 歌曲 → 统计历史 → 点击播放：当前 `TrackStat.toPlaybackStatsSongItem()` 没有还原 `channelId/audioId`，可先利用保留的规范化 `mediaUri` 解码补回，核对统计写入是否完整保留引用。
- 本地歌单保存 → 读取：保留服务器来源字段，无法解码的条目显示明确状态。
- 所有只按 `Long id` 查找或去重的相关调用点：需要使用完整身份或明确代理 ID 碰撞处理，不能在核对前承诺不需要任何存储调整。

新来源的身份处理集中在已有 identity 辅助函数与映射层；是否还需要新增模型字段，待上述往返路径核对后决定。首选方案不添加全局 `ServerTrackRef` 字段。

## 页面与综合搜索

建议页面结构如下，已有平台使用原来的入口和登录状态。

```text
音乐源设置
└─ 自定义音乐服务器
   ├─ 家里的音乐库
   └─ 另一台服务器

探索 / 资源库
└─ 我的音乐
   ├─ 综合
   ├─ 家里的音乐库
   └─ 另一台服务器
```

探索负责搜索和发现；资源库负责专辑等已入库内容。两个入口共享配置列表、服务器摘要与资源仓库，各自保留页面请求状态。没有服务器时提供添加入口。

**本草案暂将“综合”定义为已启用的自定义服务器集合。** 将网易云等已有平台纳入同一结果集需要另行确认，避免扩大首次接入范围。

综合搜索并发请求各服务器，分别维护超时、错误、分页位置和是否还有结果。一台服务器不可用时保留其他结果，并提供按服务器重试。切换关键字或服务器时使上一轮请求失效。聚合后的分页不能使用一个全局 offset 去请求所有服务器。

排序复用 `SearchTextMatcher` 的文本处理；其现有分数越低越匹配，实现时不能直接当成正向相似度。歌名、歌手匹配决定主要顺序，热度只在相近的文本匹配结果之间辅助排序：

1. 有明确目标歌名、歌手时，优先两者一致的候选，再考虑部分匹配；现场、翻唱、伴奏等版本差异保留。
2. 普通自由文本不强行拆成“歌名 + 歌手”，复用现有多字段搜索语义。
3. 热度仅使用确实存在且语义可比的数据。首版建议在同一服务器内做次级排序，跨服务器不直接比较原始播放次数；缺失热度按中性处理。
4. 综合结果保留来源标签和完整歌曲引用，只按同一来源身份去重；同名歌曲仍可有多个版本。

搜索返回范围由各服务器决定，首版只能对已返回候选排序，不能保证整个曲库的全局最优排序。搜索相关性排序与播放失败后的音源替换分开处理，后者不随本功能自动开启。

## 播放与封面

播放通过现有注入关系获取服务器仓库，在 `PlayerManagerUrlExtensions` 增加显式来源分支并返回 `SongUrlResult`。音质信息使用服务器返回的实际格式与码率；必要时补充 `PlaybackAudioInfo` 的来源枚举及相关分支。

原型通过保留域 `neri-server.invalid` 的无凭据资源地址复用现有 HTTP 播放及图片链路，专用拦截器在请求时生成鉴权播放地址；详见 API 规范第 5 节。需要一并检查队列状态保存的 `media_url` 和 URL 日志，持久化时保存可恢复引用，避免过期或带认证的地址进入普通元数据。音频缓存键至少包含 `profileId`、原始歌曲 ID 和实际音频表示，认证 salt 的变化不应产生新的曲目缓存身份。

封面使用响应里的 `coverArt` 标识，通过内部引用连接现有 Coil 加载器及通知栏的 `PlaybackCoverSourceResolver`。缓存按服务器账号、封面 ID、尺寸及可用版本信息隔离；图片请求失败保留占位图并允许重试，不阻塞播放。列表、播放页、通知栏和锁屏需要使用一致的资源解析逻辑。

服务器请求复用项目网络设施，认证仅附加到对应配置的请求，不建立全局“所有图片都带服务器凭据”的拦截规则。

## 歌词接入

在现有 `PlayerLyricsProvider` 的原生来源读取处接入服务器歌词。尊重已有手动编辑和明确的歌词来源偏好；默认情况下先取当前服务器、当前歌曲 ID 的歌词，再允许用户启用现有在线补全。

`getLyricsBySongId` 通过 `songLyrics` 扩展提供结构化歌词。首版按服务端声明的版本读取逐行或普通歌词，处理 offset、语言和多份结果；逐字等更高版本能力需单独协商，不能根据客户端支持反推服务器支持。[歌词接口及扩展版本](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)

转换后使用现有 `LyricEntry`、解析工具和展示组件；无时间戳的歌词保留普通歌词语义，展示用的人工时间分配不标作准确同步歌词。

歌词缺失时继续使用 `EditableLyricsMatcher` 和现有手动选择界面。自动补全沿用现有高置信度匹配入口与来源偏好，提供歌名、歌手、专辑、时长作为输入。服务端暂时超时不应覆盖已有歌词或清除用户编辑；不把服务器歌曲的代理 Long ID 传给网易云歌词接口。

## 需要一起处理的兼容边界

这些是首版接入的必要边界，具体修改限定在新来源。

| 路径 | 已见风险 | 首版要求 |
| --- | --- | --- |
| URL、歌词、媒体缓存的默认分支 | 未识别来源可能进入网易云逻辑 | 新来源显式分流，错误时保留原来源 |
| 历史统计映射 | 来源字段不能完整往返 | 按内部资源引用恢复来源，核对身份去重 |
| GitHub 等现有数据同步 | 非本地歌曲可能自动进入同步映射 | 保留无凭据歌曲引用；配置与密码不参与同步，另一设备缺配置时不可播放；发布前核对混合歌单、合并和删除标记 |
| 一起听 | 未识别来源可能还原为网易云；模型可携带直链 | UI 和实际导出路径均给出暂不支持，不导出服务器认证地址 |
| 永久下载与平台专属菜单 | 新来源可能落入其他平台操作 | 按来源控制入口；下载另做元数据和恢复适配 |
| 服务器移除、改密或曲目消失 | 旧队列仍保存引用 | 显示来源或认证状态，允许重新连接或移除条目 |

可恢复引用与认证地址需要分别审查。仅让 `SongItem.streamUrl` 为空或隐藏同步按钮，不足以覆盖已有状态持久化和自动导出流程。

## 实现顺序与交付

1. 本地完成 API 规范、P0 安卓原型与后续计划；在已有 issue #490 对齐范围。
2. P0 跑通多服务器配置、专辑/单服搜索、播放、封面、基础歌词，形成带源码和构建/验收记录的实现 Draft PR。
3. 同一 PR 收敛来源身份、恢复、错误处理及现有平台兼容；后续小 PR 补综合搜索、完整配置管理与发现栏目。
4. 收藏歌单回写、离线下载、配置迁移和上传分别安排独立方案。

不要求先合并纯文档 PR。完整首版目标与今晚 P0 范围分开，具体依赖、工作量和验收门槛见 [开发计划](music-server-development-plan.md)。当前源码和构建状态见 [MVP](music-server-mvp.md)，未验收场景不能写成已通过。

## 希望先对齐的三个问题

1. “综合”首版是否只覆盖已配置服务器？本稿按此范围设计。
2. 是否接受先用现有来源字段加可逆编码，并在身份归一化和历史恢复处做少量适配？如现有持久化路径无法完整往返，再提出必要的模型变更。
3. 标签和图片首版是否接受“可自定义、可选上游元数据”的回退；推荐先提供发现栏目？若要求上游自动品牌信息或个性化推荐作为首版必选能力，需要先补完网络扩展约定。

## 源码审阅入口

- [SongItem](../modules/model/src/main/java/moe/ouom/neriplayer/data/model/SongItem.kt)、[SongIdentityExtensions](../modules/local/src/main/java/moe/ouom/neriplayer/data/identity/SongIdentityExtensions.kt)、[SyncSongIdentity](../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/identity/SyncSongIdentity.kt)
- [ExploreViewModel](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/ExploreViewModel.kt)、[LibraryScreen](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/LibraryScreen.kt)、[SearchTextMatcher](../modules/common/src/main/java/moe/ouom/neriplayer/common/search/SearchTextMatcher.kt)
- [AppPlayerBindings](../app/src/main/java/moe/ouom/neriplayer/core/di/player/AppPlayerBindings.kt)、[PlayerRepositoryDependencies](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/host/PlayerRepositoryDependencies.kt)
- [PlayerManagerUrlExtensions](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerUrlExtensions.kt)、[PlaybackMediaItemFactory](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/media/PlaybackMediaItemFactory.kt)、[PlaybackQueueRoomStore](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/persistence/PlaybackQueueRoomStore.kt)
- [AppImageLoaderInitializer](../app/src/main/java/moe/ouom/neriplayer/core/startup/app/AppImageLoaderInitializer.kt)、[PlaybackCoverSourceResolver](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/service/artwork/PlaybackCoverSourceResolver.kt)
- [PlayerLyricsProvider](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsProvider.kt)、[EditableLyricsMatcher](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/repository/EditableLyricsMatcher.kt)
- [PlaybackStatsSongMapper](../modules/local/src/main/java/moe/ouom/neriplayer/data/stats/PlaybackStatsSongMapper.kt)、[SyncSongMapping](../modules/local/src/main/java/moe/ouom/neriplayer/data/sync/mapping/SyncSongMapping.kt)、[DefaultListenTogetherSongMapper](../modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/mapping/DefaultListenTogetherSongMapper.kt)

Navic 的参考范围为 [913244f](https://github.com/ssalggnikool/Navic/tree/913244f29a321ba0d343e452e61725c88b28686d)：会话管理、Subsonic 资源请求、曲库缓存与歌词仓库。其客户端库和界面无需整体引入；音理已有的歌词匹配、网络与播放结构应优先复用，多个服务器的资源和缓存隔离由本方案明确补足。
