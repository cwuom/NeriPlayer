# 🎼 歌词同步 / Lyric synchronization

## 状态与旧数据 / State and legacy data

旧数据缺少可靠的来源标记，自动匹配与手动选择可能写入相同字段。

Legacy data lacks reliable provenance: automatic matching and manual selection can populate identical fields.

| 项目 | 规则 |
| --- | --- |
| `lyricSyncEdited` 三态语义 | `true` 表示用户确认的歌词编辑、手动匹配或保守保留的旧歌词，`false` 表示已知缓存或恢复原词，`null` 表示来源未知的旧数据；联网自动缓存不会创建编辑版本，明确的普通缓存不批量上传 |
| 旧数据兼容版本与 B 站规则 | 状态为 `null` 的完整旧歌词默认提升为兼容版本 `1`，忽略不可信的旧数值版本，防止覆盖真实的新编辑或恢复；B 站有词记录一律保留，但明确的正版本恢复仍优先；之后的用户编辑才创建新修改版本 |
| 耐久账本（Ledger）与投影 | 接收旧版远端数据时，先将未确认的完整歌词写入本地耐久账本再执行归一化，所有无法判定先后的不同版本均完整保留，写入或校验失败直接中止同步；兼容记录包含全部 6 个歌词与基线字段，并自动投影至歌单、收藏、历史与正常播放，无需手动恢复 |
| V4 迁移与升级提示 | V4 迁移自动启用可逆压缩，保留网易云、YouTube 和 B 站全部存在歧义的旧歌词候选；历史有损偏好不再参与筛选、捕获或恢复，也不再保留常驻压缩选项；升级提示仅用于确认所有设备已完成更新，目标地址升至 V4 后自动消失 |

| Aspect | Rule |
| --- | --- |
| Three-State `lyricSyncEdited` | `true` marks user-confirmed edits, manual matches, or conservatively preserved legacy lyrics, `false` marks known caches or resets, and `null` marks unknown legacy data; automatic network caches never create edit revisions, and known ordinary caches are not bulk-uploaded |
| Legacy Compatibility & Bilibili | Unknown (`null`) full lyrics receive compatibility revision `1` by default, ignoring untrusted legacy numeric revisions; Bilibili lyrics are always preserved unless superseded by an explicit positive reset revision; subsequent user edits create new edit revisions |
| Durable Ledger & Projection | Incoming ambiguous legacy payloads are committed to a local durable ledger before normalization, retaining all distinct versions and aborting sync on write or verification failures; full overrides keep all six lyric and baseline fields and project automatically into playlists, favorites, history, and playback |
| V4 Migration & Upgrade Prompt | V4 migration automatically enables reversible compression and preserves all ambiguous NetEase, YouTube, and Bilibili candidates; historical lossy preferences no longer affect filtering, capture, or restoration, and the permanent compression option is removed; the upgrade prompt only confirms that all devices are updated and disappears once the target reaches V4 |

## 版本与合并 / Revisions and merging

| 机制 | 规则 |
| --- | --- |
| 版本号递进 | `lyricSyncRevision` 独立于歌单修改时间；本地修改取 `max(当前时间, 已知最高版本 + 1)`（同时检查当前歌曲与永久记录，防止旧播放队列覆盖已同步的新状态），恢复原词使用更高版本号并设 `lyricSyncEdited = false` |
| 冲突合并裁决 | 合并时取最高版本；版本号相同时优先选择恢复标记（`false`），再按带长度边界的歌词内容键（length-delimited payload key）确定唯一结果，避免另一设备修改歌单名称、排序或重播歌曲导致旧编辑回流 |
| 持久注册表与容器精简 | `SyncData.lyricOverrides` 仅保存正版本的编辑与恢复，在用户修改或恢复成功前先写入本地记录，歌曲、歌单或历史删除后仍保留；合并时统一歌单、收藏、历史与独立记录中的同曲版本；V4 中每个歌曲身份的完整编辑歌词只在该 registry 保存一次，外层容器只携带版本号与状态标记，接收后从 registry 还原，歌词源、匹配 ID 与用户时间偏移保持为轻量同步元数据 |
| 本地基线与缓存处理 | 收到仅含缓存的记录时保留本地歌词；接收编辑记录时自动写入全文与收到的原词基线，未携带基线时保留本地原词；接收恢复标记时移除旧编辑并使用本地原词，本地无原词时等待联网重新获取 |

| Mechanism | Rule |
| --- | --- |
| Revision Progression | `lyricSyncRevision` is independent of playlist modification timestamps; local edits assign `max(current time, highest known revision + 1)` across both the active song and durable overrides, while resets assign a higher revision with `lyricSyncEdited = false` |
| Merge Tie-Breaking | Merges pick the highest revision, then prefer resets (`false`), and finally compare a length-delimited payload key for deterministic results, preventing playlist renames, reordering, or replays on other devices from reviving stale edits |
| Durable Registry & Containers | `SyncData.lyricOverrides` stores only positive edit/reset revisions, persists locally before reporting edits or resets as successful, and remains after songs, playlists, or history entries are deleted; merging unifies the same song across playlists, favorites, history, and standalone records; V4 stores full edited lyrics once per song identity in this registry while containers carry only revision and state markers and restore the full lyrics from the registry on receipt, leaving lyric source, matched song ID, and user offsets as small metadata |
| Baselines & Cache Handling | Incoming cache-only records keep local lyrics; incoming edits write complete lyrics and received baselines, retaining local originals if baselines are absent; resets remove stale edits and apply local originals or wait for a network fetch when none exist |

## 持久化与播放投影 / Persistence and playback projection

历史与播放队列通过 Room 18 到 19 的 nullable JSON 扩展列保存编辑状态和罗马音。原有列及历史 schema 保持不变，旧行缺少扩展载荷时按旧数据处理；损坏的扩展 JSON 不会静默替换为未知状态。未选中的队列歌曲仍保留用户编辑的完整歌词。已被旧版有损策略省略且没有备份的内容无法恢复。

The Room 18-to-19 migration adds nullable JSON extension columns to history and queue songs for edit state and romanization, leaving existing columns and historical schema exports unchanged. Older rows without payloads retain legacy semantics; malformed extension JSON is not silently treated as unknown state. Unselected queue songs retain their full edited lyrics. Content already omitted by historical lossy versions cannot be reconstructed without another copy.

永久记录成功改变后，当前歌曲、活动队列与随机播放恢复队列都会接收歌词投影。启动恢复也使用最新永久记录，不增加同步 mutation。用户已确认的歌词编辑优先于普通来源缓存；页面按编辑状态与版本更新歌词加载所有者，防止重置后旧异步请求重新发布旧词。

Committed overrides also refresh the active and shuffle restore queues without creating a new sync mutation. Playback restoration projects the durable override registry before publishing the queue. Confirmed edits take precedence over ordinary preferred-source caches; revision changes replace the lyric loading owner so stale asynchronous results cannot restore old edits.

| 环节 | 行为与内存边界 |
| --- | --- |
| 按需流式投影 | 播放器逐条扫描永久记录，仅保留当前播放歌曲、活动队列与随机恢复队列所需的目标身份版本，且必须在完整通过 EOF 与校验和检查后才应用投影 |
| 队列变更响应 | 扫描期间若队列新增歌曲身份则触发重新扫描；仅重排已有身份时只重新核对队列快照 |
| 内存边界声明 | 完整同步会话与歌词编辑保存仍需在内存中物化全量 registry，因此播放端的流式扫描优化并不意味着千万级记录规模的完整同步流程具备有界内存 |

| Stage | Behavior and Memory Boundaries |
| --- | --- |
| Streaming Playback Projection | Playback scans the durable registry one record at a time, retaining only identities required by the current song, the active queue, and the shuffle-restore queue, and projects only after full EOF and checksum verification |
| Queue Mutation Handling | Adding new song identities to a queue during a scan triggers a fresh scan, whereas reordering existing identities only rechecks the queue snapshot |
| Memory Boundary Scope | Full sync sessions and edit persistence still materialize the entire registry in memory, so playback streaming does not make the complete ten-million-record sync workflow memory-bounded |

## 旧来源迁移 / Legacy source migration

传统云端数据迁移时，来源不明的旧全文另存为不可变兼容记录；V3 升级为 V4 时完整转码该来源并保留来源回执身份。新设备首次接入时完整校验、耐久保存并投影到正常歌词记录，成功后保存按同步地址隔离的回执。后续无需重复写入兼容记录，但仍验证完整远端对象引用，避免有效本地缓存掩盖云端删除或损坏。兼容版本低于后续用户编辑与恢复，明确的编辑或恢复仍优先。旧客户端再写可变旧文件不会改变已冻结来源。

Traditional migration preserves every unknown lyric candidate in an immutable source. V3-to-V4 conversion retains the original receipt identity and all variants. New devices validate, persist, and project compatibility overrides into normal containers. A durable receipt is isolated by sync target; subsequent reads avoid repeated recovery but still validate the complete remote object closure. Higher explicit edits/resets retain priority. Later writes to the mutable legacy file cannot alter the frozen source.

## 兼容边界 / Compatibility boundaries

该版本采用确定性的最后修改版本合并，不是服务器赋予的全局因果顺序。跨设备时钟偏差可能影响同时离线编辑的胜者；旧客户端不了解恢复标记，混用旧客户端不能提供同等的防回流保证。使用支持此协议的客户端可保证版本合并确定、恢复标记优先、重复同步幂等。

Merge follows deterministic last-modified revisions rather than a server-issued global causal order. Clock differences can affect which simultaneous offline edit wins. Older clients do not understand reset markers and cannot provide the same protection against stale edits returning. Clients supporting this protocol retain deterministic merge results, reset precedence, and idempotent repeated sync.
