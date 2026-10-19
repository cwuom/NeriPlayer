package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.IdentityHashMap
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic

class LocalMediaLyricSidecarMutationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @After
    fun clearCaches() {
        LocalMediaSupport.clearLyricsLookupCache()
        LocalMediaMetadataRecoveryStore.resetRecoveryForTest()
    }

    @Test
    fun `missing empty lyric variants create no sidecars`() {
        val audio = audio()

        assertTrue(write(audio, original = "[00:01.00]original", translated = "", romanized = "  \n"))

        assertEquals("[00:01.00]original", File(audio.parentFile, "song.lrc").readText())
        assertFalse(File(audio.parentFile, "song_trans.lrc").exists())
        assertFalse(File(audio.parentFile, "song_roma.lrc").exists())
    }

    @Test
    fun `empty variants still clear existing lyrics`() {
        val audio = audio()
        val translated = File(audio.parentFile, "song_trans.lrc").apply { writeText("old translation") }

        assertTrue(write(audio, translated = ""))

        assertTrue(translated.isFile)
        assertEquals("", translated.readText())
    }

    @Test
    fun `null variants leave existing lyrics unchanged`() {
        val audio = audio()
        val original = File(audio.parentFile, "song.lrc").apply { writeText("old original") }

        assertTrue(write(audio))

        assertEquals("old original", original.readText())
    }

    @Test
    fun `existing adjacent lyrics are reused when a Lyrics folder also exists`() {
        val audio = audio()
        val original = File(audio.parentFile, "song.lrc").apply { writeText("old original") }
        val lyrics = File(audio.parentFile, "Lyrics").apply { check(mkdir()) }

        repeat(2) { assertTrue(write(audio, original = "new original")) }

        assertEquals("new original", original.readText())
        assertEquals(emptyList<String>(), lyrics.list()!!.toList())
    }

    @Test
    fun `rollback restores a reused adjacent lyric and keeps its ownership`() {
        val audio = audio()
        val original = File(audio.parentFile, "song.lrc").apply { writeText("old original") }
        val lyrics = File(audio.parentFile, "Lyrics").apply { check(mkdir()) }
        doReturn(temporaryFolder.newFolder("no-backup")).`when`(context).noBackupFilesDir
        val transaction = LocalMediaCompanionTransaction(context, audio.absolutePath)

        withFileDescriptors {
            assertTrue(write(audio, original = "new original", transaction = transaction))
            assertFalse(requireNotNull(transaction.record).companions.single().createdByTransaction)
            assertTrue(transaction.rollback())
        }

        assertEquals("old original", original.readText())
        assertEquals(emptyList<String>(), lyrics.list()!!.toList())
    }

    private fun audio(): File = temporaryFolder.newFile("song.flac").apply { writeText("audio") }

    private fun write(
        audio: File,
        original: String? = null,
        translated: String? = null,
        romanized: String? = null,
        transaction: LocalMediaCompanionTransaction? = null
    ): Boolean {
        val uri = mock(Uri::class.java).also { doReturn("file").`when`(it).scheme }
        return LocalMediaSupport.writeLocalLyricsSidecars(
            context = context,
            sourceUri = uri,
            file = audio,
            displayName = audio.name,
            song = SongItem(
                id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
                durationMs = 1000L, coverUrl = null, matchedLyric = original,
                matchedTranslatedLyric = translated, matchedRomanizedLyric = romanized
            ),
            companionTransaction = transaction
        )
    }

    private fun <T> withFileDescriptors(block: () -> T): T {
        val files = IdentityHashMap<ParcelFileDescriptor, File>()
        val stat = mock(StructStat::class.java)
        return mockStatic(ParcelFileDescriptor::class.java).use { descriptors ->
            descriptors.`when`<ParcelFileDescriptor> {
                ParcelFileDescriptor.open(any(File::class.java), eq(ParcelFileDescriptor.MODE_READ_ONLY))
            }.thenAnswer { invocation ->
                mock(ParcelFileDescriptor::class.java).also { descriptor ->
                    doReturn(descriptor).`when`(descriptor).dup()
                    files[descriptor] = invocation.getArgument(0)
                }
            }
            mockStatic(Os::class.java).use { os ->
                os.`when`<StructStat> { Os.stat(anyString()) }.thenReturn(stat)
                os.`when`<StructStat> { Os.fstat(any()) }.thenReturn(stat)
                mockStatic(OsConstants::class.java).use { constants ->
                    constants.`when`<Boolean> { OsConstants.S_ISREG(anyInt()) }.thenReturn(true)
                    mockConstruction(ParcelFileDescriptor.AutoCloseInputStream::class.java) { stream, construction ->
                        val descriptor = construction.arguments()[0] as ParcelFileDescriptor
                        val source = ByteArrayInputStream(files.getValue(descriptor).readBytes())
                        doAnswer { source.read() }.`when`(stream).read()
                        doAnswer { source.read(it.getArgument<ByteArray>(0)) }.`when`(stream).read(any())
                        doAnswer { source.read(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
                            .`when`(stream).read(any(), anyInt(), anyInt())
                        doAnswer { source.available() }.`when`(stream).available()
                    }.use { block() }
                }
            }
        }
    }
}
