package moe.ouom.neriplayer.data.sync.cover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomCoverUrlMappingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val persistence = RecordingPersistence()
    private val cleanup = mutableListOf<String>()
    private val failures = mutableListOf<Throwable>()

    private fun store(file: File) = RoomCoverUrlMappingStore(persistence, LegacyCoverMappingReader(file), cleanup::add, failures::add)

    @Test
    fun `room primary avoids corrupt legacy file and schedules cleanup`() {
        persistence.primary = mapOf("local" to "remote")
        val file = temporary.newFile().apply { writeText("broken") }
        assertEquals(persistence.primary, store(file).load())
        assertEquals(listOf("cover-url-mapping-room-load"), cleanup)
        assertTrue(failures.isEmpty())
        assertTrue(persistence.imports.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `missing file does not promote empty legacy state`() {
        assertTrue(store(File(temporary.root, "missing.json")).load().isEmpty())
        assertTrue(persistence.imports.isEmpty())
        assertTrue(cleanup.isEmpty())
    }

    @Test
    fun `valid legacy import drops invalid entries and promotes before cleanup`() {
        val file = temporary.newFile().apply { writeText("""{"local":"remote"," ":"remote","blank":" ","null":null}""") }
        val store = store(file)
        assertEquals(mapOf("local" to "remote"), store.load())
        assertEquals(listOf(mapOf("local" to "remote")), persistence.imports)
        assertEquals(listOf("cover-url-mapping-import"), cleanup)
        store.save("new", "network")
        store.delete(listOf("local"))
        assertEquals(listOf(true, true, true), persistence.cleanupEligibility)
        assertTrue(file.exists())
    }

    @Test
    fun `null legacy document preserves empty promotion compatibility`() {
        val file = temporary.newFile().apply { writeText("null") }
        assertTrue(store(file).load().isEmpty())
        assertEquals(listOf(emptyMap<String, String>()), persistence.imports)
    }

    @Test
    fun `corrupt legacy import blocks cleanup during subsequent mutations`() {
        val file = temporary.newFile().apply { writeText("broken") }
        val store = store(file)
        assertTrue(store.load().isEmpty())
        store.save("new", "remote")
        store.delete(listOf("old"))
        assertEquals(listOf(false, false), persistence.cleanupEligibility)
        assertEquals(1, failures.size)
        assertTrue(cleanup.isEmpty())
        assertTrue(persistence.imports.isEmpty())
        assertTrue(file.exists())
        file.writeText("""{"old":"remote"}""")
        store.load()
        assertTrue(persistence.cleanupEligibility.last())
        assertEquals(listOf("cover-url-mapping-import"), cleanup)
    }

    @Test
    fun `failed room import never acknowledges cleanup`() {
        persistence.failImport = true
        val file = temporary.newFile().apply { writeText("{}") }
        assertThrows(IllegalStateException::class.java) { store(file).load() }
        assertTrue(cleanup.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `memory store snapshots do not mutate backing state`() {
        val store = InMemoryCoverUrlMappingStore(mapOf("a" to "b"))
        val before = store.load()
        store.save("c", "d")
        store.delete(listOf("a"))
        assertEquals(mapOf("a" to "b"), before)
        assertEquals(mapOf("c" to "d"), store.load())
        assertFalse(store.load().containsKey("a"))
    }

    private class RecordingPersistence : CoverMappingPersistence {
        var primary: Map<String, String>? = null
        var failImport = false
        val imports = mutableListOf<Map<String, String>>()
        val cleanupEligibility = mutableListOf<Boolean>()
        override suspend fun readIfRoomPrimary() = primary
        override suspend fun importLegacyAndPromote(mappings: Map<String, String>, cleanupEligible: Boolean) {
            if (failImport) error("write failed")
            imports += mappings
            cleanupEligibility += cleanupEligible
        }
        override suspend fun upsert(localUrl: String, networkUrl: String, cleanupEligible: Boolean) { cleanupEligibility += cleanupEligible }
        override suspend fun delete(localUrls: Collection<String>, cleanupEligible: Boolean) { cleanupEligibility += cleanupEligible }
    }
}
