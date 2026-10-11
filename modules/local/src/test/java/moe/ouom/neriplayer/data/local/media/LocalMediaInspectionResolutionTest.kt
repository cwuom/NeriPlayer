package moe.ouom.neriplayer.data.local.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.Charset
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.AudioTrackTechInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class LocalMediaInspectionResolutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val externalMusic = File(Environment.getExternalStorageDirectory(), "Music")

    @Before
    fun registerProvider() {
        Robolectric.setupContentProvider(FakeAudioProvider::class.java, AUTHORITY)
    }

    @After
    fun resetProvider() {
        FakeAudioProvider.rows = emptyList()
        FakeAudioProvider.failure = null
        externalMusic.deleteRecursively()
    }

    @Test
    fun `text bytes are decoded through a bom, utf8 or the best scoring charset`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01]歌词".toByteArray()

        assertEquals("", LocalMediaSupport.decodeTextBytes(ByteArray(0)))
        assertEquals("[00:01]歌词", LocalMediaSupport.decodeTextBytes(utf8Bom))
        assertEquals("plain words", LocalMediaSupport.decodeTextBytes("plain words".toByteArray()))
        assertEquals("你好世界", LocalMediaSupport.decodeTextBytes("你好世界".toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun `query file paths prefer existing raw paths and then rebuild external storage paths`() {
        val raw = temporaryFolder.newFile("raw.mp3")
        val rebuilt = File(externalMusic, "Song.mp3").apply {
            parentFile!!.mkdirs()
            writeText("audio")
        }

        assertEquals(raw.absolutePath, LocalMediaSupport.resolveQueryFilePath(raw.absolutePath, null, null))
        assertEquals(raw.absolutePath, LocalMediaSupport.resolveQueryFilePath("${raw.absolutePath} (deleted)", null, null))
        assertEquals(rebuilt.absolutePath, LocalMediaSupport.resolveQueryFilePath("/missing/raw.mp3", "Music/", "Song.mp3"))
        assertEquals(rebuilt.absolutePath, LocalMediaSupport.resolveQueryFilePath("relative/raw.mp3", "Music", "Song.mp3"))
    }

    @Test
    fun `query file paths without an existing candidate are unknown`() {
        assertNull(LocalMediaSupport.resolveQueryFilePath(null, null, "Song.mp3"))
        assertNull(LocalMediaSupport.resolveQueryFilePath(null, " ", "Song.mp3"))
        assertNull(LocalMediaSupport.resolveQueryFilePath(null, "Music", " "))
        assertNull(LocalMediaSupport.resolveQueryFilePath(null, "Music", "Missing.mp3"))
    }

    @Test
    fun `direct file uris are described from the file itself`() {
        val file = temporaryFolder.newFile("Song.flac").apply { writeBytes(ByteArray(7)) }

        val info = LocalMediaSupport.queryContentInfo(context, Uri.fromFile(file))

        assertEquals("Song.flac", info.displayName)
        assertEquals(7L, info.sizeBytes)
        assertEquals(file.lastModified(), info.lastModifiedMs)
        assertEquals(file.absolutePath, info.filePath)
        assertNull(info.relativePath)
    }

    @Test
    fun `provider rows are mapped into content info`() {
        val file = File(externalMusic, "Track.mp3").apply {
            parentFile!!.mkdirs()
            writeText("audio")
        }
        FakeAudioProvider.rows = listOf(
            mapOf(
                OpenableColumns.DISPLAY_NAME to "Track.mp3",
                OpenableColumns.SIZE to 42L,
                MediaStore.MediaColumns.MIME_TYPE to "audio/mpeg",
                MediaStore.MediaColumns.DATE_MODIFIED to 1_700L,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/",
                MediaStore.Audio.Media.TITLE to "Title",
                MediaStore.Audio.Media.ARTIST to "Artist",
                MediaStore.Audio.Media.ALBUM to "Album",
                MediaStore.Audio.Media.DURATION to 3_000L
            )
        )

        val info = LocalMediaSupport.queryContentInfo(context, AUDIO_URI)

        assertEquals(
            LocalMediaSupport.QueriedContentInfo(
                displayName = "Track.mp3",
                sizeBytes = 42L,
                mimeType = "audio/mpeg",
                lastModifiedMs = 1_700_000L,
                filePath = file.absolutePath,
                relativePath = "Music/",
                title = "Title",
                artist = "Artist",
                album = "Album",
                durationMs = 3_000L
            ),
            info
        )
    }

    @Test
    fun `empty or failing provider queries fall back to the provider mime type`() {
        val expected = LocalMediaSupport.QueriedContentInfo(null, null, "audio/flac", null, null, null, null, null, null, null)

        assertEquals(expected, LocalMediaSupport.queryContentInfo(context, AUDIO_URI))

        FakeAudioProvider.failure = IllegalStateException("provider unavailable")
        assertEquals(expected, LocalMediaSupport.queryContentInfo(context, AUDIO_URI))
    }

    @Test
    fun `file uris resolve to the playable file and its name`() {
        val file = temporaryFolder.newFile("Song.FLAC")

        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(context, Uri.fromFile(file))

        assertEquals(file.absolutePath, resolved.resolvedPath)
        assertEquals(file, resolved.file)
        assertEquals(Uri.fromFile(file), resolved.playableUri)
        assertEquals("Song.FLAC", resolved.displayName)
        assertEquals("Song", resolved.fallbackTitle)
        assertEquals("FLAC", resolved.fileExtension)
    }

    @Test
    fun `content uris keep the source uri while using the provider file`() {
        val file = File(externalMusic, "Track.mp3").apply {
            parentFile!!.mkdirs()
            writeText("audio")
        }
        FakeAudioProvider.rows = listOf(
            mapOf(
                OpenableColumns.DISPLAY_NAME to "Track.mp3",
                MediaStore.MediaColumns.RELATIVE_PATH to "Music"
            )
        )

        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(context, AUDIO_URI)

        assertEquals(file, resolved.file)
        assertEquals(AUDIO_URI, resolved.playableUri)
        assertEquals("Track.mp3", resolved.displayName)
        assertEquals("mp3", resolved.fileExtension)
    }

    @Test
    fun `content uris without a file fall back to provider and uri names`() {
        FakeAudioProvider.rows = listOf(mapOf(OpenableColumns.DISPLAY_NAME to ".hidden"))

        val hidden = LocalMediaSupport.resolveInspectableLocalMedia(context, AUDIO_URI, allowDescriptorFallback = false)

        assertNull(hidden.file)
        assertEquals(".hidden", hidden.displayName)
        assertEquals(context.getString(CoreCommonR.string.local_files), hidden.fallbackTitle)
        assertEquals("hidden", hidden.fileExtension)

        FakeAudioProvider.rows = emptyList()
        val unnamed = LocalMediaSupport.resolveInspectableLocalMedia(context, AUDIO_URI)

        assertNull(unnamed.resolvedPath)
        assertEquals("7", unnamed.displayName)
        assertNull(unnamed.fileExtension)
    }

    @Test
    fun `quick details take provider values before file and track fallbacks`() {
        val file = File(externalMusic, "Track.mp3").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(11))
        }
        FakeAudioProvider.rows = listOf(
            mapOf(
                OpenableColumns.DISPLAY_NAME to "Track.mp3",
                MediaStore.MediaColumns.RELATIVE_PATH to "Music",
                MediaStore.Audio.Media.DURATION to 0L
            )
        )
        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(context, AUDIO_URI)
        val track = AudioTrackTechInfo("audio/mpeg", 320, 44_100, 2, 5_000L)

        val details = LocalMediaSupport.buildQuickLocalMediaDetails(context, AUDIO_URI, resolved, track)
        val withoutTrack = LocalMediaSupport.buildQuickLocalMediaDetails(context, AUDIO_URI, resolved, null)

        assertEquals(5_000L, details.durationMs)
        assertEquals("audio/mpeg", details.audioMimeType)
        assertEquals(320, details.bitrateKbps)
        assertEquals(44_100, details.sampleRateHz)
        assertEquals(2, details.channelCount)
        assertEquals(11L, details.sizeBytes)
        assertEquals(file.lastModified(), details.lastModifiedMs)
        assertEquals(file.absolutePath, details.filePath)
        assertEquals(0L, withoutTrack.durationMs)
        assertNull(withoutTrack.audioMimeType)
    }

    @Test
    fun `quick details keep a positive provider duration over the track duration`() {
        FakeAudioProvider.rows = listOf(
            mapOf(
                OpenableColumns.DISPLAY_NAME to "Track.mp3",
                MediaStore.Audio.Media.DURATION to 4_000L
            )
        )
        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(context, AUDIO_URI, allowDescriptorFallback = false)
        val track = AudioTrackTechInfo("audio/mpeg", 320, 44_100, 2, 5_000L)

        val details = LocalMediaSupport.buildQuickLocalMediaDetails(context, AUDIO_URI, resolved, track)

        assertEquals(4_000L, details.durationMs)
        assertNull(details.filePath)
    }

    @Test
    fun `tag descriptors fall back from the provider to the local file`() {
        val file = temporaryFolder.newFile("Song.flac")

        LocalMediaSupport.openTagLibDescriptor(context, Uri.fromFile(file), file).use { assertNotNull(it) }
        LocalMediaSupport.openTagLibDescriptor(context, AUDIO_URI, file).use { assertNotNull(it) }
        LocalMediaSupport.openWritableTagLibDescriptor(context, AUDIO_URI, file).use { assertNotNull(it) }
        LocalMediaSupport.openWritableTagLibDescriptor(context, Uri.fromFile(file), file).use { assertNotNull(it) }
    }

    @Test
    fun `tag descriptors are unavailable without a readable source`() {
        assertNull(LocalMediaSupport.openTagLibDescriptor(context, Uri.parse("https://example.com/a.flac"), null))
        assertNull(LocalMediaSupport.openTagLibDescriptor(context, AUDIO_URI, null))
        assertNull(LocalMediaSupport.openTagLibDescriptor(context, Uri.fromFile(File(temporaryFolder.root, "missing.flac")), null))
        assertNull(LocalMediaSupport.openWritableTagLibDescriptor(context, AUDIO_URI, null))
    }

    class FakeAudioProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            failure?.let { throw it }
            val columns = projection ?: emptyArray()
            return MatrixCursor(columns).apply {
                rows.forEach { row -> addRow(columns.map { row[it] }) }
            }
        }

        override fun getType(uri: Uri): String = "audio/flac"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        companion object {
            var rows: List<Map<String, Any?>> = emptyList()
            var failure: RuntimeException? = null
        }
    }

    private companion object {
        const val AUTHORITY = "moe.ouom.neriplayer.test.audio"
        val AUDIO_URI: Uri = Uri.parse("content://$AUTHORITY/audio/7")
    }
}
