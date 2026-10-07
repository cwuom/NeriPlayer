package moe.ouom.neriplayer.data.local.playlist

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalPlaylistFastPreviewFailureTest : LocalPlaylistRepositoryTestSupport() {

    private val storage: LocalPlaylistStorage = mock(LocalPlaylistStorage::class.java)

    @Test
    fun `an unreadable legacy primary yields no preview and logs the cause`() = runTest {
        val repository = repository()
        doAnswer { throw IOException("disk unplugged") }.`when`(storage).readPrimary()

        // Static mocks are thread-local; the preview's own IO hop then stays on this IO thread
        val preview = withContext(Dispatchers.IO) {
            mockStatic(Log::class.java).use { log ->
                repository.readFastPlaylist(1L).also {
                    log.verify {
                        Log.w(anyString(), eq("Fast legacy playlist preview unavailable: disk unplugged"), isNull())
                    }
                }
            }
        }

        assertNull(preview)
    }

    @Test
    fun `a missing legacy primary yields no preview`() = runTest {
        assertNull(repository().readFastPlaylist(1L))
    }

    private fun repository() = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "fast_preview.json"),
        storage = storage
    )
}
