package moe.ouom.neriplayer.ui.screen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.DefaultEditSongBaselineReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NowPlayingDefaultEditSongBaselineReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearCaches() {
        LocalMediaSupport.clearLyricsLookupCache()
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `ordinary local songs read persisted originals rather than current overrides`() = runTest {
        val audio = temporaryFolder.newFile("song.mp3")
        File(audio.path + ".npmeta.json").writeText(
            """{"name":"Song","artist":"Artist","originalName":"Original title",
                "originalArtist":"Original artist","originalCoverUrl":"",
                "originalLyric":"","originalTranslatedLyric":"original translation",
                "originalRomanizedLyric":"original romanization","matchedLyric":"edited lyric"}"""
        )

        val metadata = requireNotNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))

        assertEquals("Original title", metadata.baseline.title)
        assertEquals("Original artist", metadata.baseline.artist)
        assertEquals("", metadata.baseline.coverReference)
        assertEquals("", metadata.baseline.originalLyric)
        assertEquals("original translation", metadata.baseline.translatedLyric)
        assertEquals("original romanization", metadata.baseline.romanizedLyric)
        assertEquals("edited lyric", metadata.overrides.originalLyric)
    }

    @Test
    fun `missing and invalid local metadata leave the restore baseline unavailable`() = runTest {
        val audio = temporaryFolder.newFile("missing.mp3")
        assertNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))

        File(audio.path + ".npmeta.json").writeText("not valid json")
        assertNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))
    }

    @Test
    fun `remote songs do not adopt a local metadata file`() = runTest {
        val remote = song(temporaryFolder.newFile("remote.mp3")).copy(
            mediaUri = "https://music.example/song.mp3", localFilePath = null
        )

        assertNull(DefaultEditSongBaselineReader.metadata(context, remote))
    }

    private fun song(audio: File) = SongItem(
        8L, "Edited title", "Edited artist", "__local_files__", 0L, 60_000L, null,
        mediaUri = audio.toURI().toString(), localFilePath = audio.path, localFileName = audio.name
    )
}
