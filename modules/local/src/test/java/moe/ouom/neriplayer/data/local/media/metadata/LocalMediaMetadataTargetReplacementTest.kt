package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import java.io.File
import java.io.FileNotFoundException
import org.mockito.Mockito.never
import org.mockito.Mockito.clearInvocations

class LocalMediaMetadataTargetReplacementTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = mock(Context::class.java)

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `direct targets are replaced and preserve the original modification time`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val target = File(temporaryFolder.newFolder("albums"), "song.flac").apply { writeText("original audio") }

        assertTrue(
            LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                context, target.absolutePath, source, record(target.absolutePath, source), lastModifiedMs = MODIFIED_AT
            )
        )

        assertEquals("tagged audio", target.readText())
        assertEquals(MODIFIED_AT, target.lastModified())
        assertEquals(listOf("song.flac"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `direct replacement reports blocked parents, occupied targets and unusable sources`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val blocker = temporaryFolder.newFile("blocker").apply { writeText("plain file") }
        val album = temporaryFolder.newFolder("album")
        val occupied = File(album, "song.flac").apply { mkdir() }
        File(occupied, "keep").writeText("unrelated")

        assertFalse(replace(File(blocker, "nested/song.flac"), source))
        assertFalse(replace(occupied, source))
        assertFalse(replace(File(album, "other.flac"), File(temporaryFolder.root, "missing.flac")))
        assertFalse(replace(File(album, "other.flac"), temporaryFolder.newFile("empty.flac")))

        assertEquals("plain file", blocker.readText())
        assertEquals(listOf("song.flac"), album.list()!!.toList())
        assertEquals("unrelated", File(occupied, "keep").readText())
    }

    @Test
    fun `content providers requiring a truncating open cannot replace the target`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val uri = contentUri()
        val resolver = mock(ContentResolver::class.java)
        val descriptor = mock(ParcelFileDescriptor::class.java)
        doReturn(resolver).`when`(context).contentResolver
        doThrow(FileNotFoundException("rw unsupported")).`when`(resolver).openFileDescriptor(uri, "rw")
        doReturn(descriptor).`when`(resolver).openFileDescriptor(uri, "rwt")

        withParsedUri(uri) {
            val prepared = record(CONTENT_REFERENCE, source)
            clearInvocations(resolver)
            assertFalse(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                context, CONTENT_REFERENCE, source, prepared
            ))
        }

        verify(resolver).openFileDescriptor(uri, "rw")
        verify(resolver, never()).openFileDescriptor(uri, "rwt")
        verify(resolver, never()).openFileDescriptor(uri, "wt")
    }

    @Test
    fun `content targets without a writable descriptor are not replaced`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val uri = contentUri()
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver

        withParsedUri(uri) {
            val prepared = record(CONTENT_REFERENCE, source)
            clearInvocations(resolver)
            assertFalse(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                context, CONTENT_REFERENCE, source, prepared
            ))
        }

        verify(resolver).openFileDescriptor(uri, "rw")
        verify(resolver, never()).openFileDescriptor(uri, "rwt")
        verify(resolver, never()).openFileDescriptor(uri, "wt")
    }

    private fun replace(target: File, source: File): Boolean =
        LocalMediaMetadataRecoveryStore.replaceTargetFromFile(context, target.absolutePath, source, record(target.absolutePath, source))

    private fun record(reference: String, source: File): LocalMetadataRecoveryRecord {
        val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val backup = File.createTempFile("source-", ".flac", staging).apply {
            val target = File(reference)
            writeText(if (target.isFile) target.readText() else "original audio")
        }
        val updated = File.createTempFile("updated-", ".flac", staging).apply {
            writeText(if (source.isFile && source.length() > 0L) source.readText() else "updated audio")
        }
        return LocalMediaMetadataRecoveryStore.begin(context, reference, backup, updated, null)
    }

    private fun contentUri(): Uri = mock(Uri::class.java).also { uri ->
        doReturn("content").`when`(uri).scheme
        doReturn("/external/audio/media/7").`when`(uri).path
    }

    private fun withParsedUri(uri: Uri, block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(CONTENT_REFERENCE) }.thenReturn(uri)
            block()
        }
    }

    private companion object {
        const val CONTENT_REFERENCE = "content://media/external/audio/media/7"
        const val MODIFIED_AT = 1_700_000_000_000L
    }
}
