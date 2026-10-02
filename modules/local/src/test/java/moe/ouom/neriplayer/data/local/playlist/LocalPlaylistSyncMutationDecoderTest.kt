package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.io.IOException
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPlaylistSyncMutationDecoderTest {
    private val gson = Gson()
    private val digest = "a".repeat(64)
    private val mutation = LocalPlaylistSyncMutation(
        expectedPrimaryDigest = digest,
        addedSongDeletions = listOf(SyncPlaylistSongDeletion(
            playlistId = 7, songId = 9, album = "provider", mediaUri = "content://media/9",
            deletedAt = 20, deviceId = "A", removedMembershipTokens = listOf(SyncCausalToken("A", 3))
        )),
        removedSongDeletions = listOf(PlaylistSongDeletionRemoval(8, listOf(SongIdentity(10, "local", null)))),
        deletedPlaylistIds = listOf(11), clearedPlaylistDeletionIds = listOf(12), restoredPlaylistIds = listOf(13)
    )

    @Test fun `durable mutation and wrapped chain preserve every deletion field`() {
        assertEquals(mutation, decodeLocalPlaylistSyncMutation(gson.toJson(mutation)))
        val chain = LocalPlaylistSyncMutationOutbox(listOf(mutation, mutation.copy(deletedPlaylistIds = listOf(14))))
        assertEquals(chain, decodeLocalPlaylistSyncMutationOutbox(gson.toJson(chain)))
        assertEquals(LocalPlaylistSyncMutationOutbox(listOf(mutation)), decodeLocalPlaylistSyncMutationOutbox(gson.toJson(mutation)))
        assertEquals(LocalPlaylistSyncMutationOutbox(), decodeLocalPlaylistSyncMutationOutbox("{\"mutations\":[]}"))
    }

    @Test fun `older single mutation without restoration or token proof remains readable`() {
        val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
        fields.remove("restoredPlaylistIds")
        fields.getAsJsonArray("addedSongDeletions").single().asJsonObject.remove("removedMembershipTokens")

        val decoded = decodeLocalPlaylistSyncMutationOutbox(fields.toString()).mutations.single()

        assertEquals(listOf(11L), decoded.deletedPlaylistIds)
        assertEquals(listOf(12L), decoded.clearedPlaylistDeletionIds)
        assertEquals(emptyList<Long>(), decoded.restoredPlaylistIds)
        assertEquals(emptyList<SyncCausalToken>(), decoded.addedSongDeletions.single().removedMembershipTokens)
        assertEquals(SongIdentity(10, "local", null), decoded.removedSongDeletions.single().identities.single())
    }

    @Test fun `nullable media URI and unknown extension do not reject a valid old mutation`() {
        val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
        fields.addProperty("futureExtension", true)
        fields.getAsJsonArray("addedSongDeletions").single().asJsonObject.add("mediaUri", JsonNull.INSTANCE)
        assertEquals(null, decodeLocalPlaylistSyncMutation(fields.toString()).addedSongDeletions.single().mediaUri)
    }

    @Test fun `unreadable documents cannot turn into an empty acknowledged chain`() {
        val valid = gson.toJson(mutation)
        listOf("null", "[]", "{", "$valid {}", "{\"mutations\":null}", "{\"mutations\":{}}", "{\"mutations\":[null]}", "{\"mutations\":[[]]}").forEach {
            assertThrows(it, IOException::class.java) { decodeLocalPlaylistSyncMutationOutbox(it) }
        }
    }

    @Test fun `missing null or invalid commit digest cannot confirm a mutation`() {
        listOf(null, "null", "\"\"", "\"legacy\"", "3", "[]").forEach { replacement ->
            assertInvalidField("expectedPrimaryDigest", replacement)
        }
    }

    @Test fun `missing required arrays and explicit null optional arrays are rejected`() {
        listOf("addedSongDeletions", "removedSongDeletions", "deletedPlaylistIds", "clearedPlaylistDeletionIds").forEach { field ->
            listOf(null, "null", "{}", "true", "[null]").forEach { assertInvalidField(field, it) }
        }
        listOf("null", "{}", "[null]").forEach { assertInvalidField("restoredPlaylistIds", it) }
    }

    @Test fun `IDs must be exact longs rather than coerced numbers`() {
        listOf("[1.5]", "[\"1\"]", "[9223372036854775808]", "[true]", "[{}]").forEach {
            assertInvalidField("deletedPlaylistIds", it)
        }
        val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
        fields.add("deletedPlaylistIds", JsonParser.parseString("[-9223372036854775808,9223372036854775807]"))
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), decodeLocalPlaylistSyncMutation(fields.toString()).deletedPlaylistIds)
    }

    @Test fun `damaged nested deletion fields and proof cannot be silently dropped`() {
        val invalid = listOf(
            "playlistId" to "null", "songId" to "\"9\"", "album" to "null", "deletedAt" to "0.5",
            "deviceId" to "[]", "mediaUri" to "{}", "removedMembershipTokens" to "null",
            "removedMembershipTokens" to "[null]", "removedMembershipTokens" to "[{\"deviceId\":\"A\",\"counter\":0}]",
            "removedMembershipTokens" to "[{\"deviceId\":\"\",\"counter\":1}]",
            "removedMembershipTokens" to "[{\"deviceId\":\"A\",\"counter\":\"1\"}]"
        )
        invalid.forEach { (name, value) ->
            val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
            fields.getAsJsonArray("addedSongDeletions").single().asJsonObject.add(name, JsonParser.parseString(value))
            assertThrows(name, IOException::class.java) { decodeLocalPlaylistSyncMutation(fields.toString()) }
        }
        listOf("playlistId", "songId", "album", "deletedAt", "deviceId").forEach { name ->
            val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
            fields.getAsJsonArray("addedSongDeletions").single().asJsonObject.remove(name)
            assertThrows(name, IOException::class.java) { decodeLocalPlaylistSyncMutation(fields.toString()) }
        }
    }

    @Test fun `damaged restoration identity cannot become an empty removal`() {
        listOf("null", "[]", "{\"playlistId\":8,\"identities\":null}", "{\"playlistId\":8,\"identities\":[null]}",
            "{\"playlistId\":8,\"identities\":[{\"id\":10,\"album\":null}]}",
            "{\"playlistId\":8,\"identities\":[{\"album\":\"local\"}]}").forEach {
            assertInvalidField("removedSongDeletions", "[$it]")
        }
    }

    private fun assertInvalidField(name: String, replacement: String?) {
        val fields = JsonParser.parseString(gson.toJson(mutation)).asJsonObject
        if (replacement == null) fields.remove(name) else fields.add(name, JsonParser.parseString(replacement))
        assertThrows(name, IOException::class.java) { decodeLocalPlaylistSyncMutation(fields.toString()) }
    }
}
