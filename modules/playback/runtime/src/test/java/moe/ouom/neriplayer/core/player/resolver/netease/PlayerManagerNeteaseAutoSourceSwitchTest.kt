package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerNeteaseAutoSourceSwitchTest {

    @Test fun foregroundStartsWithTheFirstPlayableMatchAndItsCdns() = runBlocking {
        val probed = mutableListOf<String>()
        val result = selectNeteaseBiliSources(
            listOf("song"), deferAlternates = true, rankedCandidates = { ranked("missing", "first", "slow") }
        ) { item ->
            probed += item.bvid
            if (item.bvid == "missing") null else matched(item.bvid)
        }
        assertEquals(listOf("missing", "first"), probed)
        assertEquals(listOf("first", "first-cdn"), result?.playbackCandidates()?.map { it.url })
        assertEquals(listOf("first", "first"), result?.playbackCandidates()?.map { it.deferredNeteaseAutoBiliCandidateId })
    }

    @Test fun exhaustedPrimaryCdnsResolveOnlyTwoOtherDistinctMatchesInOrder() = runBlocking {
        val probed = mutableListOf<String>()
        val result = selectNeteaseBiliSources(
            listOf("one", "two"), failedCandidate = "first",
            rankedCandidates = { if (it == "one") ranked("first", "second") else ranked("second", "third", "fourth") }
        ) { item ->
            probed += item.bvid
            matched(item.bvid)
        }
        assertEquals(listOf("second", "third"), probed)
        assertEquals(listOf("second", "second-cdn", "third", "third-cdn"), result?.playbackCandidates()?.map { it.url })
        assertTrue(result?.playbackCandidates()?.all { it.deferredNeteaseAutoBiliCandidateId == null } == true)
    }

    @Test fun recoveryTerminatesWhenSearchOnlyReturnsTheFailedMatch() = runBlocking {
        val result = selectNeteaseBiliSources(
            listOf("one", "two"), failedCandidate = "first", rankedCandidates = { ranked("first") }
        ) { item -> error("attempted failed candidate ${item.bvid}") }
        assertEquals(null, result)
    }

    @Test fun prefetchAndRoomResolutionStillCollectTwoBackups() = runBlocking {
        val result = selectNeteaseBiliSources(listOf("song"), rankedCandidates = { ranked("a", "b", "c", "d") }) {
            matched(it.bvid)
        }
        assertEquals(listOf("b", "c"), result?.fallbackCandidates?.map { it.url })
    }

    private fun ranked(vararg ids: String) = ids.map {
        SearchVideoItem(1, it, it, it, "", 1, "", 180, null, null)
    }

    private fun matched(id: String) = SongUrlResult.Success(url = id, candidateUrls = listOf("$id-cdn"))

    @Test
    fun buildNeteaseAutoBiliCacheKey_usesBiliAutoNamespace() {
        val key = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30280)
        )

        assertTrue(key.startsWith("bili-auto-"))
        assertFalse(key.startsWith("netease-"))
    }

    @Test
    fun buildNeteaseAutoBiliCacheKey_isStableForSameStream() {
        val stream = biliStream(id = 30280)
        val first = buildNeteaseAutoBiliCacheKey("BV1UyuRz5ERv", 31144021363L, stream)
        val second = buildNeteaseAutoBiliCacheKey("BV1UyuRz5ERv", 31144021363L, stream)

        assertEquals(first, second)
    }

    @Test
    fun buildNeteaseAutoBiliCacheKey_separatesDifferentStreams() {
        val highKey = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30280)
        )
        val mediumKey = buildNeteaseAutoBiliCacheKey(
            bvid = "BV1UyuRz5ERv",
            cid = 31144021363L,
            selectedStream = biliStream(id = 30232, bitrateKbps = 92)
        )

        assertNotEquals(highKey, mediumKey)
    }

    private fun biliStream(
        id: Int?,
        bitrateKbps: Int = 200,
        qualityTag: String? = null
    ): BiliAudioStreamInfo {
        val suffix = id?.toString() ?: qualityTag ?: bitrateKbps.toString()
        return BiliAudioStreamInfo(
            id = id,
            mimeType = "audio/mp4",
            bitrateKbps = bitrateKbps,
            qualityTag = qualityTag,
            url = "https://upos.example.bilivideo.com/$suffix.m4s"
        )
    }
}
