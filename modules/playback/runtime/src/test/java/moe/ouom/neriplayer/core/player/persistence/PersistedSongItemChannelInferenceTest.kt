package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedSongItemChannelInferenceTest {

    @Test
    fun `an explicit channel survives restore and is not treated as a local audio id`() {
        val restored = persisted(channelId = "netease").toSongItem()

        assertEquals("netease", restored.channelId)
        assertNull(restored.audioId)
    }

    @Test
    fun `a local file path marks the song as local and reuses its id as audio id`() {
        val restored = persisted(localFilePath = "/music/a.flac").toSongItem()

        assertEquals("local", restored.channelId)
        assertEquals("42", restored.audioId)
    }

    @Test
    fun `an explicit audio id wins over the inferred local id`() {
        val restored = persisted(localFilePath = "/music/a.flac", audioId = "file-7").toSongItem()

        assertEquals("local", restored.channelId)
        assertEquals("file-7", restored.audioId)
    }

    @Test
    fun `a local media uri marks songs without a usable file path as local`() {
        listOf(null, "  ").forEach { path ->
            val restored = persisted(
                localFilePath = path,
                mediaUri = "content://media/external/audio/media/5"
            ).toSongItem()

            assertEquals("local", restored.channelId)
            assertEquals("42", restored.audioId)
        }
    }

    @Test
    fun `remote media without a channel stays unclassified`() {
        listOf(null, "  ").forEach { path ->
            val restored = persisted(localFilePath = path, mediaUri = "https://music.example/a.mp3").toSongItem()

            assertNull(restored.channelId)
            assertNull(restored.audioId)
        }
    }

    @Test
    fun `restoring and persisting again keeps every stored field`() {
        val stored = PersistedSongItem(
            id = 42, name = "Song", artist = "Artist", album = "Album", albumId = 9, durationMs = 180_000,
            coverUrl = "https://cover", mediaUri = "https://media", matchedLyric = "lyric",
            matchedTranslatedLyric = "translation", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "7", userLyricOffsetMs = 120, customCoverUrl = "https://custom-cover",
            customName = "Custom", customArtist = "Custom Artist", originalName = "Original",
            originalArtist = "Original Artist", originalCoverUrl = "https://original-cover",
            originalLyric = "base", originalTranslatedLyric = "base translation", localFileName = "a.flac",
            localFilePath = "/music/a.flac", channelId = "netease", audioId = "audio", subAudioId = "sub",
            playlistContextId = "playlist", streamUrl = "https://stream", matchedRomanizedLyric = "romanized",
            originalRomanizedLyric = "base romanized", lyricSyncRevision = 3, lyricSyncEdited = true
        )

        assertEquals(stored, stored.toSongItem().toPersistedSongItem())
    }

    private fun persisted(
        channelId: String? = null,
        audioId: String? = null,
        localFilePath: String? = null,
        mediaUri: String? = null
    ) = PersistedSongItem(
        id = 42,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 9,
        durationMs = 180_000,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId
    )
}
