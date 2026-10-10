package moe.ouom.neriplayer.data.identity

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVisualKeyAliasesTest {
    @Test
    fun `path like source keys are too volatile to own visual caches`() {
        listOf(
            "/storage/emulated/0/Music/song.mp3",
            "Content://media/external/audio/media/1",
            "tree-file://primary/Music/song.mp3",
            "5|__local_files__|"
        ).forEach { sourceKey ->
            assertEquals(sourceKey, listOf("local-audio:77"), localSong(sourceKey).playbackVisualKeyAliases())
        }
    }

    @Test
    fun `remote source keys pointing at local media are not reused as local sources`() {
        assertEquals(
            listOf("remote:12|netease|/storage/emulated/0/Music/song.mp3", "local-audio:77"),
            localSong("12|netease|/storage/emulated/0/Music/song.mp3").playbackVisualKeyAliases()
        )
        assertEquals(
            listOf("remote:12|netease|android.resource://pkg/raw/song", "local-audio:77"),
            localSong("12|netease|android.resource://pkg/raw/song").playbackVisualKeyAliases()
        )
    }

    @Test
    fun `stable remote source keys are kept as both remote and local source aliases`() {
        assertEquals(
            listOf("remote:12|netease|", "local-source:12|netease|", "local-audio:77"),
            localSong(" 12|netease| ").playbackVisualKeyAliases()
        )
        assertEquals(
            listOf("remote:12|netease|https://cdn.example.com/song", "local-source:12|netease|https://cdn.example.com/song", "local-audio:77"),
            localSong("12|netease|https://cdn.example.com/song").playbackVisualKeyAliases()
        )
    }

    @Test
    fun `source keys that are not stable identities stay local source aliases`() {
        listOf(
            "plain-source-key",
            "|netease|/storage/emulated/0/Music/song.mp3",
            "12|netease",
            "abc|netease|/storage/emulated/0/Music/song.mp3",
            "12| |/storage/emulated/0/Music/song.mp3"
        ).forEach { sourceKey ->
            assertEquals(
                sourceKey,
                listOf("local-source:$sourceKey", "local-audio:77"),
                localSong(sourceKey).playbackVisualKeyAliases()
            )
        }
    }

    @Test
    fun `visual keys prefer stable local sources over audio ids`() {
        assertEquals("local-source:plain-source-key", localSong("plain-source-key").playbackVisualKey())
        assertEquals("local-audio:77", localSong("/storage/emulated/0/Music/song.mp3").playbackVisualKey())
        assertEquals("remote:12|netease|", localSong("12|netease|").playbackVisualKey())
    }

    private fun localSong(sourceKey: String) = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        localFilePath = "/music/song.mp3",
        channelId = "local",
        audioId = "77",
        sourceStableKey = sourceKey
    )
}
