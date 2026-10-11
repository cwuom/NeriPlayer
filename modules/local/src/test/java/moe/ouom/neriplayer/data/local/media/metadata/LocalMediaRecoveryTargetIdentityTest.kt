package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaRecoveryTargetIdentityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `file targets are identified by their canonical path`() {
        val album = temporaryFolder.newFolder("album")
        val canonical = "file:${File(album, "song.flac").canonicalPath}"

        assertEquals(canonical, LocalMediaMetadataRecoveryStore.targetIdentity("${album.path}/../album/song.flac"))
        assertEquals(canonical, LocalMediaMetadataRecoveryStore.targetIdentity("FILE://${album.path}/./song.flac"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("file:relative/song.flac"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("file://host"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("file:///music/a b.flac"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity(" "))
    }

    @Test
    fun `content targets prefer document ids and fall back to the normalized uri`() {
        val document = DocumentsContract.buildDocumentUri("com.example.docs", "primary:Music/song.flac")

        assertEquals(
            "document:16:com.example.docs:primary:Music/song.flac",
            LocalMediaMetadataRecoveryStore.targetIdentity(document.toString())
        )
        assertEquals(
            "uri:content://media/external/audio/media/7",
            LocalMediaMetadataRecoveryStore.targetIdentity("CONTENT://media/external/audio/media/7")
        )
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("content:///external/audio/media/7"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("content:/external/audio/media/7"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("https://example.com/song.flac"))
        assertNull(LocalMediaMetadataRecoveryStore.targetIdentity("relative/song.flac"))
    }

    @Test
    fun `file uri targets are replaced in place`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val target = File(temporaryFolder.newFolder("music"), "song.flac").apply { writeText("original") }

        assertTrue(
            LocalMediaMetadataRecoveryStore.replaceTargetFromFile(context, Uri.fromFile(target).toString(), source)
        )

        assertEquals("tagged audio", target.readText())
    }
}
