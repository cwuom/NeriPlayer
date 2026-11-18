# 🎼 歌词同步 / Lyric synchronization

[English](#english) | [中文](#中文)

## 中文

### 🏷️ 状态与旧数据

旧数据缺少可靠的来源标记，自动匹配与手动选择可能写入相同字段。

#### `lyricSyncEdited` 三态

- `true`：用户确认的歌词编辑、手动匹配，或保守保留的旧歌词
- `false`：已知缓存或恢复的原词
- `null`：来源未知的旧数据
- 联网自动缓存不创建编辑版本
- 明确的普通缓存不批量上传

#### 旧数据兼容版本与 B 站规则

- 状态为 `null` 的完整旧歌词，默认提升为兼容版本 `1`
- 忽略不可信的旧数值版本，防止覆盖真实的新编辑或恢复
- B 站有词记录一律保留，明确的正版本恢复仍优先
- 之后的用户编辑才创建新的修改版本

#### 耐久账本（Ledger）与投影

- 接收旧版远端数据时，先把未确认的完整歌词写入本地耐久账本，再执行归一化
- 无法判定先后的不同版本全部完整保留
- 写入或校验失败时，直接中止同步
- 兼容记录包含全部 6 个歌词与基线字段
- 自动投影至歌单、收藏、历史与正常播放，无需手动恢复

#### V4 迁移与升级提示

- V4 迁移自动启用可逆压缩
- 保留网易云、YouTube 和 B 站全部存在歧义的旧歌词候选
- 历史有损偏好已停用，不参与筛选、捕获或恢复，常驻压缩选项已移除
- 升级提示用于确认所有设备已完成更新，目标地址升至 V4 后自动消失

### 🔀 版本与合并

#### 版本号递进

- `lyricSyncRevision` 独立于歌单修改时间
- 本地修改取 `max(当前时间, 已知最高版本 + 1)`
- 同时检查当前歌曲与永久记录，防止旧播放队列覆盖已同步的新状态
- 恢复原词使用更高版本号，并设 `lyricSyncEdited = false`

#### 冲突合并裁决

- 合并时取最高版本
- 版本号相同时，优先选择恢复标记（`false`）
- 仍相同时，按带长度边界的歌词内容键（length-delimited payload key）确定唯一结果
- 另一设备修改歌单名称、排序或重播歌曲，不会让旧编辑回流

#### 持久注册表与容器精简

- `SyncData.lyricOverrides` 仅保存正版本的编辑与恢复
- 用户修改或恢复成功前，先写入本地记录
- 歌曲、歌单或历史删除后，记录仍保留
- 合并时统一歌单、收藏、历史与独立记录中的同曲版本
- V4 中每个歌曲身份的完整编辑歌词只在该 registry 保存一次
- 外层容器只携带版本号与状态标记，接收后从 registry 还原
- 歌词源、匹配 ID 与用户时间偏移作为轻量同步元数据

#### 本地基线与缓存处理

- 收到仅含缓存的记录：保留本地歌词
- 收到编辑记录：写入全文与收到的原词基线，未携带基线时保留本地原词
- 收到恢复标记：移除旧编辑并使用本地原词，本地无原词时等待联网重新获取

### 💾 持久化与播放投影

#### 持久化

- 历史与播放队列通过 Room 18 到 19 的 nullable JSON 扩展列保存编辑状态和罗马音
- 原有列及历史 schema 不变
- 旧行缺少扩展载荷时按旧数据处理
- 损坏的扩展 JSON 不会静默替换为未知状态
- 未选中的队列歌曲保留用户编辑的完整歌词
- 已被旧版有损策略省略且没有备份的内容无法恢复

#### 播放投影

- 永久记录成功改变后，当前歌曲、活动队列与随机播放恢复队列都接收歌词投影
- 启动恢复同样使用最新永久记录，不增加同步 mutation
- 用户已确认的歌词编辑优先于普通来源缓存
- 页面按编辑状态与版本更新歌词加载所有者，防止重置后旧异步请求重新发布旧词

#### 按需流式投影

- 播放器逐条扫描永久记录
- 仅保留当前歌曲、活动队列与随机恢复队列所需的目标身份版本
- 完整通过 EOF 与校验和检查后才应用投影

#### 队列变更响应

- 扫描期间队列新增歌曲身份：触发重新扫描
- 仅重排已有身份：只重新核对队列快照

#### 内存边界

- 完整同步会话与歌词编辑保存仍在内存中物化全量 registry
- 播放端的流式扫描不意味着千万级记录规模的完整同步流程内存有界

### 📜 旧来源迁移

- 传统云端数据迁移时，来源不明的旧全文另存为不可变兼容记录
- V3 升级为 V4 时，完整转码该来源并保留来源回执身份
- 新设备首次接入时，完整校验、耐久保存并投影到正常歌词记录
- 成功后保存按同步地址隔离的回执
- 后续读取无需重复写入兼容记录，但仍验证完整远端对象引用，避免有效本地缓存掩盖云端删除或损坏
- 兼容版本低于后续用户编辑与恢复，明确的编辑或恢复优先
- 旧客户端再写可变旧文件，不会改变已冻结的来源

### 🧭 兼容边界

- 合并采用确定性的最后修改版本，不是服务器赋予的全局因果顺序
- 跨设备时钟偏差可能影响同时离线编辑的胜者
- 旧客户端不识别恢复标记，无法提供同等的防回流保证
- 使用支持此协议的客户端时，版本合并结果确定，恢复标记优先，重复同步幂等

## English

### 🏷️ State and legacy data

Legacy data lacks reliable provenance, so automatic matching and manual selection can populate identical fields.

#### Three-state `lyricSyncEdited`

- `true`: user-confirmed edits, manual matches, or conservatively preserved legacy lyrics
- `false`: known caches or reset original lyrics
- `null`: legacy data of unknown origin
- Automatic network caches never create edit revisions
- Known ordinary caches are not bulk-uploaded

#### Legacy compatibility revision and Bilibili rules

- Unknown (`null`) full lyrics receive compatibility revision `1` by default
- Untrusted legacy numeric revisions are ignored, so they cannot overwrite genuine new edits or resets
- Bilibili lyric records are always preserved, and an explicit positive reset revision still takes precedence
- Only later user edits create new edit revisions

#### Durable ledger and projection

- Incoming legacy remote data first commits its unconfirmed full lyrics to a local durable ledger, then normalizes
- Distinct versions with no determinable order are all retained in full
- A write or verification failure aborts the sync
- Compatibility records carry all 6 lyric and baseline fields
- They project automatically into playlists, favorites, history, and playback, with no manual recovery

#### V4 migration and upgrade prompt

- V4 migration enables reversible compression automatically
- All ambiguous NetEase, YouTube, and Bilibili legacy lyric candidates are preserved
- The historical lossy preference is retired and takes no part in filtering, capture, or restoration, and the permanent compression option is removed
- The upgrade prompt confirms that all devices are updated, and disappears once the target reaches V4

### 🔀 Revisions and merging

#### Revision progression

- `lyricSyncRevision` is independent of playlist modification timestamps
- Local edits assign `max(current time, highest known revision + 1)`
- Both the active song and durable overrides are checked, so an old playback queue cannot overwrite newer synced state
- Resets assign a higher revision with `lyricSyncEdited = false`

#### Merge tie-breaking

- Merges pick the highest revision
- On equal revisions, a reset marker (`false`) wins
- If still tied, a length-delimited payload key gives a single deterministic result
- Renaming or reordering a playlist, or replaying a song on another device, does not revive stale edits

#### Durable registry and slimmer containers

- `SyncData.lyricOverrides` stores only positive edit and reset revisions
- Edits and resets are written locally before being reported as successful
- Records remain after songs, playlists, or history entries are deleted
- Merging unifies the same song across playlists, favorites, history, and standalone records
- V4 stores the full edited lyrics once per song identity in this registry
- Outer containers carry only the revision and state markers, and restore the full lyrics from the registry on receipt
- Lyric source, matched song ID, and user offsets stay as small sync metadata

#### Baselines and cache handling

- Incoming cache-only record: keeps local lyrics
- Incoming edit record: writes the full lyrics and received original baselines, and keeps local originals when no baseline is carried
- Incoming reset marker: removes stale edits and applies local originals, or waits for a network fetch when none exist

### 💾 Persistence and playback projection

#### Persistence

- History and queue songs store edit state and romanization in nullable JSON extension columns added by the Room 18-to-19 migration
- Existing columns and historical schema exports are unchanged
- Older rows without payloads follow legacy semantics
- Malformed extension JSON is never silently treated as unknown state
- Unselected queue songs retain their full edited lyrics
- Content already omitted by historical lossy versions cannot be reconstructed without another copy

#### Playback projection

- After a durable override changes, the current song, active queue, and shuffle restore queue all receive the lyric projection
- Startup restoration also uses the latest durable overrides, without creating a new sync mutation
- Confirmed edits take precedence over ordinary preferred-source caches
- Pages update the lyric loading owner by edit state and revision, so stale asynchronous results cannot restore old lyrics after a reset

#### Streaming projection on demand

- Playback scans the durable registry one record at a time
- It retains only the target identity revisions needed by the current song, active queue, and shuffle restore queue
- The projection is applied only after full EOF and checksum verification

#### Queue mutation handling

- A new song identity added to the queue during a scan: triggers a fresh scan
- Reordering existing identities only: rechecks the queue snapshot

#### Memory boundary

- Full sync sessions and edit persistence still materialize the entire registry in memory
- Playback streaming does not make the complete ten-million-record sync workflow memory-bounded

### 📜 Legacy source migration

- Traditional cloud migration stores unknown-origin legacy full lyrics as an immutable compatibility source
- V3-to-V4 conversion transcodes that source in full and keeps its receipt identity
- A new device validates, persists, and projects it into normal lyric records on first connection
- On success it saves a receipt isolated by sync target
- Later reads skip rewriting compatibility records but still validate the complete remote object closure, so a valid local cache cannot hide remote deletion or corruption
- Compatibility revisions rank below later user edits and resets, and explicit edits or resets take precedence
- Later writes by older clients to the mutable legacy file do not change the frozen source

### 🧭 Compatibility boundaries

- Merging follows deterministic last-modified revisions, not a server-issued global causal order
- Clock differences across devices can affect which simultaneous offline edit wins
- Older clients do not recognize reset markers and cannot give the same protection against stale edits returning
- With clients supporting this protocol, merge results are deterministic, reset markers take precedence, and repeated sync is idempotent
