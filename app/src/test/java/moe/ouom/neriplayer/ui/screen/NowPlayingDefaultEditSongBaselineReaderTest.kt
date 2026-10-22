package moe.ouom.neriplayer.ui.screen

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.DefaultEditSongBaselineReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NowPlayingDefaultEditSongBaselineReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var context: Context
    private var previousProcessName: String? = null
    private var snapshotScope: CoroutineScope? = null
    private var previousSnapshotJobs = emptySet<Job>()
    private var previousDatabase: NeriUserDataDatabase? = null
    private val databaseInstance = NeriUserDataDatabase::class.java.getDeclaredField("instance").apply {
        isAccessible = true
    }

    @Before
    fun prepareIsolatedStorage() {
        previousProcessName = application.applicationInfo.processName
        application.applicationInfo.processName = Application.getProcessName()
        val filesDirectory = temporaryFolder.newFolder("files")
        val externalDirectory = temporaryFolder.newFolder("external")
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDirectory
            override fun getExternalFilesDir(type: String?): File = externalDirectory
        }
        previousDatabase = databaseInstance.get(null) as NeriUserDataDatabase?
        val scopeField = ManagedDownloadStorage::class.java.getDeclaredField("snapshotScope").apply {
            isAccessible = true
        }
        val scope = scopeField.get(null) as CoroutineScope
        snapshotScope = scope
        previousSnapshotJobs = checkNotNull(scope.coroutineContext[Job]).children.toSet()
    }

    @After
    fun clearCaches() = runBlocking {
        try {
            val createdJobs = snapshotScope?.coroutineContext?.get(Job)?.children
                ?.filterNot { it in previousSnapshotJobs }?.toList().orEmpty()
            val cacheField = ManagedDownloadStorage::class.java.getDeclaredField("snapshotCacheStore").apply {
                isAccessible = true
            }
            val cache = cacheField.get(null)
            // 不传 context，失效只取消内存快照，不再启动新的 Room 清理任务
            cache.javaClass.getDeclaredMethod("invalidate", Context::class.java).invoke(cache, null)
            withTimeout(5_000L) {
                createdJobs.forEach { it.cancelAndJoin() }
            }
            val database = databaseInstance.get(null) as NeriUserDataDatabase?
            if (database !== previousDatabase) {
                database?.close()
                databaseInstance.set(null, previousDatabase)
            }
        } finally {
            LocalMediaSupport.clearLyricsLookupCache()
            LocalMediaSupport.invalidateSafReadCaches()
            application.applicationInfo.processName = previousProcessName
        }
    }

    @Test
    fun `ordinary local songs read persisted originals rather than current overrides`() = runTest {
        val audio = temporaryFolder.newFile("song.mp3")
        File(audio.path + ".npmeta.json").writeText(
            """{"name":"Song","artist":"Artist","originalName":"Original title",
                "originalArtist":"Original artist","originalCoverUrl":"",
                "originalLyric":"","originalTranslatedLyric":"original translation",
                "originalRomanizedLyric":"original romanization","matchedLyric":"edited lyric"}"""
        )

        val metadata = requireNotNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))

        assertEquals("Original title", metadata.baseline.title)
        assertEquals("Original artist", metadata.baseline.artist)
        assertEquals("", metadata.baseline.coverReference)
        assertEquals("", metadata.baseline.originalLyric)
        assertEquals("original translation", metadata.baseline.translatedLyric)
        assertEquals("original romanization", metadata.baseline.romanizedLyric)
        assertEquals("edited lyric", metadata.overrides.originalLyric)
    }

    @Test
    fun `missing and invalid local metadata leave the restore baseline unavailable`() = runTest {
        val audio = temporaryFolder.newFile("missing.mp3")
        assertNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))

        File(audio.path + ".npmeta.json").writeText("not valid json")
        assertNull(DefaultEditSongBaselineReader.metadata(context, song(audio)))
    }

    @Test
    fun `remote songs do not adopt a local metadata file`() = runTest {
        val remote = song(temporaryFolder.newFile("remote.mp3")).copy(
            mediaUri = "https://music.example/song.mp3", localFilePath = null
        )

        assertNull(DefaultEditSongBaselineReader.metadata(context, remote))
    }

    private fun song(audio: File) = SongItem(
        8L, "Edited title", "Edited artist", "__local_files__", 0L, 60_000L, null,
        mediaUri = audio.toURI().toString(), localFilePath = audio.path, localFileName = audio.name
    )
}
