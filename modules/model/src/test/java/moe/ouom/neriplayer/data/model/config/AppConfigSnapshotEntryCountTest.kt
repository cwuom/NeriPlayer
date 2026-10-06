package moe.ouom.neriplayer.data.model.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigSnapshotEntryCountTest {

    @Test
    fun `listen together snapshots count non blank texts plus their three toggles`() {
        assertEquals(3, ListenTogetherConfigSnapshot().entryCount())
        assertEquals(
            3,
            ListenTogetherConfigSnapshot(
                workerBaseUrl = " ",
                workerBaseUrlInput = " ",
                userUuid = " ",
                nickname = " "
            ).entryCount()
        )
        assertEquals(
            7,
            ListenTogetherConfigSnapshot(
                workerBaseUrl = "https://ltw.example.invalid",
                workerBaseUrlInput = "ltw.example.invalid",
                userUuid = "uuid",
                nickname = "neri"
            ).entryCount()
        )
        assertEquals(4, ListenTogetherConfigSnapshot(nickname = "neri").entryCount())
    }

    @Test
    fun `typed preference snapshots count entries of every type`() {
        val snapshot = TypedPreferenceSnapshot(
            booleans = mapOf("a" to true),
            floats = mapOf("b" to 1f),
            ints = mapOf("c" to 1, "d" to 2),
            longs = mapOf("e" to 1L),
            strings = mapOf("f" to "x")
        )

        assertEquals(6, snapshot.entryCount())
        assertEquals(0, TypedPreferenceSnapshot().entryCount())
    }
}
