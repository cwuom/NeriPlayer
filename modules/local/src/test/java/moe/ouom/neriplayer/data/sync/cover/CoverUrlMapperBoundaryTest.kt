package moe.ouom.neriplayer.data.sync.cover

import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverUrlMapperBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only nonblank local source mappings are saved and network lookup remains compatible`() {
        val mapper = CoverUrlMapper.createForTest()
        for (value in listOf(null, "", " ")) {
            mapper.saveCoverMapping(value, "https://cover.test/a")
            mapper.saveCoverMapping("content://covers/1", value)
            assertEquals(value, mapper.getNetworkUrl(value))
            assertNull(mapper.getSyncableNetworkUrl(value))
        }
        mapper.saveCoverMapping("https://cover.test/a", "https://cover.test/b")
        assertEquals("https://cover.test/a", mapper.getNetworkUrl("https://cover.test/a"))
        assertEquals("content://covers/1", mapper.getNetworkUrl("content://covers/1"))
        mapper.saveCoverMapping("content://covers/1", " https://cover.test/b ")
        assertEquals("https://cover.test/b", mapper.getSyncableNetworkUrl(" content://covers/1 "))
        assertEquals("https://cover.test/c", mapper.getSyncableNetworkUrl(" https://cover.test/c "))
        mapper.saveCoverMapping("content://covers/2", "file:/private/cover")
        assertNull(mapper.getSyncableNetworkUrl("content://covers/2"))
        assertNull(mapper.getSyncableNetworkUrl("content://covers/missing"))
        for (url in listOf("legacy/data/cover", "legacy/storage/cover")) {
            mapper.saveCoverMapping(url, "https://cover.test/legacy")
            assertEquals("https://cover.test/legacy", mapper.getNetworkUrl(url))
        }
    }

    @Test
    fun `cleanup removes missing files but retains provider references and existing files`() {
        val present = temporary.newFile("cover.jpg").absolutePath
        val missing = temporary.root.resolve("missing.jpg").absolutePath
        val missingUri = temporary.root.resolve("missing-uri.jpg").toURI().toString()
        val retained = listOf(present, "content://covers/1", "file:invalid path")
        val mapper = CoverUrlMapper.createForTest((retained + missing + missingUri).associateWith { "https://cover.test/a" })
        mapper.cleanupInvalidMappings()
        for (url in retained) assertEquals("https://cover.test/a", mapper.getNetworkUrl(url))
        for (url in listOf(missing, missingUri)) assertEquals(url, mapper.getNetworkUrl(url))
        mapper.cleanupInvalidMappings()
    }
}
