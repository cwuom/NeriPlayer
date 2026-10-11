package moe.ouom.neriplayer.data.local.database.store.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiffTargetTest {
    private fun target(previous: String?, incoming: String?, clearAdvanced: Boolean = false) = playbackDiffTarget(
        previous, incoming, isSynced = { it.startsWith("synced") }, clearAdvanced,
        mergeWithIncoming = { local, remote -> "$local+$remote" }, trimLocal = { local -> "trimmed $local" }
    )

    @Test
    fun `remote records replace synced local ones and merge into device private ones`() {
        assertEquals(PlaybackDiffTarget.Write("remote"), target("synced a", "remote"))
        assertEquals(PlaybackDiffTarget.Write("remote"), target(null, "remote"))
        assertEquals(PlaybackDiffTarget.Write("device a+remote"), target("device a", "remote"))
    }

    @Test
    fun `synced local records the remote dropped are removed`() {
        assertEquals(PlaybackDiffTarget.Write<String>(null), target("synced a", null))
        assertEquals(PlaybackDiffTarget.Write<String>(null), target("synced a", null, clearAdvanced = true))
    }

    @Test
    fun `device private records are kept unless a newer clear trims them`() {
        assertEquals(PlaybackDiffTarget.Keep, target("device a", null))
        assertEquals(PlaybackDiffTarget.Write("trimmed device a"), target("device a", null, clearAdvanced = true))
    }
}
