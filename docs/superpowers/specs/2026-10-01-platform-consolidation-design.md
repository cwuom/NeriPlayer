# 平台模块进一步收敛

状态：实施、验证与独立审查已完成

## 最终结构

本设计接续已验证的 22 库结构，按用户最新要求收敛到 14 个真实 Gradle 库。这里的 platform 是单一构建模块，不是包含多个细碎库的目录分类。原 api 网络客户端与平台账号、缓存和歌单仓库共同归入 platform；api 保留为源码内部的协议职责包，不再单独登记构建节点。

| 模块 | 职责 |
| --- | --- |
| common | 通用工具、日志、资源与测试夹具 |
| network | 共享 HTTP、Range 与 Web 登录基础能力 |
| model | 共享数据契约，不依赖项目实现 |
| database | Room、DAO、实体、schema 与历史升级 |
| storage | 可注入的存储统计和清理能力 |
| platform | Bilibili、网易云、YouTube 客户端与账号、缓存、歌单、评论，以及 QQ/酷狗/LrcLib/AMLL 等远程歌词来源、搜索匹配和仓库编排 |
| lyrics | 歌词解析、转换、共享偏移规则与 Lyricon/SuperLyric 词幕输出 |
| playback:logic | 播放 policy、运行协调、音频计算与队列规则 |
| playback:runtime | 播放引擎、服务、USB、系统音效与宿主适配 |
| download:logic | 可复用的下载规则和基础存储工具 |
| download:runtime | 下载执行、传输、恢复、Room 队列、Worker 与服务 |
| local | 本地设置、媒体、歌单、统计、备份与宿主接入 |
| sync | GitHub/WebDAV 传输、凭据、身份、同步会话与合并 |
| listentogether | 一起听协议、传输与会话 |

单个模块目录为 `modules/<领域>`，播放和下载仍保留两个有实际隔离价值的子模块。app、KSP、上游歌词库和构建逻辑不计入这 14 个库。

## 合并范围与依赖

将 api:bilibili、api:netease、api:youtube、api:metadata、bilibili、netease、youtube、comments 合并为 platform。lyrics:runtime 的 data/lyrics/matching、repository、search 全部生产源码和测试也归平台。移除重复构建节点，不保留空壳或别名。

歌词远程编排迁出后，原 `metadata -> lyrics:parser` 与 `lyrics:runtime -> metadata` 的循环约束消失。剩余 parser/runtime 合并为 lyrics，平台依赖歌词解析，歌词通过窄端口接收已解析歌词而不依赖平台。platform 保留 api(model)、api(database)、api(lyrics) 的必要类型可见性，其他通用能力作为 implementation。

播放逻辑被运行模块消费，本地数据还复用下载逻辑；将这些逻辑并入运行模块会引入反向宿主依赖，因此继续保留真实边界。model、database、network、storage 均有多个消费者与明确职责，不为减少计数而继续混合。

## 内部职责门禁

合并 Gradle 节点不意味着放开包依赖。保留全部 43 个已有字节码域，补充平台的精确允许列表与负面回归：

- 各平台 API 客户端只引用自身协议、原有共享模型和通用基础，不反向读取账号、缓存、Room 或其他平台业务
- 歌词和搜索客户端可以复用网易云客户端与歌词解析，不调用远程仓库、播放器或词幕输出
- 各平台业务访问自身 API 与原有通用能力，跨平台聚合集中在明确的评论/歌词职责
- 歌词域不引用平台，远程歌词匹配不引用词幕 SDK、播放器宿主或全局应用容器

原有源文件容量、包路径、模型零实现依赖、无环、应用容器限制、模块登记一致性、生成缓存识别和 CRAP 阈值均保留。完整目录移动与共享构建登记串行执行，互不重叠的质量工具和文档任务可以并行。

## 兼容与验证

保留生产 Kotlin/Java 包名、类全名、接口与可见性。源码和测试字节不变迁移，不改变存储格式、Room 版本、序列化字段、Worker 类名或网络行为。平台新 namespace 为 moe.ouom.neriplayer.platform，歌词沿用原解析库 namespace；迁移源码没有旧平台 R/BuildConfig 引用。

保留 serialization、parcelize、所有必要依赖、YouTube 两份 JS assets 和 Rhino consumer rules。词幕 Manifest 的四项 metadata、两个标签和 SDK consumer keep 规则继续属于歌词；200 ms 推送、400 ms 显示预推和请求代次语义保持。

同步 registry、依赖、覆盖率、源码定位夹具、IDE、CI、双语文档和模块 README。Kotlin internal JVM 方法后缀只能按新 JaCoCo 产物精确更新，不使用通配选择器绕过范围检查。

验收运行完整 Python 回归、边界检查、14 库与 app JVM 测试、CRAP、领域依赖、lint、构建逻辑、Debug/Release 和设备测试 APK 构建，并在独立 API 37 模拟器运行受影响集成测试。实际第三方词幕服务展示与实体 USB DAC 仍单独列为未验证。用户已授权按历史 Conventional Commit 风格提交；最终验证后精确暂存，不推送，保留无关改动。

## 验收结果

最终登记为 14 个自有库。第二轮基线的 4136 个文件全部可定位，1710 个生产 Kotlin/Java 文件内容与包名保持；第一轮 3339 个基线文件经两轮映射也无丢失，合并后的冗余构建配置已在工作区外保留备份。

保留原 43 个字节码域并新增 16 个域，最终 59 域零违规。`local` 的 `BiliVideoSkipRepositoryProvider` 通过完整类名识别为宿主装配例外，仅排除其自身和生成内部类；同包仓库与引用该宿主的仓库继续受检查。原 CRAP 精确选择器仅同步实际编译产生的模块后缀，覆盖范围和阈值不降低。

实际验证通过：完整 150 项 Python 回归；工作区 JVM 测试 7594 项中 7592 通过、2 项已有在线 smoke 未启用；所有独立 CRAP/领域检查与 `verifyModularization` 聚合检查；Debug、未签名 Release 和四个 AndroidTest APK 构建；独立 API 37 模拟器上的数据库 3 项、本地数据 18 项、播放 22 项和应用升级 42 项集成测试。Release 包的词幕四项 metadata、两个标签、SDK/Rhino R8 规则与两份 YouTube assets 均已核对。

独立审查确认迁移所有权、原有门禁范围和精确暂存范围，无确认问题。无关 IDE、导入排序、原有额外测试与生成缓存均保留且排除提交；测试用模拟器已停止。未验证真实在线账号、第三方词幕服务展示及实体 USB DAC，未操作实体设备。
