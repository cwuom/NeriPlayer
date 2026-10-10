package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudioImportKnownSidecarReferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `lyrics and metadata are found in the lyrics folder before the song folder`() {
        val album = temporaryFolder.newFolder("Album")
        val song = File(album, "Night Drive.flac").apply { writeText("audio") }
        val original = File(album, "Lyrics/Night Drive.lrc").apply { parentFile?.mkdirs(); writeText("[00:01]drive") }
        File(album, "Night Drive.lrc").writeText("[00:01]shadowed")
        val translated = File(album, "Night Drive_trans.lrc").apply { writeText("[00:01]translated") }
        val metadata = File(album, "Night Drive.flac.npmeta.json").apply { writeText("{}") }
        File(album, "Night Drive_roma.lrc").mkdirs()
        File(album, "notes.md").writeText("not a sidecar")

        val resolved = with(LocalAudioImportManager) {
            buildKnownSidecarReferencesForSongs(listOf(localSong(song)), emptyMap())
        }

        assertEquals(
            mapOf(
                song.absolutePath to LocalKnownSidecarReferences(
                    lyrics = NearbyLyricReferences(
                        original = original.absolutePath,
                        translated = translated.absolutePath,
                        romanized = null
                    ),
                    metadata = metadata.absolutePath
                )
            ),
            resolved
        )
    }

    @Test
    fun `already known references win and songs without a local file are left alone`() {
        val folder = temporaryFolder.newFolder("Singles")
        val song = File(folder, "Rain.mp3").apply { writeText("audio") }
        val romanized = File(folder, "Rain_romanized.txt").apply { writeText("ame") }
        File(folder, "Rain.lrc").writeText("[00:01]local")
        File(folder, "Rain.mp3.npmeta.json").writeText("{}")
        val known = LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(original = "content://known/rain.lrc", translated = null, romanized = null),
            metadata = "content://known/rain.json",
            cover = "content://known/rain.jpg"
        )
        val unrelated = LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(original = null, translated = null, romanized = null)
        )
        val mediaStoreSong = localSong(File(folder, "Remote.mp3")).copy(
            mediaUri = "content://media/external/audio/media/5",
            localFilePath = null
        )

        val resolved = with(LocalAudioImportManager) {
            buildKnownSidecarReferencesForSongs(
                listOf(localSong(song), mediaStoreSong, localSong(song).copy(mediaUri = null)),
                mapOf(song.absolutePath to known, "content://elsewhere" to unrelated)
            )
        }

        assertEquals(
            mapOf(
                song.absolutePath to known.copy(
                    lyrics = known.lyrics.copy(romanized = romanized.absolutePath)
                ),
                "content://elsewhere" to unrelated
            ),
            resolved
        )
    }

    @Test
    fun `no songs keep the known references unchanged`() {
        val known = mapOf<String, LocalKnownSidecarReferences?>("content://known" to null)

        assertSame(known, with(LocalAudioImportManager) { buildKnownSidecarReferencesForSongs(emptyList(), known) })
    }

    private fun localSong(file: File) = SongItem(
        id = file.name.hashCode().toLong(),
        name = file.nameWithoutExtension,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        mediaUri = file.absolutePath,
        localFileName = file.name,
        localFilePath = file.absolutePath,
        channelId = "local"
    )
}
