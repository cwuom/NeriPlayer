package moe.ouom.neriplayer.core.player.service.presentation

import android.media.MediaMetadata
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarMediaMetadataInstrumentedTest {
    @Test
    fun frameworkMetadataPublishesAlbumIdentityTrackCountsAndLocalArtworkUri() {
        val song = SongItem(1L, "Song", "Artist", "Album", 1L, 42_000L, null)
        val artwork = PlaybackArtworkSnapshot(null, null, null, false, false, false)
        val text = serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false)
        val mediaId = serviceMetadataMediaId(song, listOf(song, song, song, song, song), 2)
        val snapshot = serviceMetadataSnapshot(song, text, artwork, 5, 2, "content://example.car-artwork/v1/image", mediaId)
        val metadata = serviceMediaMetadata(snapshot, artwork)
        assertEquals("Song", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals(mediaId, metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
        assertEquals(42_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(3L, metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER))
        assertEquals(5L, metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS))
        assertEquals(snapshot.artworkUri, metadata.getString(MediaMetadata.METADATA_KEY_ART_URI))
        assertEquals(snapshot.artworkUri, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
        assertEquals(snapshot.artworkUri, metadata.description.iconUri.toString())
        assertNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
    }
}
