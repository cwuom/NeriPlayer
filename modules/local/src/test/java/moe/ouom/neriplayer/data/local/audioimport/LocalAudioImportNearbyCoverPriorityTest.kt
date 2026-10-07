package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioImportNearbyCoverPriorityTest {
    private val lookups = mapOf<String, (Map<String, String>, Map<String, String>, Map<String, String>) -> String?>(
        "document" to { direct, nested, root ->
            LocalAudioImportManager.findNearbyDocumentCoverReference(direct, nested, root, "Night Drive")
        },
        "saf" to { direct, nested, root ->
            LocalAudioImportManager.findNearbySafCoverReference(direct, nested, root, "Night Drive")
        }
    )

    @Test
    fun `song named covers are taken from the closest index first`() {
        lookups.forEach { (name, lookup) ->
            assertEquals(
                name,
                "direct-song",
                lookup(
                    mapOf("night drive.png" to "direct-song"),
                    mapOf("night drive.jpg" to "nested-song"),
                    mapOf("night drive.jpg" to "root-song")
                )
            )
            assertEquals(
                name,
                "nested-song",
                lookup(
                    mapOf("cover.jpg" to "direct-cover"),
                    mapOf("night drive.webp" to "nested-song"),
                    mapOf("night drive.jpg" to "root-song")
                )
            )
            assertEquals(
                name,
                "root-song",
                lookup(
                    mapOf("folder.png" to "direct-folder"),
                    emptyMap(),
                    mapOf("night drive.jpeg" to "root-song")
                )
            )
        }
    }

    @Test
    fun `generic covers follow name and extension priority before index proximity`() {
        lookups.forEach { (name, lookup) ->
            assertEquals(
                name,
                "root-cover",
                lookup(
                    mapOf("front.jpg" to "direct-front"),
                    mapOf("folder.webp" to "nested-folder"),
                    mapOf("cover.png" to "root-cover")
                )
            )
            assertEquals(
                name,
                "root-jpg",
                lookup(
                    mapOf("cover.png" to "direct-png"),
                    emptyMap(),
                    mapOf("cover.jpg" to "root-jpg")
                )
            )
            assertEquals(
                name,
                "direct-folder",
                lookup(
                    mapOf("folder.jpg" to "direct-folder"),
                    mapOf("folder.jpg" to "nested-folder"),
                    mapOf("folder.jpg" to "root-folder")
                )
            )
        }
    }

    @Test
    fun `unsupported images and other songs never become the cover`() {
        lookups.forEach { (name, lookup) ->
            assertNull(
                name,
                lookup(
                    mapOf("night drive.gif" to "gif", "back.jpg" to "back"),
                    mapOf("other song.jpg" to "other"),
                    emptyMap()
                )
            )
        }
    }
}
