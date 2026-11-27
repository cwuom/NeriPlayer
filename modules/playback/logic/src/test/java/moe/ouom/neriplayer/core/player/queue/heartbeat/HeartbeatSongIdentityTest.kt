package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HeartbeatSongIdentityTest {

    // ---- 身份键 ----

    @Test
    fun `identity key prefers sourceStableKey`() {
        val withKey = song(2, "x").copy(sourceStableKey = "local:/music/a.flac")
        assertEquals("local:/music/a.flac", HeartbeatSongIdentity.identityKey(withKey))
    }

    @Test
    fun `identity key falls back to id then to name and artist`() {
        assertEquals("local:42", HeartbeatSongIdentity.identityKey(song(42, "x")))

        val noId = song(0, "Name").copy(artist = "Artist")
        assertEquals("name|artist", HeartbeatSongIdentity.identityKey(noId))
    }

    @Test
    fun `identity key ignores lyric source so switching providers keeps the key`() {
        val plain = song(42, "x")
        val withNeteaseLyrics = plain.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC)
        val withQqLyrics = plain.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)

        assertEquals(HeartbeatSongIdentity.identityKey(plain), HeartbeatSongIdentity.identityKey(withNeteaseLyrics))
        assertEquals(HeartbeatSongIdentity.identityKey(plain), HeartbeatSongIdentity.identityKey(withQqLyrics))
    }

    @Test
    fun `identity key uses the channel value not its mere presence`() {
        val netease = song(123, "x").copy(channelId = "netease")
        val bili = song(123, "x").copy(channelId = "bilibili")

        assertEquals("netease:123", HeartbeatSongIdentity.identityKey(netease))
        assertEquals("bili:123", HeartbeatSongIdentity.identityKey(bili))
        assertNotEquals(HeartbeatSongIdentity.identityKey(netease), HeartbeatSongIdentity.identityKey(bili))
    }

    @Test
    fun `identity key normalizes the stats stable key to the playing song key`() {
        // 统计库与同步写的是「数字id|来源|音频标识」，播放中的歌没有该字段、
        // 按「播放来源:id」计算；两边必须算出同一个键，否则「不喜欢」与队列去重命中不了
        val fromStats = song(39175763, "x").copy(sourceStableKey = "39175763|netease|")
        val playing = song(39175763, "x").copy(channelId = "netease")

        assertEquals("netease:39175763", HeartbeatSongIdentity.identityKey(fromStats))
        assertEquals(HeartbeatSongIdentity.identityKey(playing), HeartbeatSongIdentity.identityKey(fromStats))
    }

    @Test
    fun `identity key normalizes the local file stable key to the local prefix`() {
        val fromStats = song(42, "x").copy(sourceStableKey = "42|__local_files__|/song.flac")

        assertEquals("local:42", HeartbeatSongIdentity.identityKey(fromStats))
        assertEquals(HeartbeatSongIdentity.identityKey(song(42, "x")), HeartbeatSongIdentity.identityKey(fromStats))
    }

    @Test
    fun `identity key falls back to local for channels the player does not recognise`() {
        // 服务器歌曲的来源段是 subsonic，而 playbackSourceKey 只认四个渠道值、
        // 其余一律算本地歌：两侧必须同样兜底，否则这类歌的标记仍会失效
        val server = song(7, "x").copy(
            channelId = "subsonic",
            sourceStableKey = "7|subsonic|",
        )

        assertEquals("local:7", HeartbeatSongIdentity.identityKey(server))
        assertEquals(HeartbeatSongIdentity.identityKey(song(7, "x")), HeartbeatSongIdentity.identityKey(server))
    }

    @Test
    fun `identity key maps youtube music channels to the youtube prefix`() {
        val fromStats = song(9, "x").copy(sourceStableKey = "9|youtube_music|")

        assertEquals("youtube:9", HeartbeatSongIdentity.identityKey(fromStats))
    }

    @Test
    fun `identity key aliases keep the pre-normalisation stats key readable`() {
        // 老版本把统计库的键原样存进「不喜欢」名单；规范化上线后
        // 读取时两种键都要能命中，否则用户已有的标记会全部失效
        val fromStats = song(39175763, "x").copy(sourceStableKey = "39175763|netease|")

        assertEquals(
            setOf("netease:39175763", "39175763|netease|"),
            HeartbeatSongIdentity.identityKeyAliases(fromStats)
        )
        assertEquals(setOf("local:42"), HeartbeatSongIdentity.identityKeyAliases(song(42, "x")))
    }

    private fun song(
        id: Long,
        name: String,
        artist: String = "歌手$id",
        album: String = "专辑$id",
        albumId: Long = id,
        durationMs: Long = 180_000L,
    ) = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = null,
    )
}
