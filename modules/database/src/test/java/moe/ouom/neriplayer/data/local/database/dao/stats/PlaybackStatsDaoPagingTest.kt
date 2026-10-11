package moe.ouom.neriplayer.data.local.database.dao.stats

import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlaybackStatsDaoPagingTest {
    private val queries = mutableListOf<String>()

    /** Runs the DAO's own paging defaults and records which generated query they pick. */
    private fun <T> recordingDao(type: Class<T>): T = mock(type) { invocation ->
        if (!Modifier.isAbstract(invocation.method.modifiers)) return@mock invocation.callRealMethod()
        queries += invocation.method.name + invocation.arguments.filterNot { it is Continuation<*> }
        emptyList<Any>()
    }

    @Test fun `primary bucket pages start at the first page and continue after the last key`() = runTest {
        val dao = recordingDao(PlaybackStatsDao::class.java)

        dao.bucketPage(day = null, identity = null, limit = 10)
        dao.bucketPage(day = 5L, identity = "a", limit = 10)
        dao.bucketIdentityPage(identity = null, day = null, limit = 3)
        dao.bucketIdentityPage(identity = "b", day = 7L, limit = 3)

        assertEquals(
            listOf("firstBucketPage[10]", "nextBucketPage[5, a, 10]", "firstBucketIdentityPage[3]", "nextBucketIdentityPage[b, 7, 3]"),
            queries
        )
    }

    @Test fun `snapshot bucket pages start at the first page and continue after the last key`() = runTest {
        val dao = recordingDao(PlaybackStatsSnapshotDao::class.java)

        dao.bucketPage("s", afterDay = null, afterIdentity = null, limit = 10)
        dao.bucketPage("s", afterDay = 5L, afterIdentity = "a", limit = 10)
        dao.bucketIdentityPage("s", afterIdentity = null, afterDay = null, limit = 3)
        dao.bucketIdentityPage("s", afterIdentity = "b", afterDay = 7L, limit = 3)

        assertEquals(
            listOf("firstBucketPage[s, 10]", "nextBucketPage[s, 5, a, 10]", "firstBucketIdentityPage[s, 3]", "nextBucketIdentityPage[s, b, 7, 3]"),
            queries
        )
    }

    @Test fun `a continued page needs both halves of its key`() = runTest {
        val primary = recordingDao(PlaybackStatsDao::class.java)
        val snapshot = recordingDao(PlaybackStatsSnapshotDao::class.java)

        assertTrue(runCatching { primary.bucketPage(5L, null, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { primary.bucketIdentityPage("a", null, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { snapshot.bucketPage("s", 5L, null, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { snapshot.bucketIdentityPage("s", "a", null, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), queries)
    }
}
