package moe.ouom.neriplayer.platform.youtube.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeContentPlaylistCacheTest {
    @Test
    fun roomCachesKeepTrackLabelsAndAllowOfflineChangesToFilters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YouTubeContentSettings.initialize(context)
        val original = YouTubeContentSettings.preferences.value
        val defaults = YouTubeContentPreferences()
        val hideExplicit = defaults.copy(hideExplicit = true)
        val system = YouTubeContentSettings.systemLocale()
        val tagged = CachedYouTubeMusicPlaylistTrack("id-1", "Title", "Artist", "", 0, "", YouTubeContentLabel.EXPLICIT_MUSIC_VIDEO)
        val unknown = tagged.copy(videoId = "id-2", contentLabel = null)
        val cache = CachedYouTubeMusicPlaylistDetail(
            browseId = "VLfixture", playlistId = "fixture", title = "Title", subtitle = "", coverUrl = "",
            trackCount = 2, firstPageSignature = "source", tracks = listOf(tagged, unknown),
            savedAtMs = 10L, catalogKey = defaults.catalogKey(system)
        )
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val repo = YouTubeMusicPlaylistCacheRepository(context, database)
            YouTubeContentSettings.publish(defaults)
            repo.save(cache)
            assertEquals(cache, repo.read(cache.browseId))
            YouTubeContentSettings.publish(hideExplicit)
            assertEquals(listOf(unknown), repo.read(cache.browseId)?.tracks)
            val empty = cache.copy(
                trackCount = 0, tracks = emptyList(), filteredEmpty = true,
                catalogKey = hideExplicit.catalogKey(system)
            )
            repo.save(empty)
            assertEquals(empty, repo.read(cache.browseId))
            YouTubeContentSettings.publish(defaults)
            assertEquals(cache, repo.read(cache.browseId))
            YouTubeContentSettings.publish(defaults.copy(countryCode = if (defaults.resolveLocale(system).gl == "JP") "US" else "JP"))
            assertNull(repo.read(cache.browseId))
        } finally {
            YouTubeContentSettings.publish(original)
            database.close()
        }
    }
}
