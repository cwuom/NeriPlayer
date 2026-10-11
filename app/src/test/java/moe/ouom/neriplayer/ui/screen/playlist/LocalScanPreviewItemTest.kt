package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.util.format.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocalScanPreviewItemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `preview item prefers the local file path and joins non blank subtitle parts`() {
        val song = song(
            name = "Song Title",
            artist = "Artist",
            album = "Album",
            durationMs = 61_000L
        ).copy(
            localFilePath = "/music/Artist - Song.mp3",
            mediaUri = "content://media/external/audio/media/1"
        )

        val item = song.toLocalScanPreviewItem(context)

        assertEquals("Artist - Song.mp3", item.fileName)
        assertEquals("/music/Artist - Song.mp3", item.filePath)
        assertEquals("Song Title", item.title)
        assertEquals("Artist · Album · Artist - Song.mp3 · ${formatDuration(61_000L)}", item.subtitle)
        assertEquals("local-scan:content://media/external/audio/media/1", item.rowKey)
        assertEquals(song.stableKey(), item.stableKey)
        assertEquals(
            listOf("Artist - Song.mp3", "/music/Artist - Song.mp3", "Song Title", "Artist", "Album").joinToString("\n"),
            item.searchText
        )
        assertTrue(item.hasMetadata)
    }

    @Test
    fun `preview item falls back to media paths file names and the display name`() {
        val absoluteMedia = song(name = "Track", artist = " ", album = "").copy(
            mediaUri = "/storage/emulated/0/Music/track.flac",
            localFileName = "track.flac"
        ).toLocalScanPreviewItem(context)
        assertEquals("/storage/emulated/0/Music/track.flac", absoluteMedia.filePath)
        assertEquals("track.flac", absoluteMedia.fileName)
        assertEquals("track.flac", absoluteMedia.subtitle)

        val providerMedia = song(name = "Provider", artist = "", album = "").copy(
            mediaUri = "content://provider/audio/2",
            localFilePath = " ",
            localFileName = " "
        ).toLocalScanPreviewItem(context)
        assertEquals("content://provider/audio/2", providerMedia.filePath)
        assertEquals("Provider", providerMedia.fileName)
        assertEquals("", providerMedia.subtitle)

        val bare = song(name = "Bare", artist = "", album = "").toLocalScanPreviewItem(context)
        assertEquals("", bare.filePath)
        assertEquals("Bare", bare.fileName)
    }

    @Test
    fun `preview metadata ignores file name titles unknown artists and local album placeholders`() {
        val unknownArtist = context.getString(CoreCommonR.string.music_unknown_artist)
        val placeholder = song(name = "track", artist = unknownArtist, album = LocalSongSupport.LOCAL_ALBUM_IDENTITY)

        assertFalse(placeholder.hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertFalse(placeholder.copy(name = " ").hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertFalse(placeholder.copy(album = " ").hasMeaningfulPreviewMetadata(context, "TRACK.mp3"))
        assertTrue(placeholder.copy(name = "Track Title").hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertTrue(placeholder.hasMeaningfulPreviewMetadata(context, ".mp3"))
        assertTrue(placeholder.copy(artist = "Real Artist").hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertTrue(placeholder.copy(album = "Real Album").hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertTrue(placeholder.copy(coverUrl = "file:///cover.jpg").hasMeaningfulPreviewMetadata(context, "track.mp3"))
        assertTrue(
            placeholder.copy(coverUrl = " ", originalCoverUrl = "https://example.com/cover.jpg")
                .hasMeaningfulPreviewMetadata(context, "track.mp3")
        )
        assertTrue(placeholder.toLocalScanPreviewItem(context, metadataPending = true).hasMetadata)
        assertFalse(placeholder.copy(localFileName = "track.mp3").toLocalScanPreviewItem(context).hasMetadata)
    }

    @Test
    fun `row keys prefer media uri then local path then file name then stable key`() {
        val base = song(name = "Key", artist = "", album = "")

        assertEquals("local-scan:content://a", base.copy(mediaUri = "content://a", localFilePath = "/b").scanPreviewRowKey())
        assertEquals("local-scan:/b", base.copy(mediaUri = " ", localFilePath = "/b", localFileName = "c").scanPreviewRowKey())
        assertEquals("local-scan:c", base.copy(localFilePath = "", localFileName = "c").scanPreviewRowKey())
        assertEquals("local-scan:${base.stableKey()}", base.scanPreviewRowKey())
    }

    @Test
    fun `scan preview filters drop items without metadata existing or duplicate keys then rank by query`() {
        val items = listOf(
            previewItem("a", "Alpha Song", hasMetadata = true),
            previewItem("b", "Beta Song", hasMetadata = false),
            previewItem("c", "Gamma Song", hasMetadata = true),
            previewItem("d", "Delta Track", hasMetadata = true)
        )
        fun filter(
            query: String = "",
            metadataOnly: Boolean = false,
            hideExisting: Boolean = false,
            hideDuplicates: Boolean = false
        ) = filterLocalScanPreviewItems(
            items = items,
            query = query,
            metadataOnly = metadataOnly,
            hideExistingLocalPlaylistSongs = hideExisting,
            existingLocalPlaylistKeys = setOf("c"),
            hideDuplicateMetadataSongs = hideDuplicates,
            duplicateMetadataKeys = setOf("d")
        ).map(LocalScanPreviewItem::stableKey)

        assertEquals(listOf("a", "b", "c", "d"), filter())
        assertEquals(listOf("a", "c", "d"), filter(metadataOnly = true))
        assertEquals(listOf("a", "b", "d"), filter(hideExisting = true))
        assertEquals(listOf("a", "b", "c"), filter(hideDuplicates = true))
        assertEquals(listOf("a"), filter(metadataOnly = true, hideExisting = true, hideDuplicates = true))
        assertEquals(listOf("a", "b", "c"), filter(query = "song"))
    }

    @Test
    fun `metadata processing card reports progress or an unknown total`() {
        var state by mutableStateOf(LocalMetadataProcessingState(isProcessing = true, processedCount = 7, totalCount = 3))
        composeRule.setContent { LocalMetadataProcessingCard(state) }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.local_playlist_metadata_processing_title)).assertExists()
        composeRule.onNodeWithText(
            context.getString(CoreCommonR.string.local_playlist_metadata_processing_message, 3, 3)
        ).assertExists()

        state = state.copy(processedCount = 2, totalCount = 0)
        composeRule.onNodeWithText(
            context.getString(CoreCommonR.string.local_playlist_metadata_processing_message_unknown)
        ).assertExists()
    }

    private fun previewItem(key: String, title: String, hasMetadata: Boolean) = LocalScanPreviewItem(
        song = song(name = title, artist = "", album = ""),
        stableKey = key,
        rowKey = "local-scan:$key",
        title = title,
        fileName = "",
        filePath = "",
        subtitle = "",
        hasMetadata = hasMetadata,
        searchText = title
    )

    private fun song(name: String, artist: String, album: String, durationMs: Long = 0L) = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null
    )
}
