package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class LocalMediaCompanionInterruptedRollbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val stat = mock(StructStat::class.java)
    private lateinit var context: Context
    private lateinit var lyric: File
    private val staging: File get() = LocalMediaMetadataRecoveryStore.stagingDirectory(context)

    @Before
    fun setUp() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
        context = mock(Context::class.java)
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        lyric = File(temporaryFolder.newFolder("music"), "song.lrc").apply {
            writeText("original words")
            setLastModified(MODIFIED_AT)
        }
    }

    @After
    fun tearDown() {
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `rollback restores a lyric whose rewrite stopped part way`() {
        val transaction = transaction()

        val restored = withFileDescriptors {
            transaction.beforeWrite(lyric.absolutePath, "updated words".toByteArray())
            lyric.writeText("upd")
            transaction.rollback()
        }

        assertTrue(restored)
        assertNull(transaction.record)
        assertEquals("original words", lyric.readText())
        assertEquals(MODIFIED_AT, lyric.lastModified())
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `rollback keeps a lyric that another writer replaced`() {
        val transaction = transaction()

        val restored = withFileDescriptors {
            transaction.beforeWrite(lyric.absolutePath, "updated words".toByteArray())
            lyric.writeText("lyrics rewritten by another app")
            transaction.rollback()
        }

        assertFalse(restored)
        assertEquals("lyrics rewritten by another app", lyric.readText())
        val record = requireNotNull(transaction.record)
        assertEquals("WRITE_INTENT", record.companions.single().phase)
        assertEquals("ROLLBACK_FAILED", JSONObject(record.journalFile.readText()).getString("stage"))
    }

    private fun transaction() = LocalMediaCompanionTransaction(context, File(lyric.parentFile, "song.flac").absolutePath)

    private fun <T> withFileDescriptors(block: () -> T): T {
        val descriptor = mock(ParcelFileDescriptor::class.java).also { doReturn(it).`when`(it).dup() }
        return mockStatic(ParcelFileDescriptor::class.java).use { descriptors ->
            descriptors.`when`<ParcelFileDescriptor> {
                ParcelFileDescriptor.open(lyric, ParcelFileDescriptor.MODE_READ_ONLY)
            }.thenReturn(descriptor)
            mockStatic(Os::class.java).use { os ->
                os.`when`<StructStat> { Os.stat(anyString()) }.thenReturn(stat)
                os.`when`<StructStat> { Os.fstat(any()) }.thenReturn(stat)
                mockStatic(OsConstants::class.java).use { constants ->
                    constants.`when`<Boolean> { OsConstants.S_ISREG(anyInt()) }.thenReturn(true)
                    mockConstruction(ParcelFileDescriptor.AutoCloseInputStream::class.java) { stream, _ ->
                        serve(stream, lyric.readBytes())
                    }.use { block() }
                }
            }
        }
    }

    private fun serve(stream: InputStream, bytes: ByteArray) {
        val source = ByteArrayInputStream(bytes)
        doAnswer { source.read() }.`when`(stream).read()
        doAnswer { source.read(it.getArgument<ByteArray>(0)) }.`when`(stream).read(any())
        doAnswer { source.read(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
            .`when`(stream).read(any(), anyInt(), anyInt())
        doAnswer { source.available() }.`when`(stream).available()
    }

    private companion object {
        const val MODIFIED_AT = 1_700_000_000_000L
    }
}
