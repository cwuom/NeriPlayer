package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式用的歌曲身份键：队列去重、「不喜欢」名单与插入记录都按它比较同一首歌。
 */
object HeartbeatSongIdentity {

    /**
     * 歌曲身份键。
     *
     * 优先使用 [SongItem.sourceStableKey]（本地歌曲/多来源场景下更准确），
     * 回退到「播放来源:歌曲 id」，再回退到「名称|歌手」。
     *
     * 播放来源只看播放渠道，不看歌词来源：换一个歌词服务不应该改变同一首歌的身份，
     * 否则已存的不喜欢与队列去重都会对不上。
     *
     * 稳定键会被规范化成「播放来源:歌曲 id」：统计库与同步写的是
     * 「数字id|来源|音频标识」，而播放中的歌大多没有该字段、按「播放来源:id」计算。
     * 两者不统一的话，同一首歌在「不喜欢」名单与播放队列里会算出两个不同的键：
     * 只要一边带这个字段、另一边不带，不喜欢与队列去重就查不到对方存的记录。
     */
    fun identityKey(song: SongItem): String {
        song.sourceStableKey?.takeIf { it.isNotBlank() }?.let { sourceKey ->
            canonicalKeyFromSource(sourceKey)?.let { return it }
            return sourceKey
        }
        if (song.id > 0L) return "${playbackSourceKey(song)}:${song.id}"
        return "${song.name.trim().lowercase()}|${song.artist.trim().lowercase()}"
    }

    /**
     * 这首歌可能被记录过的全部身份键，含规范化之前的旧格式。
     *
     * 本地存着用户标记的「不喜欢」名单。老版本把统计库/下载来的歌
     * 按「数字id|来源|音频标识」写进去，[identityKey] 规范化之后同一首歌算的是
     * 「播放来源:id」，单键比较会让这些历史标记失效。读取时两种键都查一遍，
     * 用户不需要重新标记一遍。
     */
    fun identityKeyAliases(song: SongItem): Set<String> {
        val canonical = identityKey(song)
        val legacy = song.sourceStableKey?.trim()?.takeIf { it.isNotBlank() }
        return if (legacy != null && legacy != canonical) setOf(canonical, legacy) else setOf(canonical)
    }

    /**
     * 把「数字id|来源|音频标识」形式的稳定键还原成「播放来源:数字id」。
     *
     * 识别不出该格式时返回 `null`，由调用方原样透传（例如本地文件的
     * `local:/music/a.flac`），避免把别的键格式误读成同一首歌。
     */
    private fun canonicalKeyFromSource(sourceKey: String): String? {
        val firstSeparator = sourceKey.indexOf('|')
        if (firstSeparator <= 0) return null
        val secondSeparator = sourceKey.indexOf('|', firstSeparator + 1)
        if (secondSeparator <= firstSeparator) return null
        val id = sourceKey.substring(0, firstSeparator).toLongOrNull() ?: return null
        val channel = sourceKey.substring(firstSeparator + 1, secondSeparator).trim().lowercase()
        if (channel.isEmpty()) return null
        return "${canonicalSourceName(channel)}:$id"
    }

    /**
     * 稳定键里的来源段与 [SongItem.playbackSourceKey] 的取值对齐。
     *
     * [SongItem.playbackSourceKey] 只认 `bilibili` / `youtubeMusic` / `netease` / `local`
     * 四个渠道值，其余一律兜底成本地歌：服务器歌曲的来源段是 `subsonic`、
     * 本地文件是 `__local_files__`，它们播放时都算 `local`。
     * 这里必须跟着走同样的兜底，否则同一首歌两侧仍会算出不同的键。
     */
    private fun canonicalSourceName(channel: String): String = when (channel) {
        "bilibili", "bili" -> "bili"
        "youtube_music", "youtubemusic", "youtube", "ytmusic" -> "youtube"
        "netease" -> "netease"
        else -> "local"
    }

    /**
     * 播放来源前缀，见 [playbackSourceKey]。
     *
     * 实现放在 model 层：判断平台要看各平台的明细字段，而本模块的依赖边界
     * 不允许直接引用那些类型。
     */
    private fun playbackSourceKey(song: SongItem): String = song.playbackSourceKey()
}
