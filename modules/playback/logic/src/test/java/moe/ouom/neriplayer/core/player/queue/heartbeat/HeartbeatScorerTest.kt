package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地降级评分器测试（任务书 §5.1）。
 *
 * 覆盖：评分公式各维度、同歌手/同专辑加分、短音频降权、
 * 已在队列中的歌曲排除、当前歌曲自身排除、无曲风数据时正常降级。
 */
class HeartbeatScorerTest {

    private val current = song(id = 1, name = "当前", artist = "陈洁仪", album = "重译", albumId = 10)

    // ---- 基础评分 ----

    @Test
    fun `higher tier always outranks lower tier`() {
        val favorite = candidate(song(2, "收藏"), tier = HeartbeatCandidateTier.FAVORITE)
        val history = candidate(song(3, "历史"), tier = HeartbeatCandidateTier.HISTORY)

        assertTrue(
            "收藏层级分数必须高于历史层级",
            HeartbeatScorer.score(favorite, current) > HeartbeatScorer.score(history, current),
        )
    }

    @Test
    fun `tier base score decays monotonically`() {
        val scores = (HeartbeatCandidateTier.FAVORITE..HeartbeatCandidateTier.RANDOM).map { tier ->
            HeartbeatScorer.score(candidate(song(9, "x"), tier = tier), null)
        }
        assertEquals("四个层级分数应严格递减", scores.sortedDescending(), scores)
        assertEquals(scores.distinct().size, scores.size)
    }

    @Test
    fun `same artist candidate receives bonus`() {
        val sameArtist = candidate(song(2, "同歌手", artist = "陈洁仪"), tier = HeartbeatCandidateTier.FAVORITE)
        val otherArtist = candidate(song(3, "别歌手", artist = "别人"), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(
            HeartbeatScorer.score(sameArtist, current) > HeartbeatScorer.score(otherArtist, current),
        )
    }

    @Test
    fun `same album candidate receives bonus`() {
        val sameAlbum = candidate(song(2, "同专辑", artist = "别人", album = "重译", albumId = 10), tier = HeartbeatCandidateTier.FAVORITE)
        val otherAlbum = candidate(song(3, "别专辑", artist = "别人", album = "其它", albumId = 11), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(
            HeartbeatScorer.score(sameAlbum, current) > HeartbeatScorer.score(otherAlbum, current),
        )
    }

    @Test
    fun `album bonus applies when albumId matches even if album name differs`() {
        val byId = candidate(song(2, "同专辑", artist = "别人", album = "译名不同", albumId = 10), tier = HeartbeatCandidateTier.FAVORITE)
        val noMatch = candidate(song(3, "别专辑", artist = "别人", album = "其它", albumId = 11), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(HeartbeatScorer.score(byId, current) > HeartbeatScorer.score(noMatch, current))
    }

    @Test
    fun `recently played candidate is penalized`() {
        val fresh = candidate(song(2, "新鲜"), tier = HeartbeatCandidateTier.FAVORITE, recentlyPlayed = false)
        val played = candidate(song(3, "刚播过"), tier = HeartbeatCandidateTier.FAVORITE, recentlyPlayed = true)

        assertTrue(HeartbeatScorer.score(fresh, current) > HeartbeatScorer.score(played, current))
    }

    @Test
    fun `very short tracks are penalized`() {
        val normal = candidate(song(2, "正常", durationMs = 200_000L), tier = HeartbeatCandidateTier.FAVORITE)
        val short = candidate(song(3, "短音频", durationMs = 15_000L), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(HeartbeatScorer.score(normal, current) > HeartbeatScorer.score(short, current))
    }

    @Test
    fun `zero weight candidate scores zero`() {
        val zero = candidate(song(2, "零权重"), tier = HeartbeatCandidateTier.FAVORITE, weight = 0.0)
        assertEquals(0.0, HeartbeatScorer.score(zero, current), 0.0001)
    }

    // ---- 重复惩罚（任务书 §3.4）----

    @Test
    fun `repeat penalty is neutral for never played candidates`() {
        assertEquals(1.0, HeartbeatScorer.repeatPenalty(0), 0.0001)
        assertEquals(1.0, HeartbeatScorer.repeatPenalty(-5), 0.0001)
    }

    @Test
    fun `repeat penalty decays with play count`() {
        val once = HeartbeatScorer.repeatPenalty(1)
        val thrice = HeartbeatScorer.repeatPenalty(3)

        assertTrue("播得越多分数越低", once > thrice)
        assertEquals(HeartbeatScorer.REPEAT_PENALTY_STEP, once, 0.0001)
    }

    @Test
    fun `repeat penalty never drops below the floor`() {
        assertEquals(
            HeartbeatScorer.REPEAT_PENALTY_FLOOR,
            HeartbeatScorer.repeatPenalty(10_000),
            0.0001,
        )
    }

    @Test
    fun `recentPlayCount lowers the score`() {
        val fresh = candidate(song(2, "没播过"), tier = HeartbeatCandidateTier.FAVORITE)
        val played = candidate(song(3, "播过很多次"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(recentPlayCount = 5)

        assertTrue(HeartbeatScorer.score(fresh, current) > HeartbeatScorer.score(played, current))
    }

    @Test
    fun `effective repeat count takes the stronger of boolean and counter`() {
        val boolOnly = HeartbeatCandidate(song(2, "x"), HeartbeatCandidateTier.FAVORITE, recentlyPlayed = true)
        val counterOnly = HeartbeatCandidate(song(3, "y"), HeartbeatCandidateTier.FAVORITE, recentPlayCount = 3)

        assertEquals(1, boolOnly.effectiveRepeatCount)
        assertEquals(3, counterOnly.effectiveRepeatCount)
    }

    @Test
    fun `tier priority survives moderate repeat penalty`() {
        // 层级基础分差距（3000）远大于中等重复次数造成的衰减，层级语义不得被破坏。
        // 注意：重复到下限（0.25）时收藏会掉到与随机同分——这是刻意的设计，
        // 「播爆了的老歌」本来就该和新歌机会均等，所以这里只断言中等次数。
        val favoritePlayedThrice = candidate(song(2, "收藏播过三次"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(recentPlayCount = 3)
        val randomFresh = candidate(song(3, "随机但新鲜"), tier = HeartbeatCandidateTier.RANDOM)

        assertTrue(
            "收藏层级在中等重复次数下必须压过随机层级",
            HeartbeatScorer.score(favoritePlayedThrice, current) >
                HeartbeatScorer.score(randomFresh, current),
        )
    }

    @Test
    fun `repeat penalty floor equalizes heavily played favorites with fresh randoms`() {
        // 明确固化「播爆了的老歌 == 随机新歌」这一语义，避免以后误以为是 bug
        val favoritePlayedALot = candidate(song(2, "收藏但播爆了"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(recentPlayCount = 20)
        val randomFresh = candidate(song(3, "随机但新鲜"), tier = HeartbeatCandidateTier.RANDOM)

        assertEquals(
            HeartbeatScorer.score(randomFresh, current),
            HeartbeatScorer.score(favoritePlayedALot, current),
            0.0001,
        )
    }

    // ---- 跳过次数降权（任务书 §七 第三阶段）----

    @Test
    fun `skip count lowers the score`() {
        val never = candidate(song(2, "没跳过"), tier = HeartbeatCandidateTier.FAVORITE)
        val skipped = candidate(song(3, "被跳过几次"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(skipCount = 3)

        assertTrue(
            "跳过次数越多分数越低",
            HeartbeatScorer.score(never, current) > HeartbeatScorer.score(skipped, current),
        )
    }

    @Test
    fun `skip count joins the repeat penalty curve`() {
        // 跳过与「播过一遍」共用同一条衰减曲线，不额外加权：
        // 跳 2 次的效果应与播过 2 次完全相同
        val skippedTwice = candidate(song(2, "跳过两次"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(skipCount = 2)
        val playedTwice = candidate(song(3, "播过两次"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(recentPlayCount = 2)

        assertEquals(
            HeartbeatScorer.score(playedTwice, current),
            HeartbeatScorer.score(skippedTwice, current),
            0.0001,
        )
    }

    @Test
    fun `penalty repeat count adds skips on top of play count`() {
        val candidate = HeartbeatCandidate(
            song = song(2, "播过两次又跳过三次"),
            tier = HeartbeatCandidateTier.FAVORITE,
            recentPlayCount = 2,
            skipCount = 3,
        )

        assertEquals(5, candidate.penaltyRepeatCount)
    }

    @Test
    fun `skipped candidate still selectable when nothing else exists`() {
        // 跳过不是硬排除：整池都被跳过时仍要能选出一首，否则心动模式会空转
        val pool = poolOf(
            candidate(song(2, "跳过一次"), tier = HeartbeatCandidateTier.FAVORITE).copy(skipCount = 1),
        )

        assertEquals(2L, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    // ---- 失败曲目排除（任务书 §八）----

    @Test
    fun `pickBest excludes songs marked as failed`() {
        val failed = song(2, "播放失败过")
        val ok = song(3, "正常")
        val pool = poolOf(
            candidate(failed, tier = HeartbeatCandidateTier.FAVORITE),
            candidate(ok, tier = HeartbeatCandidateTier.HISTORY),
        )

        val picked = HeartbeatScorer.pickBest(
            pool = pool,
            currentSong = current,
            excludedIdentities = setOf(HeartbeatScorer.identityKey(failed)),
        )

        assertEquals("失败曲目必须被硬排除，即使层级更高", 3L, picked?.id)
    }

    @Test
    fun `pickBest returns null when every candidate failed`() {
        val only = song(2, "唯一候选")
        val pool = poolOf(candidate(only, tier = HeartbeatCandidateTier.FAVORITE))

        assertNull(
            HeartbeatScorer.pickBest(
                pool = pool,
                currentSong = current,
                excludedIdentities = setOf(HeartbeatScorer.identityKey(only)),
            )
        )
    }

    // ---- 挑选行为 ----

    @Test
    fun `pickBest never returns the current song`() {
        val pool = poolOf(candidate(current, tier = HeartbeatCandidateTier.FAVORITE))
        assertNull(HeartbeatScorer.pickBest(pool, current, emptyList()))
    }

    @Test
    fun `pickBest excludes songs already in the queue`() {
        val queued = song(2, "已在队列")
        val pool = poolOf(
            candidate(queued, tier = HeartbeatCandidateTier.FAVORITE),
            candidate(song(3, "可用"), tier = HeartbeatCandidateTier.HISTORY),
        )

        val picked = HeartbeatScorer.pickBest(pool, current, listOf(queued))
        assertEquals(3L, picked?.id)
    }

    @Test
    fun `pickBest returns null for empty pool`() {
        assertNull(HeartbeatScorer.pickBest(HeartbeatCandidatePool.EMPTY, current, emptyList()))
    }

    @Test
    fun `pickBest prefers higher tier`() {
        val pool = poolOf(
            candidate(song(2, "历史"), tier = HeartbeatCandidateTier.HISTORY),
            candidate(song(3, "收藏"), tier = HeartbeatCandidateTier.FAVORITE),
        )

        assertEquals(3L, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    @Test
    fun `pickBest is stable when scores tie`() {
        val first = song(2, "先")
        val second = song(3, "后")
        val pool = poolOf(
            candidate(first, tier = HeartbeatCandidateTier.FAVORITE),
            candidate(second, tier = HeartbeatCandidateTier.FAVORITE),
        )

        // 同分时取候选池顺序靠前者，保证结果可复现
        assertEquals(first.id, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
        assertEquals(first.id, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    @Test
    fun `pickBest works without metadata such as genre or bpm`() {
        // 任务书要求：缺少曲风/BPM 等元数据时算法正常降级
        val plain = SongItem(
            id = 7L,
            name = "无元数据",
            artist = "",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
        )
        val pool = poolOf(candidate(plain, tier = HeartbeatCandidateTier.HISTORY))

        assertNotNull(HeartbeatScorer.pickBest(pool, current, emptyList()))
    }

    // ---- 不喜欢标记（任务书 §3.4 notLikedWeight）----

    @Test
    fun `not liked penalty overrides even the highest tier`() {
        // 收藏里被标记「不喜欢」的歌必须输给最低层级的未标记歌，
        // 否则标记形同虚设——这是 NOT_LIKED_PENALTY 取 10000 的全部意义
        val dislikedFavorite = candidate(
            song(1, "收藏但不喜欢"),
            tier = HeartbeatCandidateTier.FAVORITE,
        ).copy(notLiked = true)
        val plainRandom = candidate(
            song(2, "随机未标记"),
            tier = HeartbeatCandidateTier.RANDOM,
        )

        assertTrue(
            HeartbeatScorer.score(dislikedFavorite, current) <
                HeartbeatScorer.score(plainRandom, current)
        )
    }

    @Test
    fun `pickBest skips not liked candidate when a clean one exists`() {
        val disliked = candidate(song(1, "不喜欢"), tier = HeartbeatCandidateTier.FAVORITE)
            .copy(notLiked = true)
        val clean = candidate(song(2, "正常"), tier = HeartbeatCandidateTier.RANDOM)
        val pool = poolOf(disliked, clean)

        assertEquals(clean.song.id, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    @Test
    fun `pickBest returns null when every candidate is not liked`() {
        // 整池都被标记时回退原有播放模式，而不是硬推一首用户明确拒绝的歌
        val pool = poolOf(
            candidate(song(1, "不喜欢1")).copy(notLiked = true),
            candidate(song(2, "不喜欢2")).copy(notLiked = true),
        )

        assertNull(HeartbeatScorer.pickBest(pool, current, emptyList()))
    }

    @Test
    fun `not liked candidates stay selectable in principle`() {
        // 不硬排除而是减分：只要池里有未标记的候选，被标记的就不该被选中；
        // 但 score() 本身仍给出有限数值，便于排查与测试
        val disliked = candidate(song(1, "不喜欢")).copy(notLiked = true)
        val score = HeartbeatScorer.score(disliked, current)

        assertTrue(score.isFinite())
        assertTrue(score < 0.0)
    }

    // ---- 身份键 ----

    @Test
    fun `identity key prefers sourceStableKey`() {
        val withKey = song(2, "x").copy(sourceStableKey = "local:/music/a.flac")
        assertEquals("local:/music/a.flac", HeartbeatScorer.identityKey(withKey))
    }

    @Test
    fun `identity key falls back to id then to name and artist`() {
        assertEquals("local:42", HeartbeatScorer.identityKey(song(42, "x")))

        val noId = song(0, "Name").copy(artist = "Artist")
        assertEquals("name|artist", HeartbeatScorer.identityKey(noId))
    }

    @Test
    fun `identity key ignores lyric source so switching providers keeps the key`() {
        val plain = song(42, "x")
        val withNeteaseLyrics = plain.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC)
        val withQqLyrics = plain.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)

        assertEquals(HeartbeatScorer.identityKey(plain), HeartbeatScorer.identityKey(withNeteaseLyrics))
        assertEquals(HeartbeatScorer.identityKey(plain), HeartbeatScorer.identityKey(withQqLyrics))
    }

    @Test
    fun `identity key uses the channel value not its mere presence`() {
        val netease = song(123, "x").copy(channelId = "netease")
        val bili = song(123, "x").copy(channelId = "bilibili")

        assertEquals("netease:123", HeartbeatScorer.identityKey(netease))
        assertEquals("bili:123", HeartbeatScorer.identityKey(bili))
        assertNotEquals(HeartbeatScorer.identityKey(netease), HeartbeatScorer.identityKey(bili))
    }

    // ---- 助手 ----

    private fun candidate(
        song: SongItem,
        tier: Int = HeartbeatCandidateTier.FAVORITE,
        weight: Double = 1.0,
        recentlyPlayed: Boolean = false,
    ) = HeartbeatCandidate(song = song, tier = tier, weight = weight, recentlyPlayed = recentlyPlayed)

    private fun poolOf(vararg candidates: HeartbeatCandidate): HeartbeatCandidatePool =
        HeartbeatCandidatePool(candidates.groupBy { it.tier })

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
