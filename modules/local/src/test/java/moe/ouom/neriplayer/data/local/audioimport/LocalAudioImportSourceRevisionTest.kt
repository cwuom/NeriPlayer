package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class LocalAudioImportSourceRevisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://moe.ouom.test.documents/document/song")
    private var sourceBytes = ByteArray(0)

    @Before
    fun bindImportRoot() {
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(temporaryFolder.root).`when`(downloads).defaultRootDirectory(context)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { true })
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(sourceBytes) }
    }

    @Test
    fun `same length source edits are copied again when the provider reports a newer modification time`() {
        val first = importSource("take-1", modifiedAt = FIRST_MODIFIED_AT)
        val second = importSource("take-2", modifiedAt = SECOND_MODIFIED_AT)

        assertEquals(first, second)
        assertEquals("take-2", second.readText())
        assertEquals(SECOND_MODIFIED_AT, second.lastModified())
    }

    @Test
    fun `same length copies are reused when the provider reports the same or no modification time`() {
        importSource("take-1", modifiedAt = FIRST_MODIFIED_AT)

        assertEquals("take-1", importSource("take-2", modifiedAt = FIRST_MODIFIED_AT).readText())
        assertEquals("take-1", importSource("take-3", modifiedAt = null).readText())
        assertEquals("take-1", importSource("take-4", modifiedAt = 0L).readText())
    }

    @Test
    fun `an interrupted replacement backup is not reused for a newer source`() {
        val target = importSource("take-1", modifiedAt = FIRST_MODIFIED_AT)
        val backup = File(
            target.parentFile,
            ".${target.name}.${LocalAudioImportManager.stableKey(uri.toString()).take(8)}.backup"
        )
        check(target.renameTo(backup))

        val restored = importSource("take-2", modifiedAt = SECOND_MODIFIED_AT)

        assertEquals("take-2", restored.readText())
        assertEquals(SECOND_MODIFIED_AT, restored.lastModified())
    }

    private fun importSource(content: String, modifiedAt: Long?): File {
        sourceBytes = content.toByteArray()
        val stabilized = LocalAudioImportManager.stabilizeExternalUri(
            context = context,
            uri = uri,
            copyInfo = ExternalAudioCopyInfo(
                displayName = "Song.flac",
                sizeBytes = sourceBytes.size.toLong(),
                sourceLastModifiedAt = modifiedAt
            )
        )
        return File(requireNotNull(stabilized.uri.path))
    }

    private companion object {
        const val FIRST_MODIFIED_AT = 1_700_000_000_000L
        const val SECOND_MODIFIED_AT = FIRST_MODIFIED_AT + 60_000L
    }
}
