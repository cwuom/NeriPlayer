package moe.ouom.neriplayer.core.download.storage.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationPolicyProbeTest {
    @Test
    fun `directory change with source entries offers migration`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = true,
            probes = probes
        )

        assertEquals(ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION, decision)
        assertEquals(listOf("source", "target_non_empty"), probes)
    }

    @Test
    fun `directory change without source entries applies directly`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = false,
            probes = probes
        )

        assertEquals(ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY, decision)
        assertEquals(listOf("source"), probes)
    }

    @Test
    fun `default to populated SAF reattaches without migration prompt`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = null,
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = false,
            targetHasManagedEntries = true,
            probes = probes
        )

        assertEquals(
            ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET,
            decision
        )
        assertEquals(listOf("source", "target"), probes)
    }

    @Test
    fun `restoring default with source entries asks before migration`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = null,
            sourceHasManagedEntries = true,
            probes = probes
        )

        assertEquals(ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION, decision)
        assertEquals(listOf("source", "target_non_empty"), probes)
    }

    @Test
    fun `nonempty default target warns when restoring from SAF`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = null,
            sourceHasManagedEntries = true,
            targetNonEmpty = true,
            probes = probes
        )

        assertEquals(
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET,
            decision
        )
        assertEquals(listOf("source", "target_non_empty"), probes)
    }

    @Test
    fun `restoring default is eligible for migration confirmation`() {
        assertTrue(
            ManagedDownloadMigrationPolicy.requiresExplicitConfirmation(
                fromDirectoryUri = "content://provider/tree/source",
                toDirectoryUri = null
            )
        )
    }

    @Test
    fun `restoring default without source entries applies directly`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = null,
            sourceHasManagedEntries = false,
            probes = probes
        )

        assertEquals(ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY, decision)
        assertEquals(listOf("source"), probes)
    }

    @Test
    fun `nonempty target adds an explicit migration conflict decision`() = runBlocking {
        val probes = mutableListOf<String>()

        val decision = resolveDirectoryChangeDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = true,
            targetHasManagedEntries = false,
            targetNonEmpty = true,
            probes = probes
        )

        assertEquals(
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET,
            decision
        )
        assertEquals(listOf("source", "target_non_empty"), probes)
    }

    @Test
    fun `SAF to default does not probe either directory`() = runBlocking {
        val probes = mutableListOf<String>()

        val shouldReattach = resolveReattachDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = null,
            probes = probes
        )

        assertFalse(shouldReattach)
        assertEquals(emptyList<String>(), probes)
    }

    @Test
    fun `SAF to SAF does not probe either directory`() = runBlocking {
        val probes = mutableListOf<String>()

        val shouldReattach = resolveReattachDecision(
            fromDirectoryUri = "content://provider/tree/source",
            toDirectoryUri = "content://provider/tree/target",
            probes = probes
        )

        assertFalse(shouldReattach)
        assertEquals(emptyList<String>(), probes)
    }

    @Test
    fun `default to SAF stops after source reports managed entries`() = runBlocking {
        val probes = mutableListOf<String>()

        val shouldReattach = resolveReattachDecision(
            fromDirectoryUri = null,
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = true,
            probes = probes
        )

        assertFalse(shouldReattach)
        assertEquals(listOf("source"), probes)
    }

    @Test
    fun `default to existing SAF probes source before target and reattaches`() = runBlocking {
        val probes = mutableListOf<String>()

        val shouldReattach = resolveReattachDecision(
            fromDirectoryUri = null,
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = false,
            targetHasManagedEntries = true,
            probes = probes
        )

        assertTrue(shouldReattach)
        assertEquals(listOf("source", "target"), probes)
    }

    @Test
    fun `default to empty SAF probes source before target without reattaching`() = runBlocking {
        val probes = mutableListOf<String>()

        val shouldReattach = resolveReattachDecision(
            fromDirectoryUri = null,
            toDirectoryUri = "content://provider/tree/target",
            sourceHasManagedEntries = false,
            targetHasManagedEntries = false,
            probes = probes
        )

        assertFalse(shouldReattach)
        assertEquals(listOf("source", "target"), probes)
    }

    @Test
    fun `probe failure is preserved for the UI error boundary`() {
        val probes = mutableListOf<String>()

        val failure = runCatching {
            runBlocking {
                ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectoryAfterProbes(
                    fromDirectoryUri = null,
                    toDirectoryUri = "content://provider/tree/target",
                    probeSourceHasManagedEntries = {
                        probes += "source"
                        error("provider unavailable")
                    },
                    probeTargetHasManagedEntries = {
                        probes += "target"
                        false
                    }
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("provider unavailable", failure?.message)
        assertEquals(listOf("source"), probes)
    }

    private suspend fun resolveReattachDecision(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        sourceHasManagedEntries: Boolean = false,
        targetHasManagedEntries: Boolean = false,
        probes: MutableList<String>
    ): Boolean {
        return ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectoryAfterProbes(
            fromDirectoryUri = fromDirectoryUri,
            toDirectoryUri = toDirectoryUri,
            probeSourceHasManagedEntries = {
                probes += "source"
                sourceHasManagedEntries
            },
            probeTargetHasManagedEntries = {
                probes += "target"
                targetHasManagedEntries
            }
        )
    }

    private suspend fun resolveDirectoryChangeDecision(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        sourceHasManagedEntries: Boolean = false,
        targetHasManagedEntries: Boolean = false,
        targetNonEmpty: Boolean = targetHasManagedEntries,
        probes: MutableList<String>
    ): ManagedDownloadDirectoryChangeDecision {
        return ManagedDownloadMigrationPolicy.resolveDirectoryChangeAfterProbes(
            fromDirectoryUri = fromDirectoryUri,
            toDirectoryUri = toDirectoryUri,
            probeSourceHasManagedEntries = {
                probes += "source"
                sourceHasManagedEntries
            },
            probeTargetHasManagedEntries = {
                probes += "target"
                targetHasManagedEntries
            },
            probeTargetNonEmpty = {
                probes += "target_non_empty"
                targetNonEmpty
            }
        )
    }
}
