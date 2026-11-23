package moe.ouom.neriplayer.data.model.settings.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSongSectionDisplayTest {

    @Test
    fun parseReadsIdModePairs() {
        val modes = parseHomeSongSectionModes("top_hot=CARD,daily_recommend=card")
        assertEquals(HomeSongSectionDisplayMode.CARD, modes["top_hot"])
        assertEquals(HomeSongSectionDisplayMode.CARD, modes["daily_recommend"])
    }

    @Test
    fun parseDropsUnknownIdsAndMalformedEntries() {
        val modes = parseHomeSongSectionModes(
            "unknown_module=CARD,broken_entry,=CARD,top_hot=CARD"
        )
        assertEquals(setOf("top_hot"), modes.keys)
    }

    @Test
    fun parseFallsBackToEmptyOnBlank() {
        assertTrue(parseHomeSongSectionModes("").isEmpty())
        assertTrue(parseHomeSongSectionModes(null).isEmpty())
    }

    @Test
    fun parseIgnoresUnknownModeNames() {
        val modes = parseHomeSongSectionModes("top_hot=SOMETHING_ELSE")
        assertEquals(HomeSongSectionDisplayMode.DEFAULT, modes["top_hot"])
    }

    @Test
    fun encodeOnlyKeepsNonDefaultEntries() {
        val encoded = encodeHomeSongSectionModes(
            mapOf(
                "top_hot" to HomeSongSectionDisplayMode.CARD,
                "top_new" to HomeSongSectionDisplayMode.DEFAULT,
                "unknown_module" to HomeSongSectionDisplayMode.CARD
            )
        )
        assertEquals("top_hot=CARD", encoded)
    }

    @Test
    fun encodeRoundTripsThroughTheParser() {
        val original = mapOf(
            "top_hot" to HomeSongSectionDisplayMode.CARD,
            "private_fm" to HomeSongSectionDisplayMode.CARD
        )
        assertEquals(original, parseHomeSongSectionModes(encodeHomeSongSectionModes(original)))
    }

    @Test
    fun displayModeOfFallsBackToDefault() {
        assertEquals(
            HomeSongSectionDisplayMode.DEFAULT,
            homeSongSectionDisplayModeOf(emptyMap(), "top_hot")
        )
        assertEquals(
            HomeSongSectionDisplayMode.CARD,
            homeSongSectionDisplayModeOf(
                mapOf("top_hot" to HomeSongSectionDisplayMode.CARD),
                "top_hot"
            )
        )
    }

    @Test
    fun songSectionIdsCoverEverySongBackedSection() {
        assertEquals(
            listOf(
                HomeTrendingSubSectionIds.TOP_SOARING,
                HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS,
                HomeTrendingSubSectionIds.TOP_HOT,
                HomeTrendingSubSectionIds.TOP_NEW,
                HomeRadarSubSectionIds.PERSONAL_RADAR,
                HomeRadarSubSectionIds.DAILY_RECOMMEND,
                HomeRadarSubSectionIds.PRIVATE_FM
            ),
            HomeSongSectionIds.ALL
        )
        // 雷达歌单是歌单集合，不是歌曲来源，不应出现在歌曲模块里
        assertTrue(!HomeSongSectionIds.ALL.contains(HomeRadarSubSectionIds.RADAR_PLAYLISTS))
    }

    @Test
    fun cardColumnsAreClampedToTheSupportedRange() {
        assertEquals(HomeSongCardColumns.MIN, HomeSongCardColumns.normalize(0))
        assertEquals(HomeSongCardColumns.MAX, HomeSongCardColumns.normalize(9))
        assertEquals(2, HomeSongCardColumns.normalize(2))
    }
}
