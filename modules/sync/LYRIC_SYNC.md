# 🎼 歌词同步 / Lyric synchronization

同步歌曲的 `lyricSyncEdited` 使用三态：`true` 是用户确认的歌词修改、手动匹配或为防丢失而保留的旧歌词，`false` 是明确的缓存或恢复，`null` 是尚未迁移的旧数据。普通联网获取不会创建编辑版本。编辑与保留的旧歌词同步完整原文、翻译、罗马音及三种原词基线；明确的普通缓存仍不批量上传。

`lyricSyncRevision` 独立于歌单修改时间。本地修改使用 `max(当前时间, 已知最高版本 + 1)`，已知版本同时读取当前歌曲和永久记录，避免旧播放队列覆盖同步后已知的新状态。恢复原词使用更高版本和 `false` 标记。合并取最高版本；相同版本先选择恢复标记，再按带长度边界的歌词内容键确定结果。恢复标记持续保留，旧编辑不会因为另一设备修改歌单名称、排序或重新播放歌曲而回流。

`SyncData.lyricOverrides` 是独立永久记录，只保存正版本的编辑与恢复。用户修改和恢复成功前先写入本地记录，删除歌曲、删除歌单和清空历史不会清除这些记录。合并时统一所有歌单、收藏、历史与独立记录里的同歌曲歌词版本。V4 载荷只在这份记录中保存完整修改歌词，各容器仅保存版本与标记，接收后按独立记录恢复，避免同一首歌的修改歌词重复传输。

接收没有歌词全文的缓存记录时保留本地缓存。接收编辑时自动写入全文与收到的原词基线，未携带基线时保留本地原词作为恢复来源；接收恢复标记时移除旧编辑，使用本地原词缓存，缺少原词的设备等待联网获取。歌词源、匹配歌曲 ID 与用户偏移仍是小型同步元数据。

旧数据没有可靠的来源标记，自动匹配和手动选择可能写入完全相同的字段。默认将 `null` 的完整歌词提升到兼容版本 `1`；旧未知记录中的数值版本不能证明编辑先后，不借用该数值覆盖真实的新编辑或恢复。B 站有歌词一律保留，明确的正版本恢复仍优先。接收旧远端时，先把未确认的全文耐久保存到本地独立记录，再归一化同步数据；多个无法确定先后的版本均保留，写入或校验失败会中止同步。兼容记录进入正常歌单、收藏、历史及永久歌词投影，拉取后自动使用，无需恢复选择。用户之后编辑才创建新的修改版本。

V4 迁移自动启用可逆压缩并完整保留来源未知的旧歌词，包括网易云、YouTube 和 B 站的所有候选。升级弹窗只确认所有设备已更新；迁移完成后升级入口自动消失，不再保留常驻压缩选项。历史有损偏好不会再用于筛选、捕获或恢复歌词，已经被旧版本省略且没有备份的内容仍无法凭空恢复。

历史与播放队列通过 Room 18 到 19 的 nullable JSON 扩展列保存编辑状态和罗马音。原有列及历史 schema 保持不变，旧行缺少扩展载荷时按旧数据处理；损坏的扩展 JSON 不会静默替换为未知状态。未选中的队列歌曲仍保留用户编辑的完整歌词。

传统云端数据迁移时，来源不明的旧全文另存为不可变兼容记录；V3 升级为 V4 时完整转码该来源并保留来源回执身份。新设备首次接入时完整校验、耐久保存并投影到正常歌词记录，成功后保存按同步地址隔离的回执。后续无需重复写入兼容记录，但仍验证完整远端对象引用，避免有效本地缓存掩盖云端删除或损坏。兼容版本低于后续用户编辑与恢复，明确的编辑或恢复仍优先。旧客户端再写可变旧文件不会改变已冻结来源。

永久记录成功改变后，当前歌曲、活动队列与随机播放恢复队列都会接收歌词投影。启动恢复也使用最新永久记录，不增加同步 mutation。用户已确认的歌词编辑优先于普通来源缓存；页面按编辑状态与版本更新歌词加载所有者，防止重置后旧异步请求重新发布旧词。

播放器逐条扫描永久记录，只保留这三类目标身份需要的版本；完整检查 EOF 和校验和后才投影。扫描期间新增队列歌曲会重新读取，重排相同身份只重新确认队列。同步会话与编辑保存仍有全量 registry 成本，这项优化不能证明整个千万记录同步过程内存有界。

该版本采用确定性的最后修改版本合并，不是服务器赋予的全局因果顺序。跨设备时钟偏差可能影响同时离线编辑的胜者；旧客户端不了解恢复标记，混用旧客户端不能提供同等的防回流保证。使用支持此协议的客户端可保证版本合并确定、恢复标记优先、重复同步幂等。

`lyricSyncEdited` distinguishes edits and conservatively preserved legacy lyrics, known caches/resets, and unknown legacy data. Unknown full lyrics receive compatibility revision 1 by default, ignoring untrusted legacy numeric revisions. Bilibili lyrics are always preserved unless a positive reset explicitly supersedes them. Full overrides retain all six lyric and baseline fields, are written once per identity, and restore normal playback automatically. Distinct unknown versions are also committed to a durable ledger before normalization; failures abort sync. Higher edit/reset revisions prevent stale legacy lyrics from returning. V4 migration preserves all ambiguous legacy candidates with reversible compression. Historical lossy preferences are no longer applied. The upgrade prompt only confirms that every device has been updated and disappears when that target reaches V4.

Committed overrides also refresh the active and shuffle restore queues without creating a new sync mutation. Playback restoration projects the durable override registry before publishing the queue. Confirmed edits take precedence over ordinary preferred-source caches; revision changes replace the lyric loading owner so stale asynchronous results cannot restore old edits.

Traditional migration preserves every unknown lyric candidate in an immutable source. V3-to-V4 conversion retains the original receipt identity and all variants. New devices validate, persist, and project compatibility overrides into normal containers. A durable receipt is isolated by sync target; subsequent reads avoid repeated recovery but still validate the complete remote object closure. Higher explicit edits/resets retain priority. Later writes to the mutable legacy file cannot alter the frozen source.

Playback scans the durable registry one record at a time and retains only identities needed by the current song and both queues. The entire document and checksum must pass before projection. New queue identities trigger another scan; reordering existing identities only rechecks the queue snapshot. Full sync and edit persistence still materialize the registry, so this optimization does not establish bounded memory for the complete ten-million-record sync flow.
