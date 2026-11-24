package moe.ouom.neriplayer.platform.youtube.api.parser

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeContentFilterTest {
    private fun card(id: String, type: String, explicit: Boolean): JSONObject {
        val renderer = JSONObject().put("id", id)
        renderer.put("navigationEndpoint", JSONObject("""{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{
            "watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}"""))
        if (explicit) renderer.put("subtitleBadges", JSONArray("""[{"musicInlineBadgeRenderer":{
            "icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}]"""))
        return JSONObject().put("musicTwoRowItemRenderer", renderer)
    }

    private fun visible(prefs: YouTubeContentPreferences): List<String> {
        val cards = JSONArray()
            .put(card("track", "MUSIC_VIDEO_TYPE_ATV", false))
            .put(card("explicit", "MUSIC_VIDEO_TYPE_ATV", true))
            .put(card("video", "MUSIC_VIDEO_TYPE_OMV", false))
            .put(card("both", "MUSIC_VIDEO_TYPE_UGC", true))
            .put(JSONObject().put("continuationItemRenderer", JSONObject()))
        JSONObject().put("continuationContents", JSONObject().put("contents", cards)).filterCatalogItems(prefs)
        return (0 until cards.length()).map {
            cards.getJSONObject(it).optJSONObject("musicTwoRowItemRenderer")?.getString("id") ?: "continuation"
        }
    }

    @Test
    fun savedContentLabelsDistinguishTracksVideosAndExplicitBadges() {
        fun label(type: String, explicit: Boolean) =
            card("id", type, explicit).getJSONObject("musicTwoRowItemRenderer").contentLabel()
        assertEquals(null, label("MUSIC_VIDEO_TYPE_ATV", false))
        assertEquals(YouTubeContentLabel.EXPLICIT, label("MUSIC_VIDEO_TYPE_ATV", true))
        assertEquals(YouTubeContentLabel.MUSIC_VIDEO, label("MUSIC_VIDEO_TYPE_OMV", false))
        assertEquals(YouTubeContentLabel.EXPLICIT_MUSIC_VIDEO, label("MUSIC_VIDEO_TYPE_UGC", true))
    }

    @Test
    fun filteredHomeGridKeepsItsVisibleTracksAndPageCursor() {
        fun track(id: String, explicit: Boolean): JSONObject = card(id, "MUSIC_VIDEO_TYPE_ATV", explicit).also {
            val row = it.getJSONObject("musicTwoRowItemRenderer")
            row.put("title", JSONObject().put("simpleText", id))
            row.getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint").put("videoId", id)
        }
        val grid = JSONObject("""{"header":{"gridHeaderRenderer":{"title":{"simpleText":"Discover"}}}}""")
            .put("items", JSONArray().put(track("hidden", true)).put(track("visible", false)))
        val sections = JSONArray().put(JSONObject().put("gridRenderer", grid)).put(JSONObject(
            """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}"""
        ))
        val root = JSONObject().put("contents", JSONObject().put("sectionListRenderer", JSONObject().put("contents", sections)))
        assertEquals(1, root.filterCatalogItems(YouTubeContentPreferences(hideExplicit = true)))
        val shelf = YouTubeMusicParser.parseHomeShelfPages(root).single()
        assertEquals("Discover", shelf.title)
        assertEquals("visible", shelf.items.single().videoId)
        assertEquals("next", YouTubeMusicParser.extractHomeContinuation(root))
    }

    @Test
    fun twoRowPlaybackCardsUseTheSameTypeForCreatorHomeAndFiltering() {
        fun row(title: String, subtitle: String, type: String = ""): JSONObject =
            card(title, type, false).also {
                val renderer = it.getJSONObject("musicTwoRowItemRenderer")
                renderer.put("title", JSONObject().put("simpleText", title))
                renderer.put("subtitle", JSONObject().put("simpleText", subtitle))
                renderer.getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint").put("videoId", title)
            }
        val cards = JSONArray().put(row("video", "Video · Artist")).put(row("fallback", "Artist"))
            .put(row("song", "Song · Artist")).put(row("audio", "Video", "MUSIC_VIDEO_TYPE_ATV"))
        val grid = JSONObject("""{"header":{"gridHeaderRenderer":{"title":{"simpleText":"Featured"}}}}""").put("items", cards)
        val sections = JSONArray().put(JSONObject().put("gridRenderer", grid))
        val root = JSONObject().put("contents", JSONObject().put("sectionListRenderer", JSONObject().put("contents", sections)))
        val creatorItems = YouTubeMusicParser.parseCreatorItemsPage(root, "Featured").items
        assertEquals(
            listOf(YouTubeMusicCreatorItemType.Video, YouTubeMusicCreatorItemType.Video,
                YouTubeMusicCreatorItemType.Song, YouTubeMusicCreatorItemType.Song),
            creatorItems.map { it.type }
        )
        val labels = listOf(YouTubeContentLabel.MUSIC_VIDEO, YouTubeContentLabel.MUSIC_VIDEO, null, null)
        assertEquals(labels, creatorItems.map { it.contentLabel })
        assertEquals(labels, YouTubeMusicParser.parseHomeShelfPages(root).single().items.map { it.contentLabel })
        assertEquals(2, root.filterCatalogItems(YouTubeContentPreferences(hideMusicVideos = true)))
        assertEquals(listOf("song", "audio"), YouTubeMusicParser.parseCreatorItemsPage(root, "Featured").items.map { it.title })
    }

    @Test
    fun badgesAndMusicVideoTypesAreIndependentAndNeverRemovePagination() {
        val prefs = YouTubeContentPreferences()
        assertEquals(listOf("track", "explicit", "video", "both", "continuation"), visible(prefs))
        assertEquals(listOf("track", "video", "continuation"), visible(prefs.copy(hideExplicit = true)))
        assertEquals(listOf("track", "explicit", "continuation"), visible(prefs.copy(hideMusicVideos = true)))
        assertEquals(listOf("track", "continuation"), visible(prefs.copy(hideExplicit = true, hideMusicVideos = true)))
    }
}
