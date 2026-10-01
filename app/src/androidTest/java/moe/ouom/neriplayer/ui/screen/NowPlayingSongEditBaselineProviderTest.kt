package moe.ouom.neriplayer.ui.screen

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadMigrationTestDocumentProvider
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongBaseline
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.NowPlayingSongEditOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.NowPlayingSongEditPlaybackPort
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.SongEditLyricsWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingSongEditBaselineProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )

    @Test
    fun nullRootCursorKeepsCurrentEditorBaselineEditable() = runBlocking {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            val previousDirectory = ManagedDownloadStorage.configuredDirectoryUri()
            val ownerJob = SupervisorJob()
            val ownerScope = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
            var owner: NowPlayingSongEditOwner? = null
            try {
                call(ManagedDownloadMigrationTestDocumentProvider.RESET)
                val audioUri = requireNotNull(DocumentsContract.createDocument(
                    context.contentResolver, rootUri, "audio/mpeg", "editor-song.mp3"
                ))
                val song = SongItem(
                    id = 461L,
                    name = "Current title",
                    artist = "Current artist",
                    album = "Album",
                    albumId = 0L,
                    durationMs = 60_000L,
                    coverUrl = "current-cover",
                    mediaUri = audioUri.toString()
                )
                val baseline = EditSongBaseline(
                    title = "Original title",
                    artist = "Original artist",
                    coverUrl = "original-cover",
                    lyric = "[00:00.10]original lyric",
                    translatedLyric = null,
                    romanizedLyric = null
                )
                // 仅切换 URI，保留现有目录 label 和文件名模板
                ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
                call(
                    ManagedDownloadMigrationTestDocumentProvider.REFERENCE_QUERY_FAULT,
                    rootUri.toString(),
                    Bundle().apply { putString("fault", "null") }
                )

                val failure = runCatching {
                    GlobalDownloadManager.readManagedRestorableMetadata(context, song)
                }.exceptionOrNull()
                assertTrue(failure.toString(), failure is ManagedDownloadRootProviderException)
                assertTrue(failure?.cause is IllegalStateException)
                assertTrue(failure?.cause?.message.orEmpty().startsWith(
                    "provider returned null document cursor"
                ))
                val queriesBeforeEditor = requireNotNull(call(
                    ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT
                )).getInt("referenceQueryFaults")
                assertTrue("the root fault must reach the real Provider", queriesBeforeEditor > 0)

                withContext(Dispatchers.Main) {
                    val editor = NowPlayingSongEditOwner(
                        initialSong = song,
                        initialCoverUrl = "current-cover",
                        initialBaseline = baseline,
                        scope = ownerScope,
                        onSavingChanged = {},
                        playbackPort = object : NowPlayingSongEditPlaybackPort {
                            override fun currentSong(): SongItem? =
                                error("baseline loading must not access playback")

                            override suspend fun saveLyrics(write: SongEditLyricsWrite): Boolean =
                                error("baseline loading must not write lyrics")
                        }
                    )
                    owner = editor
                    editor.loadManagedBaseline(context, song)

                    assertSame(baseline, editor.editBaselineState.value)
                    assertEquals("Current title", editor.songNameState.value)
                    assertEquals("Current artist", editor.artistNameState.value)
                    assertEquals("current-cover", editor.coverUrlState.value)
                    assertTrue(editor.canEditFields())
                    editor.updateTitle("Edited title")
                    assertEquals("Edited title", editor.songNameState.value)
                }
                val queriesAfterEditor = requireNotNull(call(
                    ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT
                )).getInt("referenceQueryFaults")
                assertTrue("the editor must exercise the same Provider fault",
                    queriesAfterEditor > queriesBeforeEditor)
            } finally {
                withContext(NonCancellable) {
                    try {
                        try {
                            withContext(Dispatchers.Main) { owner?.dispose() }
                        } finally {
                            ownerJob.cancelAndJoin()
                        }
                    } finally {
                        try {
                            call(ManagedDownloadMigrationTestDocumentProvider.RESET)
                        } finally {
                            ManagedDownloadStorage.updateCustomDirectoryUri(previousDirectory)
                        }
                    }
                }
            }
        }
    }

    private fun call(method: String, argument: String? = null, extras: Bundle? = null) =
        context.contentResolver.call(treeUri, method, argument, extras)
}
