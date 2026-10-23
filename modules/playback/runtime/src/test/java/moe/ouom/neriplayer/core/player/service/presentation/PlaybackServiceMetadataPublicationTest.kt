package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.AudioDeviceInfo
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackServiceMetadataPublicationTest {
    private val song = SongItem(1L, "Song", "Artist", "Album", 1L, 60_000L, null)
    private val mediaBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    private val iconBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)

    @Test
    fun `ColorOS keeps stable platform identity while preserving Bluetooth display lyrics`() {
        for (mode in listOf(BluetoothMetadataMode.Lyrics, BluetoothMetadataMode.SongAndLyrics)) {
            val text = serviceMetadataText(song, ExternalBluetoothLyricPayload("line", "translation"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, mode, colorOsLyricsEnabled = true)
            assertEquals("Song", text.title)
            assertEquals("Artist", text.artist)
            val baseline = serviceMetadataText(song, ExternalBluetoothLyricPayload("line", "translation"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, mode)
            assertEquals(baseline.displayTitle, text.displayTitle)
            assertEquals(baseline.displaySubtitle, text.displaySubtitle)
        }
    }

    @Test
    fun `ColorOS timeline-only changes deduplicate and toggle removal clears both vendor fields`() = runTest {
        val source = FakeSource().apply { colorOsLyricsEnabled = true; colorOsLyricInfo = "first" }
        val port = RecordingPort(false)
        val owner = owner(port, source, artworkFor("content://covers/a.jpg", smallArtwork("content://covers/a.jpg")))
        owner.updateMetadata()
        owner.updateMetadata()
        assertEquals(1, port.attempts)
        assertEquals("first", port.published.last().getString("lyricInfo"))
        assertEquals("1", port.published.last().getString("oplus.media.config"))
        source.colorOsLyricInfo = "translation-arrived"
        owner.updateMetadata()
        assertEquals(2, port.attempts)
        assertEquals("translation-arrived", port.published.last().getString("lyricInfo"))
        assertNotNull(port.published.last().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        source.colorOsLyricsEnabled = false
        owner.updateMetadata()
        assertEquals(3, port.attempts)
        assertNull(port.published.last().getString("lyricInfo"))
        assertNull(port.published.last().getString("oplus.media.config"))
    }

    @Test
    fun `ColorOS oversize full metadata drops only vendor fields and preserves media identity`() {
        val artwork = smallArtwork("cover")
        val snapshot = serviceMetadataSnapshot(song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), artwork, 3, 1, "content://cover", "queue-id")
            .copy(colorOsLyricInfo = "a".repeat(300_000))
        val metadata = serviceMediaMetadata(snapshot, artwork)
        assertNull(metadata.getString("lyricInfo"))
        assertNull(metadata.getString("oplus.media.config"))
        assertEquals("queue-id", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals(2L, metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER))
        assertEquals("content://cover", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
        assertEquals(artwork.mediaBitmap, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }

    @Test
    fun `ColorOS measures the complete parcel including preexisting text`() {
        val album = "a".repeat(300_000)
        val artwork = smallArtwork("cover")
        val snapshot = serviceMetadataSnapshot(song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), artwork)
            .copy(album = album, colorOsLyricInfo = "small lyric")
        val metadata = serviceMediaMetadata(snapshot, artwork)
        assertNull(metadata.getString("lyricInfo"))
        assertNull(metadata.getString("oplus.media.config"))
        assertEquals(album, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
    }

    @Test
    fun `ColorOS keeps lyrics and visible artwork within the full parcel budget`() {
        val snapshot = serviceMetadataSnapshot(song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), readyArtwork("cover"))
            .copy(colorOsLyricInfo = "small lyric")
        val metadata = serviceMediaMetadata(snapshot, readyArtwork("cover"))
        assertEquals("small lyric", metadata.getString("lyricInfo"))
        assertEquals("1", metadata.getString("oplus.media.config"))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertTrue(parcelSize(metadata) <= 512 * 1024)
        assertEquals(512, mediaBitmap.width)
        assertEquals(256, iconBitmap.width)
    }

    @Test
    fun `an accepting session receives ColorOS lyrics even with the normal large cover`() = runTest {
        val source = FakeSource().apply { colorOsLyricsEnabled = true; colorOsLyricInfo = "loaded timeline" }
        val port = RecordingPort(rejectBitmaps = false)
        val owner = owner(port, source, artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()

        val metadata = port.published.single()
        assertEquals("loaded timeline", metadata.getString("lyricInfo"))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertTrue(parcelSize(metadata) <= 512 * 1024)
        assertEquals(1, port.attempts)
    }

    @Test
    fun `ColorOS retains a near-limit lyric payload while shrinking only metadata artwork`() {
        val artwork = readyArtwork("cover")
        val lyric = "a".repeat(128 * 1024)
        val snapshot = serviceMetadataSnapshot(song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), artwork)
            .copy(colorOsLyricInfo = lyric)

        val metadata = serviceMediaMetadata(snapshot, artwork)

        assertEquals(lyric, metadata.getString("lyricInfo"))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertTrue(parcelSize(metadata) <= 512 * 1024)
    }

    @Test
    fun `ColorOS keeps artwork URIs when text leaves no room for embedded thumbnails`() {
        val artwork = readyArtwork("cover")
        val snapshot = serviceMetadataSnapshot(
            song, serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false), artwork,
            artworkUri = "content://covers/a.jpg", mediaId = "queue-id",
        ).copy(album = "a".repeat(240_000), colorOsLyricInfo = "small lyric")

        val metadata = serviceMediaMetadata(snapshot, artwork)

        assertEquals("small lyric", metadata.getString("lyricInfo"))
        assertEquals("queue-id", metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals("content://covers/a.jpg", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
        assertEquals("content://covers/a.jpg", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
        assertNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertTrue(parcelSize(metadata) <= 512 * 1024)
        assertTrue(!mediaBitmap.isRecycled && !iconBitmap.isRecycled)
    }

    @Test
    fun `a session rejecting ColorOS artwork retains lyrics in the bitmap-free retry`() = runTest {
        val source = FakeSource().apply { colorOsLyricsEnabled = true; colorOsLyricInfo = "loaded timeline" }
        val port = RecordingPort(rejectBitmaps = true)
        val owner = owner(port, source, artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()

        assertEquals(2, port.attempts)
        val fallback = port.published.single()
        assertEquals("loaded timeline", fallback.getString("lyricInfo"))
        assertEquals("1", fallback.getString("oplus.media.config"))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertTrue(parcelSize(fallback) <= 512 * 1024)
    }

    @Test
    fun `accepted metadata carries the large artwork once plus the display icon`() = runTest {
        val port = RecordingPort(rejectBitmaps = false)
        val owner = owner(port, FakeSource(), artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()

        val metadata = port.published.single()
        assertEquals(mediaBitmap, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(iconBitmap, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
    }

    @Test
    fun `rejected artwork metadata is republished without bitmaps instead of crashing`() = runTest {
        val port = RecordingPort(rejectBitmaps = true)
        val source = FakeSource()
        val artwork = artworkFor("content://covers/a.jpg")
        val owner = owner(port, source, artwork)

        owner.updateMetadata()

        assertEquals(2, port.attempts)
        val fallback = port.published.single()
        assertEquals("Song", fallback.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(fallback.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))

        source.payload = ExternalBluetoothLyricPayload("next line")
        owner.updateMetadata()

        assertEquals("same rejected cover skips the bitmap attempt", 3, port.attempts)
        assertEquals("next line", port.published.last().getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(port.published.last().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }

    @Test
    fun `a new cover retries bitmap publication after an earlier rejection`() = runTest {
        val port = RecordingPort(rejectBitmaps = true)
        val artwork = artworkFor("content://covers/a.jpg")
        val owner = owner(port, FakeSource(), artwork)
        owner.updateMetadata()
        port.rejectBitmaps = false
        `when`(artwork.observe(song)).thenReturn(readyArtwork("content://covers/b.jpg"))

        owner.updateMetadata()

        assertEquals(3, port.attempts)
        assertNotNull(port.published.last().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }

    @Test
    fun `a session that rejects every publish is retried on the next update`() = runTest {
        val port = RecordingPort(rejectBitmaps = true, rejectAll = true)
        val owner = owner(port, FakeSource(), artworkFor("content://covers/a.jpg"))

        owner.updateMetadata()
        owner.updateMetadata()

        assertEquals(3, port.attempts)
        assertEquals(emptyList<MediaMetadata>(), port.published)
    }

    private fun kotlinx.coroutines.test.TestScope.owner(
        port: RecordingPort,
        source: FakeSource,
        artwork: PlaybackArtworkOwner,
    ) = PlaybackServicePresentationOwner(source, port.port, artwork, backgroundScope)

    private fun artworkFor(coverSource: String, snapshot: PlaybackArtworkSnapshot = readyArtwork(coverSource)): PlaybackArtworkOwner {
        val artwork = mock(PlaybackArtworkOwner::class.java)
        `when`(artwork.observe(song)).thenReturn(snapshot)
        return artwork
    }

    private fun smallArtwork(coverSource: String) = PlaybackArtworkSnapshot(
        coverSource,
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
        true, true, false,
    )

    private fun readyArtwork(coverSource: String) =
        PlaybackArtworkSnapshot(coverSource, mediaBitmap, iconBitmap, true, true, false)

    private fun parcelSize(metadata: MediaMetadata): Int {
        val parcel = Parcel.obtain()
        return try {
            metadata.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private class RecordingPort(
        var rejectBitmaps: Boolean,
        val rejectAll: Boolean = false,
    ) : PlaybackServicePresentationPort by mock(PlaybackServicePresentationPort::class.java) {
        val published = mutableListOf<MediaMetadata>()
        var attempts = 0
        val port: PlaybackServicePresentationPort get() = this

        override fun setMetadata(metadata: MediaMetadata) {
            attempts++
            val hasBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
            if (rejectAll || (rejectBitmaps && hasBitmap)) {
                throw RuntimeException("android.os.TransactionTooLargeException: data parcel size 2101452 bytes")
            }
            published += metadata
        }
    }

    private inner class FakeSource : PlaybackServicePresentationSource {
        var payload = ExternalBluetoothLyricPayload()
        var colorOsLyricsEnabled = false
        var colorOsLyricInfo: String? = null

        override fun playback() = PlaybackServicePlaybackSnapshot(
            song = song,
            playerSongPresent = true,
            playerPositionMs = 0L,
            roomPositionMs = 0L,
            buffering = false,
            transportActive = true,
            enginePlaying = true,
            roomPlaying = false,
            playbackControlPlaying = true,
            audioRouteMuted = false,
            playbackSpeed = 1f,
        )
        override fun metadata() = PlaybackServiceMetadataInputs(payload, null, forceSendLyrics = true, colorOsLyricsEnabled = colorOsLyricsEnabled, colorOsLyricInfo = colorOsLyricInfo)
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = emptySet<String>()
        override fun localPlaylistsReady() = true
        override fun isLocalSong(song: SongItem) = false
        override fun shareUrl(song: SongItem): String? = null
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) = Unit
    }
}
