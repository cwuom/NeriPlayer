package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedLyricsBundle
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.LyricKind
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ManagedDownloadStorageLyricsSidecarTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context = mock(Context::class.java)

    @Test
    fun `lyric sidecars are complete only when every kind has a sidecar`() {
        assertTrue(storage.hasCompleteLyricsSidecars(sidecars(original = true, translated = true, romanized = true)))
        assertFalse(storage.hasCompleteLyricsSidecars(sidecars(original = false, translated = true, romanized = true)))
        assertFalse(storage.hasCompleteLyricsSidecars(sidecars(original = true, translated = false, romanized = true)))
        assertFalse(storage.hasCompleteLyricsSidecars(sidecars(original = true, translated = true, romanized = false)))
    }

    @Test
    fun `merged lyrics follow sidecar evidence before plain fallback values`() {
        val preferredSidecars = DownloadedLyricsBundle("p-lyric", "p-trans", "p-roma", true, true, true)
        val fallbackSidecars = DownloadedLyricsBundle("f-lyric", "f-trans", "f-roma", true, true, true)
        val preferredPlain = DownloadedLyricsBundle("p-lyric", null, null)
        val fallbackPlain = DownloadedLyricsBundle("f-lyric", "f-trans", null)

        assertEquals(preferredSidecars, storage.mergeLyricsBundles(preferredSidecars, fallbackPlain))
        assertEquals(fallbackSidecars, storage.mergeLyricsBundles(preferredPlain, fallbackSidecars))
        assertEquals(
            DownloadedLyricsBundle("p-lyric", "f-trans", null),
            storage.mergeLyricsBundles(preferredPlain, fallbackPlain)
        )
    }

    @Test
    fun `legacy references are absolute paths inside the legacy download root`() {
        assertTrue(storage.isLegacyDownloadReference("/storage/emulated/0/neriplayer-download/song.mp3"))
        assertTrue(storage.isLegacyDownloadReference(" /storage/emulated/0/neriplayer-download/ "))
        assertFalse(storage.isLegacyDownloadReference("/storage/emulated/0/neriplayer-download-old/song.mp3"))
        assertFalse(storage.isLegacyDownloadReference("content://media/external/audio/media/7"))
        assertFalse(storage.isLegacyDownloadReference("neriplayer-download/song.mp3"))
    }

    @Test
    fun `named lyric files are read per kind across directories unless already read`() {
        val lyrics = temporaryFolder.newFolder("Lyrics")
        File(lyrics, "Song.lrc").writeText("original")
        File(lyrics, "Song_trans.lrc.txt").writeText("translated")
        File(temporaryFolder.root, "Song_romanized.lrc").writeText("romanized")
        val directories = listOf(lyrics, temporaryFolder.root)

        assertEquals(
            mapOf(
                LyricKind.ORIGINAL to ("original" to true),
                LyricKind.TRANSLATED to ("translated" to true),
                LyricKind.ROMANIZED to ("romanized" to true)
            ),
            storage.readLyricsFromNamedFiles(context, directories, listOf("Missing", "Song"), emptySet())
        )
        assertEquals(
            mapOf(LyricKind.TRANSLATED to ("translated" to true)),
            storage.readLyricsFromNamedFiles(
                context,
                listOf(lyrics),
                listOf("Song"),
                alreadyRead = setOf(LyricKind.ORIGINAL)
            )
        )
        assertTrue(storage.readLyricsFromNamedFiles(context, directories, listOf("Missing"), emptySet()).isEmpty())
    }

    @Test
    fun `file root lyric lookup also searches beside an absolute song path`() {
        val rootDir = temporaryFolder.newFolder("root")
        File(rootDir, "Lyrics").mkdirs()
        File(rootDir, "Lyrics/Song.lrc").writeText("original in root")
        val albumDir = temporaryFolder.newFolder("album")
        File(albumDir, "Song_trans.lrc").writeText("translated beside song")
        val root = ManagedDownloadRootHandle.FileRoot(rootDir)

        val besideSong = storage.readLyricsFromFileRootFast(
            context,
            root,
            song(localFilePath = File(albumDir, "Song.mp3").absolutePath),
            listOf("Song"),
            emptySet()
        )

        assertEquals(
            mapOf(
                LyricKind.ORIGINAL to ("original in root" to true),
                LyricKind.TRANSLATED to ("translated beside song" to true)
            ),
            besideSong
        )
        listOf(
            null,
            "album/Song.mp3",
            "/",
            "/storage/emulated/0/neriplayer-download/Song.mp3"
        ).forEach { localFilePath ->
            assertEquals(
                localFilePath.toString(),
                setOf(LyricKind.ORIGINAL),
                storage.readLyricsFromFileRootFast(
                    context,
                    root,
                    song(localFilePath = localFilePath),
                    listOf("Song"),
                    emptySet()
                ).keys
            )
        }
    }

    @Test
    fun `direct song path lookup reads sidecars next to an absolute local file`() {
        val albumDir = temporaryFolder.newFolder("album")
        File(albumDir, "Lyrics").mkdirs()
        File(albumDir, "Lyrics/Song_roma.lrc").writeText("romanized")
        val localSong = song(localFilePath = File(albumDir, "Song.mp3").absolutePath)

        assertEquals(
            DownloadedLyricsBundle(null, null, "romanized", hasRomanizedSidecar = true),
            storage.readLyricsBundleFromDirectSongPathFast(context, localSong)
        )
        assertEquals(
            DownloadedLyricsBundle(null, null, null),
            storage.readLyricsBundleFromDirectSongPathFast(
                context,
                song(localFilePath = null, mediaUri = "content://media/external/audio/media/7")
            )
        )
    }

    @Test
    fun `lyric base names combine song candidates with the best known audio file name`() {
        val song = song(
            localFileName = "Local Name.mp3",
            localFilePath = "/music/Path Name.mp3",
            mediaUri = "/music/Media Name.mp3"
        )
        val songWithoutFile = song.copy(localFileName = null, localFilePath = null, mediaUri = null)
        val songCandidates = candidateManagedDownloadBaseNames(songWithoutFile, storage.settings.fileNameTemplate)

        assertBaseNames(storage.buildManagedLyricBaseNames(song, audioName = "Audio Name.flac"), "Audio Name")
        assertBaseNames(storage.buildManagedLyricBaseNames(song), "Local Name")
        assertBaseNames(storage.buildManagedLyricBaseNames(song.copy(localFileName = null)), "Path Name")
        assertBaseNames(
            storage.buildManagedLyricBaseNames(song.copy(localFileName = null, localFilePath = null)),
            "Media Name"
        )
        assertEquals(songCandidates.distinct(), storage.buildManagedLyricBaseNames(songWithoutFile))
        assertEquals(
            storage.buildManagedLyricBaseNames(songWithoutFile),
            storage.buildManagedLyricBaseNames(songWithoutFile, audioName = ".flac")
        )
    }

    private fun assertBaseNames(names: List<String>, fileBaseName: String) {
        assertEquals(names.distinct(), names)
        assertTrue(names.toString(), names.containsAll(candidateManagedDownloadBaseNames(fileBaseName)))
    }

    private fun sidecars(original: Boolean, translated: Boolean, romanized: Boolean): DownloadedLyricsBundle {
        return DownloadedLyricsBundle(
            lyric = null,
            translatedLyric = null,
            romanizedLyric = null,
            hasOriginalSidecar = original,
            hasTranslatedSidecar = translated,
            hasRomanizedSidecar = romanized
        )
    }

    private fun song(
        localFilePath: String? = null,
        localFileName: String? = null,
        mediaUri: String? = null
    ): SongItem {
        return SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = localFileName,
            localFilePath = localFilePath,
            channelId = "netease",
            audioId = "7"
        )
    }
}
