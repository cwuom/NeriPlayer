package moe.ouom.neriplayer.core.player.service.presentation

import android.media.AudioDeviceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import moe.ouom.neriplayer.core.player.metadata.EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers.RETURNS_SELF
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.withSettings

class CarMediaMetadataTest {
    @Test
    fun `default bluetooth mode combines lyrics while preserving artist and non bluetooth behavior`() {
        val payload = ExternalBluetoothLyricPayload("line", "译文")
        val bluetooth = serviceMetadataText(song(), payload, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false)
        assertEquals("Song | line | 译文", bluetooth.title)
        assertEquals("Artist", bluetooth.artist)
        assertEquals("Album", bluetooth.album)

        val speaker = serviceMetadataText(song(), payload, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true)
        assertEquals("line", speaker.title)
        assertEquals("译文", speaker.artist)
    }

    @Test
    fun `car actions support browsed items voice preparation and queue selection`() {
        val actions = mediaSessionPlaybackActions()
        for (action in listOf(
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID,
            PlaybackState.ACTION_PLAY_FROM_SEARCH,
            PlaybackState.ACTION_PREPARE,
            PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID,
            PlaybackState.ACTION_PREPARE_FROM_SEARCH,
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM,
        )) assertTrue(actions and action != 0L)
    }

    @Test
    fun `song information mode overrides forced lyric output on bluetooth only`() {
        val payload = ExternalBluetoothLyricPayload("lyric", "translation")
        val bluetooth = serviceMetadataText(song(), payload, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, true, BluetoothMetadataMode.SongInfo)
        assertEquals("Song", bluetooth.title)
        assertEquals("Artist", bluetooth.artist)
        assertEquals("Album", bluetooth.album)
        val speaker = serviceMetadataText(song(), payload, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true, BluetoothMetadataMode.SongInfo)
        assertEquals("lyric", speaker.title)
        assertEquals("translation", speaker.artist)
    }

    @Test
    fun `empty albums do not publish whitespace or stale song data`() {
        for (album in listOf("", "  ", "\n")) {
            val metadata = serviceMetadataText(song().copy(album = album), ExternalBluetoothLyricPayload(), null, false)
            assertNull(metadata.album)
        }
        assertNull(serviceMetadataText(null, ExternalBluetoothLyricPayload(), null, false).album)
    }

    @Test
    fun `framework metadata excludes stale artwork and adds album and local artwork only when ready`() {
        val bitmap = mock(Bitmap::class.java)
        val stale = PlaybackArtworkSnapshot("content://stale", bitmap, bitmap, false, false, false)
        val ready = PlaybackArtworkSnapshot("content://current", bitmap, bitmap, true, true, false)
        val info = serviceMetadataText(song(), ExternalBluetoothLyricPayload("line"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, BluetoothMetadataMode.SongAndLyrics)
        val current = serviceMetadataSnapshot(song(), info, ready, 4, 1, "content://local/art", "song-id")
        mockConstruction(MediaMetadata.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenReturn(mock(MediaMetadata::class.java))
        }.use { builders ->
            serviceMediaMetadata(current.copy(album = null, displayDescription = null, artworkUri = null), stale)
            serviceMediaMetadata(current, ready)
            val staleBuilder = builders.constructed()[0]
            verify(staleBuilder).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, null)
            verify(staleBuilder).putBitmap(MediaMetadata.METADATA_KEY_ART, null)
            verify(staleBuilder).putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, null)
            verify(staleBuilder, never()).putString(eq(MediaMetadata.METADATA_KEY_ALBUM), nullable(String::class.java))
            verify(staleBuilder, never()).putString(eq(MediaMetadata.METADATA_KEY_ALBUM_ART_URI), nullable(String::class.java))
            val currentBuilder = builders.constructed()[1]
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_TITLE, "Song | line")
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "song-id")
            verify(currentBuilder).putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 2L)
            verify(currentBuilder).putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            verify(currentBuilder).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            verify(currentBuilder).putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://local/art")
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_ART_URI, "content://local/art")
            verify(currentBuilder).putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, "content://local/art")
        }
    }

    @Test
    fun `android display album takes precedence over internal local album identity`() {
        val metadata = serviceMetadataText(
            song().copy(album = "__local_files__"),
            ExternalBluetoothLyricPayload(),
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            false,
            BluetoothMetadataMode.SongInfo,
            normalAlbum = "本地文件",
        )
        assertEquals("本地文件", metadata.album)
    }

    @Test
    fun `combined lyrics preserve artist album and normal display metadata`() {
        val metadata = serviceMetadataText(song(), ExternalBluetoothLyricPayload("line", "译文"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, BluetoothMetadataMode.SongAndLyrics)
        assertEquals("Song | line | 译文", metadata.title)
        assertEquals("Artist", metadata.artist)
        assertEquals("Album", metadata.album)
        assertEquals("Song", metadata.displayTitle)
        assertEquals("Artist", metadata.displaySubtitle)
        assertEquals("line | 译文", metadata.displayDescription)
    }

    @Test
    fun `combined metadata bounds utf8 and returns normal song between lyric lines`() {
        val metadata = serviceMetadataText(song(), ExternalBluetoothLyricPayload("歌词🎵".repeat(200)), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, BluetoothMetadataMode.SongAndLyrics)
        assertTrue(metadata.title.startsWith("Song | "))
        assertTrue(metadata.title.toByteArray(Charsets.UTF_8).size <= EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES)
        assertTrue(!metadata.title.contains('\uFFFD'))
        val noLine = serviceMetadataText(song(), ExternalBluetoothLyricPayload(), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, BluetoothMetadataMode.SongAndLyrics)
        assertEquals("Song", noLine.title)
        assertEquals("Album", noLine.album)
    }

    @Test
    fun `metadata identity stays constant across lyric updates and track counts are one based`() {
        val song = song()
        val text = serviceMetadataText(song, ExternalBluetoothLyricPayload("line"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false)
        val queue = List(10) { song.copy(id = it.toLong()) }
        val current = queue[3]
        val mediaId = serviceMetadataMediaId(current, queue, 3)
        val snapshot = serviceMetadataSnapshot(current, text, emptyArtwork(), 10, 3, "content://test/art", mediaId)
        assertEquals(current, CarMediaLibrary(CarLibrarySnapshot(queue = queue)).getItem(checkNotNull(snapshot.mediaId))?.song)
        assertEquals(4L, snapshot.trackNumber)
        assertEquals(10L, snapshot.numTracks)
        assertEquals("content://test/art", snapshot.artworkUri)
        assertEquals(0L, serviceMetadataSnapshot(song, text, emptyArtwork(), 10, -1).trackNumber)
        assertEquals(0L, serviceMetadataSnapshot(song.copy(durationMs = -10L), text, emptyArtwork()).durationMs)
        assertNull(serviceMetadataSnapshot(null, text, emptyArtwork()).mediaId)
        assertNull(serviceMetadataMediaId(song, queue, -1))
        assertNull(serviceMetadataMediaId(song.copy(id = 30L), queue, 3))
        assertNull(serviceMetadataMediaId(null, queue, 3))
    }

    private fun song() = SongItem(1L, "Song", "Artist", "Album", 1L, 60_000L, null)
    private fun emptyArtwork() = PlaybackArtworkSnapshot(null, null, null, false, false, false)
}
