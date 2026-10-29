package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LanHostedMediaTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun hostReusesPublishedFileAndPresentationWithoutCreatingCopies() {
        val audioFile = temporary.newFile().apply { writeText("abc") }
        val cover = temporary.newFile().apply { writeText("cover") }
        val descriptor = LanLocalMedia("12345678-1234-1234-1234-123456789012", "a".repeat(64), 3, 60000, "audio/mpeg")
        val metadata = LanSharedPresentation("Custom title", "Artist", "Album", 120,
            listOf(LanSharedLyricLine("Words", 0, 1000)))
        val audio = LanAudioFile(audioFile, LanAudioFormat("audio/mpeg", 192, 48000, 2, null))
        val prepared = LanHostedMedia(descriptor, audio, metadata, cover)
        val track = ListenTogetherTrack(descriptor.stableKey, "local", descriptor.sha256, name = "Old", artist = "Old", durationMs = 60000)
        val before = temporary.root.listFiles()!!.toSet()
        val (result, song) = prepared.read(descriptor, track)
        assertSame(audio, result)
        assertEquals(audioFile.absolutePath, song.localFilePath)
        assertEquals(cover.absolutePath, song.coverUrl)
        assertEquals("Custom title", song.name)
        assertEquals(120L, song.userLyricOffsetMs)
        assertEquals(before, temporary.root.listFiles()!!.toSet())
        try { prepared.read(descriptor.copy(sha256 = "b".repeat(64)), track); fail("Wrong snapshot accepted") }
        catch (expected: IllegalStateException) { assertEquals("AUDIO_CONTENT", expected.message) }
        audioFile.delete()
        try { prepared.read(descriptor, track); fail("Missing snapshot accepted") }
        catch (expected: IllegalStateException) { assertEquals("LOCAL_READ", expected.message) }
    }
}
