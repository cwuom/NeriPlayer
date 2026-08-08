package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHostScreenTest {

    @Test
    fun usageSnapshotStaysStableWhileDetailIsOpen() {
        val current = listOf(usageEntry(id = 1L))
        val incoming = listOf(usageEntry(id = 2L), usageEntry(id = 1L))

        assertEquals(
            current,
            resolveHomeUsageEntriesForDisplay(
                currentEntries = current,
                incomingEntries = incoming,
                detailOpen = true,
                snapshotFrozen = false
            )
        )
    }

    @Test
    fun usageSnapshotStaysStableWhileOpeningDetail() {
        val current = listOf(usageEntry(id = 1L))
        val incoming = listOf(usageEntry(id = 2L), usageEntry(id = 1L))

        assertEquals(
            current,
            resolveHomeUsageEntriesForDisplay(
                currentEntries = current,
                incomingEntries = incoming,
                detailOpen = false,
                snapshotFrozen = true
            )
        )
    }

    @Test
    fun usageSnapshotAppliesLatestEntriesAfterDetailCloses() {
        val current = listOf(usageEntry(id = 1L))
        val incoming = listOf(usageEntry(id = 2L), usageEntry(id = 1L))

        assertEquals(
            incoming,
            resolveHomeUsageEntriesForDisplay(
                currentEntries = current,
                incomingEntries = incoming,
                detailOpen = false,
                snapshotFrozen = false
            )
        )
    }

    private fun usageEntry(id: Long): UsageEntry {
        return UsageEntry(
            id = id,
            name = "playlist-$id",
            picUrl = null,
            trackCount = 1,
            source = "netease",
            lastOpened = id,
            openCount = 1
        )
    }
}
