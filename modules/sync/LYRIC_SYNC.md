# 🎼 歌词同步 / Lyric synchronization

同步歌曲的 `lyricSyncEdited` 使用三态：`true` 是用户确认的歌词修改或手动匹配，`false` 是明确的缓存或恢复，`null` 是尚未迁移的旧数据。普通联网获取不会创建编辑版本。用户修改才同步完整原文、翻译和罗马音；`originalLyric` 与 `originalTranslatedLyric` 仅留在本地，不进入新同步载荷。

`lyricSyncRevision` 独立于歌单修改时间。本地修改使用 `max(当前时间, 已知最高版本 + 1)`，已知版本同时读取当前歌曲和永久记录，避免旧播放队列覆盖同步后已知的新状态。恢复原词使用更高版本和 `false` 标记。合并取最高版本；相同版本先选择恢复标记，再按带长度边界的歌词内容键确定结果。恢复标记持续保留，旧编辑不会因为另一设备修改歌单名称、排序或重新播放歌曲而回流。

`SyncData.lyricOverrides` 是独立永久记录，只保存正版本的编辑与恢复。用户修改和恢复成功前先写入本地记录，删除歌曲、删除歌单和清空历史不会清除这些记录。合并时统一所有歌单、收藏、历史与独立记录里的同歌曲歌词版本。v3 载荷只在这份记录中保存完整修改歌词，各容器仅保存版本与标记，接收后按独立记录恢复，避免同一首歌的修改歌词重复传输。

接收没有歌词全文的缓存记录时保留本地缓存。接收编辑时保留本地原词作为恢复来源；接收恢复标记时移除旧编辑，使用本地原词缓存，缺少原词的设备等待联网获取。歌词源、匹配歌曲 ID 与用户偏移仍是小型同步元数据。

旧数据没有可靠的来源标记，自动匹配和手动选择可能写入完全相同的字段。`null` 不会自动转换成编辑，也不会创建兼容版本。旧本地歌曲仍保留全文；接收旧远端时，先把未确认的全文耐久保存到本地独立记录，再归一化同步数据。写入或校验失败会中止同步。该记录不参与上传，多个无法确定先后的版本均保留。播放器在歌曲没有现有全文且没有正版本编辑或恢复时自动使用旧歌词，不增加选择或确认步骤，旧歌词不能覆盖明确的恢复。已确认的用户修改则在接收后自动保存并更新歌词，仅用户再次编辑才创建新的上传版本。

历史与播放队列通过 Room 18 到 19 的 nullable JSON 扩展列保存编辑状态和罗马音。原有列及历史 schema 保持不变，旧行缺少扩展载荷时按旧数据处理；损坏的扩展 JSON 不会静默替换为未知状态。未选中的队列歌曲仍保留用户编辑的完整歌词。

永久记录成功改变后，当前歌曲、活动队列与随机播放恢复队列都会接收歌词投影。启动恢复也使用最新永久记录，不增加同步 mutation。用户已确认的歌词编辑优先于普通来源缓存；页面按编辑状态与版本更新歌词加载所有者，防止重置后旧异步请求重新发布旧词。

播放器逐条扫描永久记录，只保留这三类目标身份需要的版本；完整检查 EOF 和校验和后才投影。扫描期间新增队列歌曲会重新读取，重排相同身份只重新确认队列。同步会话与编辑保存仍有全量 registry 成本，这项优化不能证明整个千万记录同步过程内存有界。

该版本采用确定性的最后修改版本合并，不是服务器赋予的全局因果顺序。跨设备时钟偏差可能影响同时离线编辑的胜者；旧客户端不了解恢复标记，混用旧客户端不能提供同等的防回流保证。使用支持此协议的客户端可保证版本合并确定、恢复标记优先、重复同步幂等。

`lyricSyncEdited` distinguishes confirmed user edits, known caches/resets, and unconfirmed legacy data. New payloads carry full original, translated, and romanized lyrics only for confirmed user edits; baseline lyric caches stay local. Lyric revisions merge independently from playlist timestamps, and persistent higher revision reset markers prevent stale edits from returning. Missing ordinary lyrics preserve local caches. Unconfirmed old cloud lyrics are committed to a separate local ledger before normalization; a failed commit aborts sync. The ledger retains distinct versions, stays out of uploads, and automatically supplies local playback only when there is no current lyric payload or positive revision. Received confirmed edits are saved and displayed automatically without a selection or confirmation step; only a subsequent user edit creates a new upload revision. This is deterministic last revision conflict resolution; clocks and legacy clients remain compatibility boundaries.

Committed overrides also refresh the active and shuffle restore queues without creating a new sync mutation. Playback restoration projects the durable override registry before publishing the queue. Confirmed edits take precedence over ordinary preferred-source caches; revision changes replace the lyric loading owner so stale asynchronous results cannot restore old edits.

Playback scans the durable registry one record at a time and retains only identities needed by the current song and both queues. The entire document and checksum must pass before projection. New queue identities trigger another scan; reordering existing identities only rechecks the queue snapshot. Full sync and edit persistence still materialize the registry, so this optimization does not establish bounded memory for the complete ten-million-record sync flow.
