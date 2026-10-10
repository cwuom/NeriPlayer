# 服务器接入验收总报告

更新：2026-10-10。范围：Navidrome / OpenSubsonic 完整本地实现。当前源码对比基线 `285beb6b`，分支 `feat/navidrome-mvp`，已有 HEAD `2b195cc` 加待提交合并（MERGE_HEAD `285beb6b`）及工作区改动，尚未新增 commit。本报告区分自动化、现场观察、用户反馈与后续专项，不用初版报告代替当前状态。

## 当前结论

工作区已合并上游 `285beb6b` 并解决冲突，卡片修正版 Debug 构建、完整本地质量检查和本轮服务器真机补验已通过。服务器认证、媒体库、播放与交互已形成完整流程，此前基础真机闭环按用户反馈记录为完成。合并后及历史安装版本的现场证据分别记录，旧版的筛选、多选等操作不自动计为新版逐项复测；原平台完整设备矩阵等专项不包含在本轮通过范围。

首个 PR 按约 80% 的粗略交付状态记录，提交与发布尚未执行。本地 review 发现的地址变更媒体缓存隔离仍待修复；截图在本地，公开附件尚未上传。具体待办见[开发计划](music-server-development-plan.md)，检查通过不表示所有已知问题或后续能力已经完成。

本轮文档复核补充：GitHub 查询上游已前进至 `400e278f`，尚未对齐及验证该版本。下方 `285beb6b` 的“最新上游”和通过结果属于当轮历史快照；本轮只修正文档及文件介绍，未改变生产代码或重跑 Gradle。

## 上游对齐与补验（2026-10-10）

- 最新上游 `master`：`285beb6b25a2e7a457c0dc909a19bee60d8cff0b`。采用本地 `merge --no-commit --no-ff`，HEAD 保持 `2b195cc2093e02d8b12dbf1519439132346ee8b2`，MERGE_HEAD 指向最新上游；无新增 commit、push 或 PR。
- 完整工作区的逐文件三方检查发现 3 个文本冲突：账号页调用、账号页内容、CRAP 方法选择器。处理后保留上游账号卡片/资料加载/搜索高亮及服务器入口；CRAP 不减少受管方法，按编译结果调整自动生成编号。Git 以既有三个提交合并时实际有 2 个冲突文件，质量配置由工作区改动另外整合，两种数量分别记录。
- 合并后播放器文件达到 2000 行上限，已将服务器失败判断移入现有 `ServerPlaybackRecovery.kt`；保留其他来源原失败路径和服务器队列/进度恢复行为。模块边界检查通过。
- 隔离源码完整 Debug 构建通过：13 分 40 秒，361 项任务全部执行。最初应用时 4633 个受版本控制文件一致；新增卡片后检查时，4634 个文件与构建副本逐字节一致，冲突条目为 0。随后本轮只整理文档，未修改生产源码或重建 APK。
- 最终 `verifyModularization :app:assembleDebug` 通过（10 分 38 秒，884 项任务，53 执行/831 复用）。JVM 汇总 11849 项，11842 通过、7 条件跳过、0 失败/错误，包含构建逻辑测试 4 项；质量工具自测 165 项通过。模块边界、域依赖检查通过；CRAP 测量 49164 个方法，受管 14592 个，0 个超过阈值 9。
- 15 份 lint 报告共 0 错误、27 警告、1 Hint；共 39 个问题位置的对应文件逐字节等于上游 `285beb6b`，本轮服务器卡片未新增 lint 问题。具体报告和警告来源见本地质量材料。
- 卡片修正版 APK `2b195cc2.10102224`（26101007）已覆盖安装，SHA-256 `0179132a91755afefa43e1001c9b96c73fb8563056b2485796283ee054ce9ef6`。已有服务器配置保留；卡片与按钮都能进入管理页，系统返回回到账号页，详情说明只显示一次。
- 合并后真机补验：Oasis 专辑分类搜索、12 首专辑详情、Wonderwall 播放与歌词、切到 Don’t Look Back in Anger、12 首队列正常。暂停两次读取保持 00:31，恢复后进度推进到 00:37；Wi-Fi 为已验证的非计费网络，22:31:03.606 日志确认下一首预取完成 `bytes=1572864, target=1572864`，即读取并补齐 1.5 MiB 的目标头部缓存；此计数不是单独测量的新增网络写入量。
- 停止应用后只读统计服务器浏览缓存 327 条：9 个专辑详情共 87 条、7 个专辑目录页共 210 条、1 个单曲目录页 30 条；Oasis 专辑详情 12 条、持久搜索页 0。重启后暂停曲目保留。仅核对已有认证配置，未重新添加/改密/移除；未做断网启动、宽屏、多服务器或原平台完整回归，未宣称人工听感验证。手机已保持暂停并恢复自动熄屏。
- 账号页服务器入口现已改用与原平台一致的 24dp 圆角卡片、玻璃背景和自适应操作区，提供“管理服务器”按钮；详情页移除与页头重复的说明。该界面修正 Debug 构建通过（1 分 25 秒），手机竖屏截图和跳转补验完成；完整检查结果见本节。
- 首轮合并完整检查因新增界面修正主动停止（退出 143），未记为通过；卡片修正后的第一轮检查因上游新增标签测试仍缺少 `MY_MUSIC` 预期而失败 3 项（25 分 29 秒），已同步地区/YouTube 开关下的末尾服务器标签预期，保留独立刷新约定。最终 5 项排序测试及全套检查通过，失败日志保留以供回顾。

本地材料目录为源码仓库外的工作区 `artifacts/neriplayer-upstream-alignment-20261010/`，包含 `README.md`、`quality-summary.json`、`lint-provenance.json`、`phone-verification.json` 与截图。本文记录检查结论和证据范围；这些本机文件不随源码提供，不作为仓库内链接。公开评审时另行附上选定的截图及脱敏汇总，备份、设备原始数据和 APK 保留本地。

## 入口改名与排序补验（2026-10-10）

资源库入口及服务器页标题从“我的音乐”改为“音乐服务器”，英文同步为 `Music servers`；入口移到标签末尾，保留原有平台相对顺序、YouTube 开关行为及 `MY_MUSIC` 枚举身份。此项随当前服务器接入 PR 收尾，不改变服务器协议或缓存策略。

- 定向执行 `:app:testDebugUnitTest --tests moe.ouom.neriplayer.ui.screen.tab.LibraryScreenYouTubeGateTest :app:assembleDebug`，BUILD SUCCESSFUL，耗时 1 分 3 秒；现有 YouTube 开关用例 1 项通过。另静态核对国际/非国际、YouTube 开/关四种显示列表，服务器均在末尾。`git diff --check` 通过。
- 新包 `2b195cc.10102140`（versionCode `26101006`），SHA-256 `bf90f61f53433f6bff008fc6e13473ff4cf78907bdb0f9ff498e2af076805950`，已通过 USB 覆盖安装，返回 Success。
- 真机确认末尾依次可见“哔哩哔哩 → QQ音乐 → 音乐服务器”，入口及页标题正确；打开服务器专辑列表、进入专辑详情、系统返回到服务器列表均正常。播放器保持暂停，测试后亮屏设置恢复为原值 0。
- 新入口截图 `01-server-tab-last.png` 和版本与检查证据 `verification.json` 已保存于仓库外工作区 `artifacts/neriplayer-server-entry-20261010/`；公开附件尚未上传。下文 10 月 9–10 日播放、缓存和预取现场证据属于改名前的安装版本；本轮仅补验上述入口变动，未把完整质量检查或预取重新记为新包实测。

随后已将最新上游 `285beb6b` 合并到工作区，处于待提交状态；下方历史证据不自动改记为合并后实测，补验状态见上游对齐章节。

## 历史仓库检查（2026-10-09，基线 `2c68dbb`）

整仓检查命令（警告清理前的完整快照）：

```sh
./gradlew verifyModularization :app:assembleDebug \
  --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process --continue --console=plain
```

本机使用 JDK 17，JAVA_HOME 和 PATH 同时指向其工具目录，GRADLE_USER_HOME 使用本机工具链目录。最终命令返回 0，BUILD SUCCESSFUL，耗时 10 分 50 秒；以下结果已核对报告。

| 检查 | 该轮结果 | 证据 |
| --- | --- | --- |
| 模块边界 | 已通过 | Python 独立检查及 Gradle verifyModuleBoundaries |
| Debug 构建 | 已通过 | app:assembleDebug；新 APK 元数据与包内 manifest 一致 |
| JVM 测试 | 9264 项：9260 通过，4 跳过，0 失败/错误 | app 与 13 个自有 JVM 库的 1155 份 XML 报告 |
| 质量工具自测 | 163 项通过 | Python unittest 输出 |
| 域依赖 | 已通过，0 违规 | domain-dependencies/report.json 的 errors 为空 |
| CRAP | 已通过，受管 14282 个方法中 0 个超过阈值 9 | 共测量 46919 个方法；不按范围外分数判定门禁 |
| app / 自有库 lint | 15 份报告，0 错误，23 警告、1 Hint | app 19 警告/1 Hint，common 1、platform 2、playback runtime 1 警告 |
| diff / 文档链接 / 资源文案 | 已通过 | git diff --check；6 份服务器文档的 28 个本地链接有效；默认/英文/中文均 63 个资源键且集合一致 |

远端 CI 尚未运行。此次未新跑模拟器 instrumented tests、Native host 专项或外部平台账号 smoke；这些状态与真机基础闭环分别记录。

JVM 报告中的 4 项跳过为网易云歌词账号 smoke、YouTube Music 播放账号 smoke，以及 2 项需显式开启的同步大规模测试；未将它们记为执行通过。

### 警告来源与后续清理

完整检查的 23 条警告中，20 条警告及 1 条 Hint 所在文件与上游基线 `2c68dbb` 逐字节一致：app 的图标、版本更新、ChromeOS ABI、网络配置和组件参数建议，以及 common 的重复图标。未将这些项目归因于服务器接入，也未为此修改上游代码。

本次服务器代码原有 2 条 UseKtx 和 1 条 UsableSpace，已作如下处理：

- 账号保存/删除使用返回 Boolean 的 commitEdit，仍检查同步写入成功后才发布状态。仅在该 helper 上局部标注 SuppressLint("UseKtx") 并说明理由，沿用上游 YouTubeAuthRepository 的现有处理方式；不是全局关闭规则。KTX 的 edit(commit=true) 不返回 commit 是否成功，机械替换会丢失错误检查，参见 [AndroidX 实现](https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/content/SharedPreferences.kt)。这两条属于有理由保留的规则例外，不声称改为了 KTX。
- 预取空间检查从 File.usableSpace 改为 [StatFs.availableBytes](https://developer.android.com/reference/android/os/StatFs#getAvailableBytes())，使用缓存目录所在文件系统当前可供应用写入的剩余空间；保留 1 MiB 余量，空间不足即跳过，不为投机预取回收其他缓存数据。

上述表格保留警告清理前的完整检查快照。清理后执行 `:platform:lintDebug :playback:runtime:lintDebug :platform:testDebugUnitTest :playback:runtime:testDebugUnitTest :app:assembleDebug`，命令返回 0，BUILD SUCCESSFUL，耗时 6 分 59 秒；模块边界另以 Python 工具复核通过。

| 警告清理后的复核 | 结果 |
| --- | --- |
| platform lint | 0 错误、0 警告 |
| playback runtime lint | 0 错误、0 警告 |
| platform JVM 测试 | 937 项：936 通过，1 项外部账号 smoke 条件跳过，0 失败/错误 |
| playback runtime JVM 测试 | 1091 项通过，0 失败/错误/跳过 |
| Debug 构建、模块边界、diff | 通过 |

本轮共复核 2028 项测试（2027 通过、1 跳过）。其余 lint 报告沿用上一轮，汇总现为 20 条上游警告、1 条上游 Hint；没有重新运行整仓 JVM、app lint、CRAP 或域依赖，不将上一轮整仓结果写成本次重复执行。

CRAP 与域依赖检查按仓库现有配置选择受管文件、方法与包，不代表全部新增代码都进入门禁。服务器预取策略位于已有 runtime 受检范围；新增 Subsonic 协议/仓库及服务器 UI 尚未配置为完整文件的 CRAP/域依赖检查范围。模块边界、构建和 lint 则包含这些新增源码。

### 本轮检查发现与处理

- 新增服务器分支使三个播放器文件达到 2001、2006、2000 行，超过“库单文件少于 2000 行”的限制。已将服务器错误处理、失败保留进度和 URL 解析提到同目录的小文件，保持原行为；模块边界检查已通过。
- 新增服务器浏览缓存的存储统计项后，原存储展示测试仍断言 21 项，实际为 22 项。已更新断言，并验证服务器缓存项对应正确清理类别且空缓存不增加占用。
- 完整 lint 发现新增服务器提示有 3 处错误、1 处同类警告：Compose 中通过 LocalContext 查询文案。已改为 LocalResources，使资源读取遵循 Compose 的配置更新约定。
- common lint 发现 63 处 MissingTranslation：62 个服务器字符串和 1 个复数文案缺少 values-zh。已补齐中文目录，默认、英文、中文三个目录的服务器资源键一致；不禁用翻译检查。
- 新增歌词错误提示的 LaunchedEffect 改变 Compose 编译生成的方法序号，原 CRAP 方法选择器失配。依据实际覆盖率 XML 和未改变的页面主体，将两处 lambda 序号 148 更新为 149，受检逻辑和阈值保持不变。
- 一起听映射层新增拒绝服务器歌曲的来源判断，需要引用 model 中的 ServerSongRefKt。已将这一精确模型类列入该域允许依赖，不引入服务器协议或账号实现依赖。
- 修正以上项目后的完整检查中，构建、JVM 测试、lint 与域依赖通过，CRAP 仍有 8 个方法超限。已补充缓存歌词不覆盖手动来源、服务器歌词失败/重试/取消、原音质设置拒绝、来源引用恢复及共享拒绝的受控测试；将歌词源判定、失败反馈和预取缓冲条件按职责整理，保留原阈值与受检范围。
- 再次检查中，9264 项 JVM 测试无失败或错误，4 项按原有条件跳过。默认一起听映射始终有来源兜底，收紧具体实现的返回类型并移除不可达的空值分支；该方法最终 CRAP 为 8。设置清空按钮保留原布局及操作，直接根据 query.length 判断显示，避免内联字符串判定产生重复条件分支；输入框及其对应 lambda 最终 CRAP 均为 6。完整重跑通过，阈值和受检范围未放宽。
- 首次聚合 Gradle 检查进程中断，未取得完整结果。分批执行时，质量工具自测因 PATH 缺少 javac 失败；已补齐 JDK PATH 后重跑。上述失败不能计入最终通过结果。

## 服务器相关自动化覆盖

9 个服务器专用测试类共 43 项通过；本轮另在现有歌词、音质、同步身份和一起听测试类中增加 9 项服务器边界测试，均通过，共 52 项直接相关用例。不把历次 41 项与 25 项重复相加，也不将这 52 项再加到全仓库 JVM 测试总数中。

| 测试 | 项数 | 覆盖与边界 |
| --- | ---: | --- |
| SubsonicClientRequestUrlTest | 3 | 接口名、子路径、参数编码与 token/salt；不等同于实际认证联调 |
| SubsonicBrowseCacheTest | 11 | 新鲜度、请求复用/订阅取消、失败保留、修订隔离、清空后不回写、存储适配 |
| SubsonicLibraryBrowseTest | 4 | 专辑/单曲查询与分页、空 query、原始 ID、单曲目录读写；底层存储为模拟对象 |
| SubsonicLyricsCacheTest | 4 | 成功歌词、过期、配置/歌曲隔离、空结果不覆盖 |
| ServerSongDisplayTest | 4 | 自定义展示保持完整身份、同 ID 隔离、原名恢复 |
| MusicServerViewModelTest | 6 | 搜索输入、分类切换、详情筛选与返回状态 |
| ServerMediaPrefetchPolicyTest | 2 | 非计费网络/播放缓冲条件和字节预算 |
| ServerPrefetchCancellationTest | 3 | 取消打断阻塞打开/读取，任务及请求释放 |
| ServerRangeResponseTest | 6 | 416 文件末尾与真正越界、认证失败、短读、无凭据请求保留 |

测试位于 app、modules/platform、modules/playback/logic 和 modules/playback/runtime 的 src/test。受控响应验证客户端约定，不验证服务器索引、具体媒体格式或所有网络条件。

本轮新增 9 项分别位于 NowPlayingLyricsFastStageTest（2 项）、NowPlayingLyricsLoadOwnerTest（3 项）、PlaybackQualityOwnerTest、PlayerManagerPlaybackQualityPortTest、SyncSongIdentityCompatibilityTest 和 ListenTogetherSongMapperTest（各 1 项）。覆盖缓存不覆盖手动来源、服务器错误与重试、超时/取消、过时请求不回写、拒绝修改服务器音质、从无凭据引用恢复身份及拒绝一起听共享。

## 真机与现场观察

| 项目 | 结论 | 证据范围 |
| --- | --- | --- |
| 基础功能与断网使用 | 用户报告已测试，未发现问题；基础真机闭环已完成 | 用户操作反馈，不拆写为每个专项都独立通过 |
| 最新媒体库/交互版 | 已覆盖安装并启动；后续用户确认基础真机闭环完成 | ADB 安装/版本记录，加用户反馈 |
| 目录缓存落盘 | 实际 Room 保存 3 页共 90 张专辑、1 张专辑 15 首曲目 | 早期现场读取；新版单曲目录落盘另有受控存储适配测试 |
| 下一首预取 | 非计费 Wi-Fi 顺序播放，17:22:00 日志观察到下一首头部预取完成 | 不代表整首离线下载或所有取消场景实测完成 |
| 重复请求数/缓存命中字节/取消专项 | 有自动化覆盖，未保留完整真机测量矩阵 | 按后续专项排期，不重复要求基础闭环 |
| 多服务器身份与共享链路 | 安排在后续多服务器阶段 | 不把 UUID 模型或受控测试写成该专项已通过 |

### 最新版本 USB 补充验收（2026-10-09–10）

手机安装版本为 `2b195cc.10091715`，versionCode `26100902`，包名 `moe.ouom.neriplayer.debug`，与警告清理 APK 一致。本轮未修改生产代码、重建 APK 或执行 commit/push/创建 PR。

| 操作 / 观察 | 结果及范围 |
| --- | --- |
| 分类搜索（10 月 9 日） | 专辑分类搜索 Oasis 返回一张 12 首专辑；保留同词切到单曲，返回 12 首歌曲，搜索框提示与分类一致 |
| 专辑筛选与返回（10 月 9 日） | 进入详情后改为专辑内筛选；Wonderwall 仅显示一首，全选显示已选 1 项；清空恢复 12 首，返回父列表恢复 Oasis 搜索词及专辑结果 |
| 清空单曲搜索（10 月 9 日） | 保持单曲分类，恢复 30 首已加载目录并显示播放/全选仅覆盖已加载歌曲的提示；未逐页核对全库完整性 |
| 顺序播放与手动切歌（10 月 10 日） | 播放 Oasis 专辑的 Hello，日志进入 READY/isPlaying=true；下一首切到 Roll with It，封面、音乐服务器来源标记、MP3 / 44.1 kHz / 320 kbps 与歌词显示正常 |
| 暂停与恢复（10 月 10 日） | 两次界面读取均停在 01:00，按钮显示播放；恢复后从约 60140 ms 继续，界面推进到 01:04，按钮显示暂停 |
| 播放队列（10 月 10 日） | 显示共 12 首、第 2 首 Roll with It，后续为 Wonderwall；核对当前项及可见顺序，不声称逐首媒体验证 |
| 未支持操作（10 月 10 日） | 更多菜单中下载与一起听为禁用样式，显示服务器歌曲暂不支持的原因；本轮未实际执行下载/共享，也未更改收藏或本地歌单 |
| 浏览缓存落盘（10 月 10 日） | 只读统计 Subsonic 缓存：专辑列表 7 页/210 条记录、专辑详情 9 份/87 条曲目、单曲目录 1 页/30 条，共 327 条；其中 Oasis 专辑详情为 12 条。关键词搜索持久页为 0，符合仅内存复用约定。记录数不等于去重后的全库歌曲/专辑数 |
| 下一首预取（10 月 10 日） | 系统确认 Wi-Fi 与 VPN 路径均为非计费网络；21:23:47.937 日志观察到下一首头部预取完成。同会话此前移动数据下等待条件至 30 秒预算结束，未看到完成记录；不把它记为全部计费网络/取消专项均通过 |
| 进程重启（10 月 10 日） | 已暂停后 force-stop，再进入应用，PID 从 14252 变为 19825；服务器配置、专辑列表与暂停的 Roll with It 保留。启动输出为 WARM，按进程重启记录；仍联网，不将此项写成断网冷启动或列表缓存命中率测试 |

本轮没有记录人工听感确认，不以进度和 READY 状态代替声音输出、音质或所有媒体格式验证。账号增删/改密、完整收藏/歌单撤销、长时间断网与混合平台回归沿用此前用户反馈或后续专项；最新包的这些操作没有全部逐项重做。

10 月 9 日用户遇到连接超时，手机请求与 HTTPS 探测均超时；服务器 Navidrome、Caddy、音乐挂载服务在运行，本机 API 约 0.8 ms 响应，电脑另一代理路径可达。用户随后反馈直连仍异常、恢复可用 DMIT 节点后正常，因此按访问链路波动记录，未改客户端或服务器配置；不能据此断定节点故障的具体位置，也未将其混同历史 HTTP 416。

本地证据位于源码仓库外的工作区 `artifacts/neriplayer-pr-phone-20261010/`：截图索引、verification.json、脱敏 playback-evidence.log 和 cache-summary.json。搜索/筛选原始截图另存于 `artifacts/neriplayer-pr-phone-20261009/`。缓存统计的临时数据库快照已删除，只保留服务器缓存汇总；截图没有账号密码、认证 Token 或带认证参数的请求地址。测试结束恢复自动熄屏设置，当前测试歌曲保持暂停。

## 已安装版本与本机产物

基础真机闭环对应的历史安装版本为 `2b195cc.10082055`，versionCode `26100805`，包名 `moe.ouom.neriplayer.debug`。覆盖安装保留应用数据，ADB 返回 Success 并核对手机版本。包含服务器 UI、分类搜索、浏览缓存、预取和 416 修复；本轮最新安装版本见下方警告清理构建记录。

APK：`neriplayer-server-library-search-debug.apk`。

SHA-256：`100b6414b908a0f482d7bb072e0b8f33872782b5a8c91e2f2b8bf57c6843c72a`。

本机路径：工作区 `artifacts/neriplayer-server-library-search-20261008/`（源码仓库外），包括 APK、输出元数据、构建日志、测试 XML 和 verification.json。该 JSON 的 pending 状态是当时安装后的历史快照，当前基础真机状态以本报告的后续用户反馈为准。

警告清理前的完整质量构建已保存至工作区 `artifacts/neriplayer-first-pr-20261009/`（源码仓库外）：

- APK：`neriplayer-first-pr-debug.apk`，版本 `2b195cc.10091653`，versionCode `26100901`，包名 `moe.ouom.neriplayer.debug`。
- SHA-256：`42193b518e39548d08631565d3e645d29265ab9fab1f143e9949620a769b6e2b`。
- 同目录保存输出元数据、quality.log、域依赖报告、15 个相关测试类的 XML 和 verification.json，后者记录本地检查、生产源码摘要及 APK 版本/哈希。
- 包内 manifest 与输出元数据一致；包含 HEAD `2b195cc` 及该轮工作区实现，不能仅凭提交号推定包内容。
- 当前尚未 USB 安装。后续新包回归与旧版本的基础真机反馈分别记录，不将旧包哈希用于新包。

警告清理后的最新构建保存于 `artifacts/neriplayer-server-warning-cleanup-20261009/`：

- APK：`neriplayer-server-warning-cleanup-debug.apk`，版本 `2b195cc.10091715`，versionCode `26100902`，包名 `moe.ouom.neriplayer.debug`；包内 manifest 已核对。
- SHA-256：`050673100243f3896419815c96ed2daa7ba2f13be906882914b37cf4b803435c`。
- 同目录保存构建日志、两模块 lint XML、测试 XML、警告来源对照及 verification.json；后者明确关联此前完整检查，并记录本轮未重跑的检查。
- 2026-10-09 17:25:58 已通过 USB 覆盖安装（adb install -r），ADB 返回 Success，核对手机上的 versionName 为 `2b195cc.10091715`、versionCode 为 `26100902`。应用冷启动 Status 为 ok；安装及启动记录保存在同目录 usb-install.txt 和 verification.json。
- 安装/版本/启动核对已完成；10 月 9–10 日又完成上方列出的 UI 与播放补充回归。未把这组操作记成完整专项矩阵，已有基础闭环结论仍按此前用户反馈保留。

## 历史 HTTP 416 报错

2026-10-08 17:22:07 自动切到 Take 6 Minus 3 时用户看到断线提示。日志显示 HTTP 416 被服务器拦截器提前包装为 SubsonicException，使播放器无法继续判断正常 EOF 与越界。

修复保留 416 响应头交给 Media3，正常 EOF 不误报短读，真正越界保留对应错误信号；修复已包含在后续安装版本。6 项受控测试通过。旧日志没有请求偏移与 Content-Range，具体触发原因无法确定；用户的整体真机闭环反馈不补写为该曲目逐项重播日志。

## 历史记录与后续待办

[缓存与断线记录](music-server-cache-acceptance/README.md)保留早期版本、操作清单及当时观察，旧“尚未安装/待验收”文字不代表当前状态。UI 与分类搜索的阶段记录另留本地，本报告汇总其版本及测试结论。

本地 review 还发现地址变更后的媒体缓存隔离问题：编辑保留配置 UUID，但音频和图片的资源身份不含配置修订号；地址指向另一实例且原始资源 ID 重合时，可能复用旧媒体。此项由代码路径发现，尚未完成修复或真机复现；既有自动化和现场通过记录不覆盖这个场景，发布前按开发计划处理。

脱机开关与公共刷新入口统一、能力刷新、歌词请求复用、探索/替代音源等见[开发计划](music-server-development-plan.md)。首个 PR 的当前范围见[功能说明](music-server-mvp.md)，API 的实际契约见[接口文档](music-server-api.md)。
