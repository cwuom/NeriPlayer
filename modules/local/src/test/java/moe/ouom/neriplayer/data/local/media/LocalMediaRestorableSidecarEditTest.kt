package moe.ouom.neriplayer.data.local.media

import android.content.Context
import moe.ouom.neriplayer.core.download.storage.metadata.codec.ManagedDownloadedAudioMetadataDecoder
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File

class LocalMediaRestorableSidecarEditTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `restoring metadata removes nested overrides before the download catalog refreshes`() {
        val root = write(song(), writeLyrics = true, clearCover = true)
        val decoded = ManagedDownloadedAudioMetadataDecoder(root).decode()

        assertNull(decoded.customName)
        assertNull(decoded.customArtist)
        assertEquals("[00:01.00]original", decoded.matchedLyric)
        assertNull(decoded.matchedTranslatedLyric)
        assertNull(decoded.matchedRomanizedLyric)
        assertEquals(0L, decoded.userLyricOffsetMs)
        assertEquals("Original", decoded.restorableMetadata?.baseline?.title)
        assertEquals("", root.getJSONObject("restorableMetadata").getJSONObject("baseline").getString("coverReference"))
        assertEquals("publication-receipt", root.getString("operationId"))
        assertEquals("keep", root.getJSONObject("restorableMetadata").getString("futureField"))
    }

    @Test
    fun `repeated local edits publish current nested lyrics instead of stale overrides`() {
        val edited = song().copy(
            customName = "New title",
            customArtist = "New artist",
            customCoverUrl = "/new-cover.jpg",
            matchedLyric = "[00:02.00]new",
            matchedTranslatedLyric = "[00:02.00]translation",
            matchedRomanizedLyric = "[00:02.00]romanization",
            userLyricOffsetMs = 125L
        )
        repeat(3) {
            val decoded = ManagedDownloadedAudioMetadataDecoder(write(edited, writeLyrics = true)).decode()
            assertEquals("New title", decoded.customName)
            assertEquals("New artist", decoded.customArtist)
            assertEquals("[00:02.00]new", decoded.matchedLyric)
            assertEquals("[00:02.00]translation", decoded.matchedTranslatedLyric)
            assertEquals("[00:02.00]romanization", decoded.matchedRomanizedLyric)
            assertEquals(125L, decoded.userLyricOffsetMs)
            assertEquals("Original", decoded.restorableMetadata?.baseline?.title)
        }
    }

    @Test
    fun `metadata only edit leaves nested lyric overrides intact`() {
        val decoded = ManagedDownloadedAudioMetadataDecoder(write(song(), writeLyrics = false)).decode()

        assertEquals("[00:09.00]old edit", decoded.matchedLyric)
        assertEquals("old translation", decoded.matchedTranslatedLyric)
        assertEquals(900L, decoded.userLyricOffsetMs)
        assertNull(decoded.customName)
    }

    @Test
    fun `lyric only save updates nested lyrics without changing title overrides`() {
        val root = write(song(), writeLyrics = true, fullMetadata = false)
        val decoded = ManagedDownloadedAudioMetadataDecoder(root).decode()

        assertEquals("Edited", decoded.customName)
        assertEquals("[00:01.00]original", decoded.matchedLyric)
        assertEquals("Original", decoded.restorableMetadata?.baseline?.title)
    }

    @Test
    fun `restoring a confirmed empty translation removes the previous displayed edit`() {
        val restored = song().copy(originalTranslatedLyric = "", originalRomanizedLyric = "")
        val decoded = ManagedDownloadedAudioMetadataDecoder(write(restored, writeLyrics = true)).decode()

        assertEquals("", decoded.matchedTranslatedLyric)
        assertEquals("", decoded.matchedRomanizedLyric)
    }

    private fun write(
        song: SongItem,
        writeLyrics: Boolean,
        clearCover: Boolean = false,
        fullMetadata: Boolean = true
    ): JSONObject {
        val audio = temporaryFolder.newFile("song-${temporaryFolder.root.listFiles().orEmpty().size}.flac")
        val sidecar = File(audio.parentFile, audio.name + ".npmeta.json").apply { writeText(existing) }
        assertTrue(LocalMediaSupport.writeLocalLyricsMetadataReference(
            context = mock(Context::class.java),
            reference = sidecar.absolutePath,
            file = audio,
            song = song,
            writeFullMetadata = fullMetadata,
            writeLyricFields = writeLyrics,
            clearCoverReference = clearCover
        ))
        return JSONObject(sidecar.readText())
    }

    private fun song() = SongItem(
        id = 51L, name = "Original", artist = "Original artist", album = "Album",
        albumId = 0L, durationMs = 180_000L, coverUrl = null,
        originalName = "Original", originalArtist = "Original artist", originalCoverUrl = "",
        originalLyric = "[00:01.00]original", matchedLyric = "[00:01.00]original"
    )

    private val existing = """
        {"operationId":"publication-receipt","customName":"Edited","customArtist":"Edited artist",
        "restorableMetadata":{"futureField":"keep","baseline":{"title":"Original",
        "artist":"Original artist","coverReference":"","originalLyric":"[00:01.00]original"},
        "overrides":{"title":"Edited","artist":"Edited artist","coverReference":"/old-cover.jpg",
        "originalLyric":"[00:09.00]old edit","translatedLyric":"old translation",
        "romanizedLyric":"old romanization","userLyricOffsetMs":900}}}
    """.trimIndent()
}
