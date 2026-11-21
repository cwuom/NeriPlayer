package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanStreamSessionSongTest {
    @Test fun localRoomRowRetainsItsIdentityWithoutAnAudioPath() {
        val track = ListenTogetherTrack("local:opaque-id", "local", "opaque-id", name = "Shared", artist = "Host")
        val song = lanStreamSessionSong(track)

        assertEquals("lan-session:local:opaque-id", song.sourceStableKey)
        assertEquals(track.stableKey, LanLocalIdentity.track(song)?.stableKey)
        assertNull(song.mediaUri)
        assertNull(song.localFilePath)
    }

    @Test fun subsonicRoomsUseAnOpaqueIdentityInBothDirections() {
        val ref = ServerSongRef("70ebc33c-e7e2-43f1-8a3d-2f0bd8ff55e8", "private-track-id")
        val source = SongItem(ref.numericId, "Song", "Artist", "Album", 0, 60_000L, ref.resourceUrl("cover"),
            channelId = ServerSongRef.CHANNEL, audioId = ref.audioId, mediaUri = ref.mediaUri)

        val track = checkNotNull(LanPlaybackBinding.track(source))
        assertEquals("subsonic", track.channelId)
        assertTrue(track.audioId.matches(Regex("[a-f0-9]{64}")))
        assertFalse(track.stableKey.contains(ref.profileId))
        assertFalse(track.stableKey.contains(ref.songId))
        assertNull(track.coverUrl)
        assertNull(track.mediaUri)
        assertNull(track.streamUrl)

        val guest = lanStreamSessionSong(track)
        assertTrue(isLanTemporaryServerSong(guest))
        assertNull(ServerSongRef.from(guest))
        assertEquals(track.stableKey, LanPlaybackBinding.track(guest)?.stableKey)
    }
}
