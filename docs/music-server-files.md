# 音乐服务器接入：文件职责与审阅地图

更新：2026-10-10。以工作区相对已合并上游 `285beb6b` 的 106 个原候选文件为基础，另加入本文件；这是职责导航，最终提交范围以发布分支相对目标上游 diff 为准。新上游 `400e278f` 尚未对齐，本机草稿、APK、设备日志及备份不在此清单。

接口参数和实际行为见 [API 契约](music-server-api.md)，讨论取舍见[设计回顾](music-server-integration.md)，测试项数和版本证据见[验收报告](music-server-acceptance.md)。表格只介绍此次接入所涉及的职责，不宣称既有文件整体由本 PR 新增。

## 依赖与公共影响

协议仓库通过现有 host 注入播放器，库不读取 AppContainer；Room schema 沿用原表。ServerSongRef 属于 model，不读取账号。当前 SubsonicResourceInterceptor 虽由宿主注入账号提供者，仍直接依赖具体 SubsonicAccounts；这是 api → auth 的实际耦合，不等同于已经遵循所有纯协议包的窄依赖要求，后续评审需考虑凭据窄接口或放置归属。现有受管域检查没有覆盖全部新增 Subsonic 文件。

公共改动重点看 PlaylistExportSheet 的可选文案参数、设置搜索交互、歌词加载/错误反馈、URL 与下载/共享的来源分支、缓存统计清理，以及模型来源枚举。不因服务器路径测试通过就推定原平台完整设备回归已完成。

## 协议、账号与模型

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [PlaybackAudioInfo.kt](../modules/model/src/main/java/moe/ouom/neriplayer/data/model/playback/PlaybackAudioInfo.kt) | 增加 SUBSONIC 来源及原音展示/音质选项处理。 |
| [ServerSongRef.kt](../modules/model/src/main/java/moe/ouom/neriplayer/data/model/server/ServerSongRef.kt) | 完整来源引用、编码/解码、数字代理、内部资源 URL、来源识别；媒体键尚无配置 revision。 |
| [StorageUsageModels.kt](../modules/model/src/main/java/moe/ouom/neriplayer/data/model/storage/StorageUsageModels.kt) | 增加服务器浏览缓存类别与选择/汇总模型。 |
| [SubsonicClient.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicClient.kt) | 生成 REST URL、token/salt 并请求/读取 JSON；检查 HTTP、根状态和 4 MiB 上限。15 秒预算由仓库提供。 |
| [SubsonicErrorMessage.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicErrorMessage.kt) | 把安全异常类别映射为本地化资源 ID；账号输入和取消/超时单独判定。 |
| [SubsonicException.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicException.kt) | 定义实际 kind、HTTP/协议码、Retry-After、retryable 和脱敏 IO 分类；分类本身不执行重试。 |
| [SubsonicResourceInterceptor.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicResourceInterceptor.kt) | 仅解析内部资源域，读取当前账号，转换 stream/cover 请求并认证；屏蔽常见通用认证头，保留 416 和无凭据返回 request。当前直接依赖 auth 的具体账号类。 |
| [SubsonicAccounts.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/auth/SubsonicAccounts.kt) | SubsonicProfile/Credentials、加密存储、账号快照/流、地址规范化及 expectedRevision 写入检查；不自行 ping。 |
| [SubsonicAudioMetadata.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicAudioMetadata.kt) | 映射服务器声明的 MIME/码率/采样率/位深/声道/长度和表示描述；没有解码实测。 |
| [SubsonicBrowseCache.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseCache.kt) | 分类、键、页和存储接口；60 秒新鲜度、共享任务/订阅取消、容量与清理代次。 |
| [SubsonicBrowseRoomStore.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseRoomStore.kt) | 把浏览页映射到既有 Room 快照表；subsonic 总预算和账号修订清理，不存关键词搜索。 |
| [SubsonicLyricsCache.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLyricsCache.kt) | 按歌曲引用/revision 缓存非空成功歌词，32 首/10 分钟；不合并歌词网络请求。 |
| [SubsonicRepository.kt](../modules/platform/src/main/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicRepository.kt) | 连接/编辑、端点查询、ServerAlbum/SongItem 映射、播放元数据、能力探测及逐行歌词解析；应用层契约入口。 |

## 页面、宿主及公共 UI

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [AppContainer.kt](../app/src/main/java/moe/ouom/neriplayer/core/di/AppContainer.kt) | 组装账号、客户端、仓库、Room 适配和共享 HTTP 拦截器；生产依赖入口。 |
| [AppPlayerBindings.kt](../app/src/main/java/moe/ouom/neriplayer/core/di/player/AppPlayerBindings.kt) | 把服务器仓库通过既有 playback host 暴露给播放器。 |
| [PlaybackSourceBadge.kt](../app/src/main/java/moe/ouom/neriplayer/ui/component/playback/PlaybackSourceBadge.kt) | 补充服务器来源图标和文案。 |
| [PlaylistExportSheet.kt](../app/src/main/java/moe/ouom/neriplayer/ui/component/playlist/PlaylistExportSheet.kt) | 添加可选确认/操作文案参数，供服务器添加本地歌单复用；默认值兼容原调用方。 |
| [NowPlayingScreen.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/NowPlayingScreen.kt) | 识别服务器来源；显示服务器歌词失败提示与手动重试。 |
| [MoreOptionsMainContent.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/actions/MoreOptionsMainContent.kt) | 服务器下载/一起听的禁用样式、原因和点击边界。 |
| [NowPlayingLyricsFastStage.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/lyrics/NowPlayingLyricsFastStage.kt) | 原始歌词成功缓存参与首帧加载，尊重已确认的手动歌词和来源偏好。 |
| [NowPlayingLyricsLoadOwner.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/lyrics/NowPlayingLyricsLoadOwner.kt) | 歌词请求代次、错误提示和重试；阻止旧请求结果/错误覆盖新歌曲。 |
| [NowPlayingLyricsLoadSources.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/lyrics/NowPlayingLyricsLoadSources.kt) | 为既有歌词加载端口增加可选 cachedOriginal，生产实现接 PlayerManager。 |
| [MusicServerScreen.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/server/MusicServerScreen.kt) | 探索入口、服务器选择、专辑/单曲列表、搜索/刷新/分页和批量本地歌曲操作。 |
| [MusicServerSettingsContent.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/server/MusicServerSettingsContent.kt) | 配置列表、添加/编辑/删除界面；输入/密码状态、连接提交及错误反馈。 |
| [MusicServerSongRow.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/server/MusicServerSongRow.kt) | 歌曲行、播放/多选状态和歌曲操作菜单；复用原有本地操作。 |
| [ServerSongDisplay.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/server/ServerSongDisplay.kt) | 为歌曲应用本地展示覆盖，保留来源身份，以及手动歌词/偏移等展示数据。 |
| [ExploreScreen.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/explore/ExploreScreen.kt) | 增加服务器独立入口；未实现探索聚合搜索或发现。 |
| [LibraryScreen.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/LibraryScreen.kt) | 增加末尾音乐服务器标签，保留 MY_MUSIC 枚举和原平台顺序/开关。 |
| [SettingsScreen.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/SettingsScreen.kt) | 注册服务器设置页并接账号卡片导航。 |
| [SettingsAccountStatusContent.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/auth/SettingsAccountStatusContent.kt) | 保留原账号内容与资料/搜索参数，插入服务器管理卡片。 |
| [SettingsMusicServerCard.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/auth/SettingsMusicServerCard.kt) | 与原平台账号入口一致的服务器管理卡片、背景与自适应操作区。 |
| [SettingsStorageCacheSection.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/component/storage/SettingsStorageCacheSection.kt) | 增加服务器浏览缓存的清理选择项。 |
| [StorageCacheDetailsContent.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/component/storage/StorageCacheDetailsContent.kt) | 在缓存详情展示中包含服务器浏览快照统计。 |
| [SettingsNavigationSearch.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/navigation/SettingsNavigationSearch.kt) | 共享设置搜索新增清空、结果标题与父级路径，影响所有设置结果。 |
| [SettingsPage.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/page/SettingsPage.kt) | 注册 MusicServers 页面标题/导航归属。 |
| [SettingsStoragePageController.kt](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/storage/SettingsStoragePageController.kt) | 把服务器浏览缓存选择状态接入清理控制器。 |
| [AppSettingsRouteOwners.kt](../app/src/main/java/moe/ouom/neriplayer/ui/settings/route/AppSettingsRouteOwners.kt) | 宿主设置路由的缓存清理接线与服务器缓存选择。 |
| [MusicServerViewModel.kt](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/server/MusicServerViewModel.kt) | 账号加载、分类/查询/专辑/父列表状态、滚动位置、分页请求代次与用户错误映射；非全局搜索聚合器。 |

## 播放接线、数据源与预取

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [ServerMediaPrefetchPolicy.kt](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/runtime/prefetch/ServerMediaPrefetchPolicy.kt) | 可测试的缓冲/网络/播放条件和字节预算计算；不负责 HTTP 任务。 |
| [PlaybackQualityOwner.kt](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/runtime/quality/PlaybackQualityOwner.kt) | 拒绝服务器歌曲套用原平台音质档位。 |
| [PlayerManager.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt) | 服务器重试进度状态和生命周期接线；主体复用原播放器。 |
| [PlayerManagerPlaybackQualityPort.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/audio/output/PlayerManagerPlaybackQualityPort.kt) | 向音质策略端口提供服务器来源判断。 |
| [ConditionalHttpDataSourceFactory.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/engine/datasource/ConditionalHttpDataSourceFactory.kt) | 服务器数据源包装与预取专属 Call.Factory 接线；保留原请求策略。 |
| [ServerAwareHttpDataSource.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/engine/datasource/ServerAwareHttpDataSource.kt) | 包装服务器媒体 open/read，识别短读，保留越界信号并分类 IO；普通来源沿用 delegate。 |
| [ServerMediaLoadErrorPolicy.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/engine/datasource/ServerMediaLoadErrorPolicy.kt) | 服务器媒体连续错误的有限重试与 Retry-After 判断；原来源回退 Media3 默认策略。 |
| [PlayerRepositoryDependencies.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/host/PlayerRepositoryDependencies.kt) | 既有 repository host 增加可选服务器仓库。 |
| [PlayerManagerLyriconLyricsLoader.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/integration/lyrics/PlayerManagerLyriconLyricsLoader.kt) | 系统歌词输出链路接服务器歌词，失败时降级并保留取消。 |
| [PlayerManagerLifecycleExtensions.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/lifecycle/PlayerManagerLifecycleExtensions.kt) | 服务器媒体失败进入保留曲目/进度的处理路径。 |
| [ServerPlaybackError.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/lifecycle/ServerPlaybackError.kt) | 服务器播放错误路径的队列/进度保留及重试状态处理入口。 |
| [PlaybackMediaItemFactory.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/media/PlaybackMediaItemFactory.kt) | 服务器来源使用完整引用的缓存键。 |
| [PlayerLyricsProvider.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/metadata/PlayerLyricsProvider.kt) | 提供服务器原始歌词及缓存读取，原平台继续原分支。 |
| [PlayerManagerPlaybackExtensions.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/playback/PlayerManagerPlaybackExtensions.kt) | URL 失败及手动重试接服务器恢复状态，播放成功后清理重试状态。 |
| [ServerPlaybackRecovery.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/playback/ServerPlaybackRecovery.kt) | URL 解析失败时按请求 token 保留服务器曲目/进度，供手动重试。 |
| [PlayerManagerGenericUrlPrefetch.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/prefetch/PlayerManagerGenericUrlPrefetch.kt) | 分派服务器预取；目标切到当前曲目时取消投机任务，播放不等待服务器预取。 |
| [PlayerManagerServerMediaPrefetch.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/prefetch/PlayerManagerServerMediaPrefetch.kt) | 调度下一首头部预取、检查目标/网络/空间，复用播放缓存并取消阻塞 IO；总预算 30 秒/读取 10 秒。 |
| [PrefetchCallFactory.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/prefetch/PrefetchCallFactory.kt) | 追踪并取消单次服务器预取拥有的 OkHttp 调用，不取消其他播放请求。 |
| [CachedPlaybackDescriptor.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/CachedPlaybackDescriptor.kt) | 恢复缓存音频信息时处理服务器原音文案和 M4A 容器标签。 |
| [ListenTogetherQualityPolicy.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/ListenTogetherQualityPolicy.kt) | 服务器来源在共享音质序号转换中的显式兜底，不表示支持共享。 |
| [PlayerManagerListenTogetherStreamExtensions.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerListenTogetherStreamExtensions.kt) | 共享描述/候选构造识别服务器来源并拒绝认证流导出。 |
| [PlayerManagerUrlExtensions.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerUrlExtensions.kt) | 公共 URL 入口先校验服务器账号/引用，绕过通用直链捷径并分派服务器解析。 |
| [ServerSongUrlResolver.kt](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/ServerSongUrlResolver.kt) | 将仓库播放结果接入播放器、原音标签及受控错误反馈，传播外部取消。 |

## 存储、身份与执行边界

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [PlatformPlaylistCacheDao.kt](../modules/database/src/main/java/moe/ouom/neriplayer/data/local/database/dao/PlatformPlaylistCacheDao.kt) | 补充按平台列出快照记录，供预算和配置修订清理使用。 |
| [PlatformPlaylistCacheRoomStore.kt](../modules/database/src/main/java/moe/ouom/neriplayer/data/local/database/store/PlatformPlaylistCacheRoomStore.kt) | 新增有界事务写入及按条件清理；保留原平台读写入口。 |
| [AudioDownloadManagerRuntime.kt](../modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/runtime/AudioDownloadManagerRuntime.kt) | 执行层拒绝尚未支持的服务器下载，防止绕过 UI。 |
| [DefaultListenTogetherSongMapper.kt](../modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/mapping/DefaultListenTogetherSongMapper.kt) | 服务器歌曲共享映射拒绝；默认兜底返回非空类型，原来源继续映射。 |
| [SongIdentityExtensions.kt](../modules/local/src/main/java/moe/ouom/neriplayer/data/identity/SongIdentityExtensions.kt) | 本地 identity 优先使用完整服务器引用，支持 mediaUri 恢复。 |
| [StorageCacheCleaner.kt](../modules/local/src/main/java/moe/ouom/neriplayer/data/local/storage/cleanup/StorageCacheCleaner.kt) | 登记 subsonic 浏览快照清理选项；内存/任务取消由宿主设置路由接线。 |
| [StorageUsagePresenter.kt](../modules/local/src/main/java/moe/ouom/neriplayer/data/local/storage/presentation/StorageUsagePresenter.kt) | 服务器缓存计入缓存展示类别。 |
| [StorageLocations.kt](../modules/local/src/main/java/moe/ouom/neriplayer/data/local/storage/source/StorageLocations.kt) | 服务器浏览快照统计和清理来源登记。 |
| [SyncSongIdentity.kt](../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/identity/SyncSongIdentity.kt) | 同步条目从 mediaUri 或 channelId/audioId 恢复完整服务器身份，不迁移配置。 |

## 资源、配置和文档

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 服务器设计/文件导航，保留原贡献和质量要求。 |
| [CONTRIBUTING_EN.md](../CONTRIBUTING_EN.md) | 英文贡献入口中的服务器文档导航。 |
| [README.md](../README.md) | 面向用户的服务器接入入口和文档导航。 |
| [README_EN.md](../README_EN.md) | 英文用户入口中的服务器范围/文档导航。 |
| [crap-scope.json](../config/quality/crap-scope.json) | 保持既有阈值，按实际 Compose 字节码更新生成方法选择器；不代表所有新增文件纳入受管范围。 |
| [domain-dependencies.json](../config/quality/domain-dependencies.json) | 允许一起听拒绝逻辑引用精确 ServerSongRefKt 模型类，不开放账号或协议实现依赖。 |
| [music-server-acceptance.md](../docs/music-server-acceptance.md) | 按版本记录质量检查、真机操作和未覆盖范围，不代替接口规范。 |
| [music-server-api.md](../docs/music-server-api.md) | 当前 HTTP 参数/响应、模型映射、身份/缓存、仓库方法和后续协议提案。 |
| [docs/music-server-cache-acceptance/README.md](../docs/music-server-cache-acceptance/README.md) | 历史缓存/断线阶段记录，当前结论转到验收总报告。 |
| [music-server-development-plan.md](../docs/music-server-development-plan.md) | 合并前问题、后续功能和专项回归排期，未完成项保持待办。 |
| [music-server-integration.md](../docs/music-server-integration.md) | issue 讨论对应、模块依赖、实际取舍与评审边界。 |
| [music-server-mvp.md](../docs/music-server-mvp.md) | 当前功能、使用流程、限制和构建入口。 |
| [modules/common/src/main/res/values-en/plurals_server.xml](../modules/common/src/main/res/values-en/plurals_server.xml) | 英文服务器复数文案。 |
| [modules/common/src/main/res/values-en/strings_server.xml](../modules/common/src/main/res/values-en/strings_server.xml) | 英文服务器字符串；原平台设置搜索共用文案也在此。 |
| [modules/common/src/main/res/values-zh/plurals_server.xml](../modules/common/src/main/res/values-zh/plurals_server.xml) | 显式中文服务器复数文案。 |
| [modules/common/src/main/res/values-zh/strings_server.xml](../modules/common/src/main/res/values-zh/strings_server.xml) | 显式中文服务器字符串；原平台设置搜索共用文案也在此。 |
| [modules/common/src/main/res/values/plurals_server.xml](../modules/common/src/main/res/values/plurals_server.xml) | 默认中文服务器复数文案。 |
| [modules/common/src/main/res/values/strings_server.xml](../modules/common/src/main/res/values/strings_server.xml) | 默认中文服务器字符串；原平台设置搜索共用文案也在此。 |
| [modules/platform/README.md](../modules/platform/README.md) | 平台模块与 subsonic 包职责、实际凭据依赖及测试入口。 |
| [music-server-files.md](../docs/music-server-files.md) | 本文件：逐文件用途、公共影响和审阅导航，不保存本机材料。 |

## 自动化测试

| 文件 | 本次接入中的职责 / 评审点 |
| --- | --- |
| [NowPlayingLyricsFastStageTest.kt](../app/src/test/java/moe/ouom/neriplayer/ui/screen/NowPlayingLyricsFastStageTest.kt) | 缓存原词不覆盖手动来源/偏好。 |
| [NowPlayingLyricsLoadOwnerTest.kt](../app/src/test/java/moe/ouom/neriplayer/ui/screen/NowPlayingLyricsLoadOwnerTest.kt) | 服务器歌词失败/重试、超时/取消与旧请求拒绝。 |
| [ServerSongDisplayTest.kt](../app/src/test/java/moe/ouom/neriplayer/ui/screen/server/ServerSongDisplayTest.kt) | 展示覆盖保持身份、同 ID 隔离和恢复原名。 |
| [LibraryTabOrderTest.kt](../app/src/test/java/moe/ouom/neriplayer/ui/screen/tab/library/LibraryTabOrderTest.kt) | 地区与 YouTube 开关下音乐服务器始终在末尾。 |
| [MusicServerViewModelTest.kt](../app/src/test/java/moe/ouom/neriplayer/ui/viewmodel/server/MusicServerViewModelTest.kt) | 草稿查询、提交/清空、分类切换、详情筛选与返回。 |
| [ListenTogetherSongMapperTest.kt](../modules/listentogether/src/test/java/moe/ouom/neriplayer/data/ltw/mapping/ListenTogetherSongMapperTest.kt) | 服务器共享拒绝和既有来源映射。 |
| [StorageUsageHostAdapterTest.kt](../modules/local/src/test/java/moe/ouom/neriplayer/data/local/storage/host/StorageUsageHostAdapterTest.kt) | 服务器浏览缓存统计/清理类别与既有汇总。 |
| [SubsonicClientRequestUrlTest.kt](../modules/platform/src/test/java/moe/ouom/neriplayer/platform/subsonic/api/SubsonicClientRequestUrlTest.kt) | 子路径、参数编码、token/salt 与非法接口名。 |
| [SubsonicBrowseCacheTest.kt](../modules/platform/src/test/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicBrowseCacheTest.kt) | 新鲜度、共享任务/订阅取消、失败保留、修订隔离、清理后不回写及存储适配。 |
| [SubsonicLibraryBrowseTest.kt](../modules/platform/src/test/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLibraryBrowseTest.kt) | 专辑/单曲分类参数、分页、原始 ID 和单曲目录快照。 |
| [SubsonicLyricsCacheTest.kt](../modules/platform/src/test/java/moe/ouom/neriplayer/platform/subsonic/repository/SubsonicLyricsCacheTest.kt) | 非空成功结果、TTL、身份/revision 隔离和空结果不覆盖。 |
| [ServerMediaPrefetchPolicyTest.kt](../modules/playback/logic/src/test/java/moe/ouom/neriplayer/core/player/runtime/prefetch/ServerMediaPrefetchPolicyTest.kt) | 非计费网络、播放缓冲和字节预算。 |
| [PlaybackQualityOwnerTest.kt](../modules/playback/logic/src/test/java/moe/ouom/neriplayer/core/player/runtime/quality/PlaybackQualityOwnerTest.kt) | 服务器原音不允许套用平台音质切换。 |
| [PlayerManagerPlaybackQualityPortTest.kt](../modules/playback/runtime/src/test/java/moe/ouom/neriplayer/core/player/audio/output/PlayerManagerPlaybackQualityPortTest.kt) | 音质端口的服务器来源拒绝接线。 |
| [ServerRangeResponseTest.kt](../modules/playback/runtime/src/test/java/moe/ouom/neriplayer/core/player/engine/datasource/ServerRangeResponseTest.kt) | 416 末尾/越界、短读、认证失败与无凭据返回 request；受控响应。 |
| [ServerPrefetchCancellationTest.kt](../modules/playback/runtime/src/test/java/moe/ouom/neriplayer/core/player/prefetch/ServerPrefetchCancellationTest.kt) | 取消打断阻塞 open/read 并释放任务；不是真机网络矩阵。 |
| [SyncSongIdentityCompatibilityTest.kt](../modules/sync/src/test/java/moe/ouom/neriplayer/data/sync/identity/SyncSongIdentityCompatibilityTest.kt) | 无凭据引用的身份恢复；不代表混合版本/跨设备专项完成。 |
