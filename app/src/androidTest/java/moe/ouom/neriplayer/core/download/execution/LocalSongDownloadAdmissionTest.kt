package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.batch.startBatchDownload
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSongDownloadAdmissionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun allLocalSourcesAreRejectedBeforeCreatingBatchStartup() = runBlocking {
        val localSongs = listOf(
            song(1).copy(mediaUri = "content://test.documents/document/local"),
            song(2).copy(localFilePath = "/synthetic/local.flac")
        )
        val beforePresentationId = GlobalDownloadManager.batchDownloadPresentationIdGenerator.get()
        val startupJob = GlobalDownloadManager.startBatchDownload(
            context = context,
            songs = localSongs,
            skipTrafficRiskPrompt = true,
            // 让旧实现即使创建了启动协程也不能写入下载队列
            requestedAdmissionTicket = Long.MIN_VALUE,
            awaitAdmissionWhenUnavailable = false
        )
        try {
            assertNull("local sources must not create a download startup job", startupJob)
            assertEquals(
                "local sources must not allocate a batch presentation",
                beforePresentationId,
                GlobalDownloadManager.batchDownloadPresentationIdGenerator.get()
            )
        } finally {
            startupJob?.cancelAndJoin()
        }
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "local-song-$id",
        artist = "synthetic artist",
        album = "user edited album",
        albumId = 42L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
