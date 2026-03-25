package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudioImportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `copyNearbySidecars keeps track specific cover ahead of generic folder art`() {
        val sourceDir = tempFolder.newFolder("source-track-cover")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "song.png").writeText("track-cover")
        File(sourceDir, "cover.jpg").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-track-cover")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val copiedTrackCover = File(targetDir, "imported_song.png")
        val copiedGenericCover = File(File(targetDir, "Covers"), "imported_song.jpg")
        val resolvedCover = LocalMediaSupport.findNearbyCover(targetAudio)

        assertTrue(copiedTrackCover.exists())
        assertTrue(copiedGenericCover.exists())
        assertNotNull(resolvedCover)
        assertEquals(copiedTrackCover.canonicalPath, resolvedCover?.canonicalPath)
    }

    @Test
    fun `copyNearbySidecars stores generic folder art in Covers fallback path`() {
        val sourceDir = tempFolder.newFolder("source-generic-cover")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(sourceDir, "folder.jpg").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-generic-cover")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        LocalAudioImportManager.copyNearbySidecars(sourceAudio, targetAudio)

        val unexpectedSiblingCover = File(targetDir, "imported_song.jpg")
        val copiedGenericCover = File(File(targetDir, "Covers"), "imported_song.jpg")
        val resolvedCover = LocalMediaSupport.findNearbyCover(targetAudio)

        assertFalse(unexpectedSiblingCover.exists())
        assertTrue(copiedGenericCover.exists())
        assertEquals(copiedGenericCover.canonicalPath, resolvedCover?.canonicalPath)
    }

    @Test
    fun `buildNearbySidecarCopyPlans keeps source Covers artwork as track specific target`() {
        val sourceDir = tempFolder.newFolder("source-cover-dir")
        val sourceAudio = File(sourceDir, "song.flac").apply { writeText("audio") }
        File(File(sourceDir, "Covers").apply { mkdirs() }, "song.jpg").writeText("track-cover")
        File(sourceDir, "cover.png").writeText("generic-cover")

        val targetDir = tempFolder.newFolder("imports-cover-dir")
        val targetAudio = File(targetDir, "imported_song.flac").apply { writeText("audio") }

        val plans = buildNearbySidecarCopyPlans(
            sourceFile = sourceAudio,
            targetFile = targetAudio,
            lyricExtensions = listOf("lrc", "txt"),
            imageExtensions = listOf("jpg", "jpeg", "png", "webp"),
            coverNames = listOf("cover", "folder", "front")
        )

        assertTrue(
            plans.any { plan ->
                plan.source.name == "song.jpg" &&
                    plan.target.canonicalPath == File(targetDir, "imported_song.jpg").canonicalPath
            }
        )
        assertTrue(
            plans.any { plan ->
                plan.source.name == "cover.png" &&
                    plan.target.canonicalPath == File(File(targetDir, "Covers"), "imported_song.png").canonicalPath
            }
        )
    }
}
