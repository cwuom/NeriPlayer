package moe.ouom.neriplayer.core.player.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerManagerCustomMetadataNormalizationTest {

    @Test
    fun `restore original title still needs custom value when base title was replaced`() {
        val baseName = "搜索匹配后的标题"
        val originalName = "原始标题"
        val normalized = normalizeCustomMetadataValue(
            desiredValue = originalName,
            baseValue = baseName
        )

        assertEquals(originalName, normalized)
    }

    @Test
    fun `matching base title clears custom value`() {
        val normalized = normalizeCustomMetadataValue(
            desiredValue = "当前标题",
            baseValue = "当前标题"
        )

        assertNull(normalized)
    }
}
