package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NearbySidecarCopyPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `existing lyrics and artwork are planned next to the imported copy`() {
        val source = temporaryFolder.newFolder("source")
        val target = temporaryFolder.newFolder("target")
        val audio = File(source, "Song.flac").apply { writeText("audio") }
        val lyric = File(source, "Lyrics/Song.lrc").apply { parentFile.mkdirs(); writeText("[00:00.00]line") }
        val translated = File(source, "Song_trans.lrc").apply { writeText("[00:00.00]trans") }
        val sameNameCover = File(source, "Song.jpg").apply { writeText("jpg") }
        val folderArt = File(source, "folder.png").apply { writeText("png") }
        val coversDirectoryArt = File(source, "Covers/Song.png").apply { parentFile.mkdirs(); writeText("png") }
        File(source, "Other.jpg").writeText("unrelated")

        val plans = buildNearbySidecarCopyPlans(
            sourceFile = audio,
            targetFile = File(target, "Imported.flac"),
            lyricExtensions = listOf("lrc"),
            imageExtensions = listOf("jpg", "png"),
            coverNames = listOf("cover", "folder")
        )

        assertEquals(
            listOf(
                SidecarCopyPlan(lyric, File(target, "Imported.lrc")),
                SidecarCopyPlan(translated, File(target, "Imported_trans.lrc")),
                SidecarCopyPlan(sameNameCover, File(target, "Imported.jpg")),
                SidecarCopyPlan(folderArt, File(target, "Covers/Imported.png")),
                SidecarCopyPlan(coversDirectoryArt, File(target, "Imported.png"))
            ),
            plans
        )
    }

    @Test
    fun `audio without sidecars produces no plans`() {
        val source = temporaryFolder.newFolder("plain")
        val audio = File(source, "Song.flac").apply { writeText("audio") }

        assertTrue(
            buildNearbySidecarCopyPlans(
                sourceFile = audio,
                targetFile = File(temporaryFolder.root, "Imported.flac"),
                lyricExtensions = listOf("lrc", "txt"),
                imageExtensions = listOf("jpg"),
                coverNames = listOf("cover")
            ).isEmpty()
        )
    }

    @Test
    fun `files without a parent directory cannot carry sidecars`() {
        val target = File(temporaryFolder.root, "Imported.flac")

        assertTrue(
            buildNearbySidecarCopyPlans(
                sourceFile = File("Song.flac"),
                targetFile = target,
                lyricExtensions = listOf("lrc"),
                imageExtensions = listOf("jpg"),
                coverNames = listOf("cover")
            ).isEmpty()
        )
        assertTrue(
            buildNearbySidecarCopyPlans(
                sourceFile = File(temporaryFolder.root, "Song.flac"),
                targetFile = File("Imported.flac"),
                lyricExtensions = listOf("lrc"),
                imageExtensions = listOf("jpg"),
                coverNames = listOf("cover")
            ).isEmpty()
        )
    }
}
