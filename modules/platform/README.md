# 🌐 远程平台与内容来源

`:platform` 汇集 Bilibili、网易云、YouTube Music 的服务协议、账号与凭据、平台缓存、播放候选和歌单业务，以及跨平台评论、歌词来源、元数据搜索、匹配与回退。新增平台能力应进入本库的对应职责包，并随实现维护测试。

源码保留既有包名，按协议和业务职责组织：

| 职责 | 源码包 |
| --- | --- |
| 请求、认证协议、响应解析、传输与挑战 | `api/bilibili`、`api/netease`、`api/youtube` |
| AMLL、酷狗、LrcLib 等歌词来源和歌曲元数据搜索 | `api/lyrics`、`api/search` |
| 账号持久化、平台缓存、歌单与播放候选 | `data/auth/bili`、`data/auth/netease`、`data/platform`、`data/youtube` |
| 评论来源、分页、响应映射与缓存 | `core/comment` |
| 歌词来源仓库、匹配、检索与回退 | `data/lyrics` |

`api` 是同库的协议职责。音乐平台协议消费共享模型、网络与通用能力；歌词和搜索协议还可使用歌词解析与明确列出的来源客户端。协议不反向读取账号仓库、Room 或播放器状态，账号和凭据刷新通过窄接口注入。各平台业务消费自身协议，评论可以消费 Bilibili 身份与 Bilibili/网易云协议，跨来源歌词协调使用明确列出的来源能力。同库构建不放宽这些包级依赖规则。

本库公开 `:model`、`:database` 和 `:lyrics`，基础辅助来自 `:common`、`:network`。数据库 schema 和 DAO 由数据库库维护，平台缓存与 Bilibili 跳过规则的业务适配留在本库；跨平台本地歌单编排属于 `:local`。歌词解析、时间轴和词幕输出归 `:lyrics`，歌词库不反向依赖平台。

YouTube JS 运行资产保留在 `src/main/assets/youtube`，consumer R8 规则保护 Rhino 反射入口。调整挑战、PoToken、候选、Range 和缓存时，应同时检查取消、账号指纹、缓存身份与回退边界。已有平台包名、序列化名称、凭据文件和键名保持兼容。

协议夹具、仓库、匹配与策略测试放在 `src/test`，宿主与真实服务集成另行验证。在线 smoke 仅在显式配置时运行；本地缓存命中或 JVM 结果不能证明真实账号、登录和在线播放行为。

## 🧪 验证

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :platform:testDebugUnitTest --tests "*YouTube*"
python3 -B tools_pub/quality/module_boundaries.py
```

`verifyCrap` 使用本库真实 JVM 覆盖率和共享检查范围，任意受检方法大于 9 即失败；新增网易歌单映射与同步实现自动进入整目录范围。`verifyDomainDependencies` 选择共享配置中的平台域并检查实际编译类，`check` 同时执行两个门禁。报告位于本库 `build/reports/crap` 与 `build/reports/domain-dependencies`，合并目录不能成为绕过协议与业务边界的理由。

完整模块索引、源码容量与依赖约束见仓库根目录 [README](../../README.md#模块结构)，共享门禁见 [质量工具说明](../../tools_pub/quality/README.md)。
