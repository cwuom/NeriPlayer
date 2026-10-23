package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsAccessMode
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigBackend
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigCoordinator
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigException
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigJournal
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigStatus
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsRusRow
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsSystemUiAsset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ColorOsRecoveryDiscardTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun discardBypassesUnavailableSystemUiAndBothPrivilegedModes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (mode in ColorOsAccessMode.entries) {
            val file = File(temporaryFolder.newFolder(), "recovery.json")
            val backend = Backend(ColorOsRusRow("10", XML))
            val coordinator = coordinator(context.packageName, file)
            assertEquals(ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED, coordinator.apply(backend).status)
            val configured = backend.row
            val result = executeColorOsConfiguration(context, file, mode, ColorOsConfigurationAction.DISCARD)
            assertEquals(ColorOsConfigStatus.RECOVERY_DISCARDED, result.status)
            assertNull(result.uid)
            assertFalse(file.exists())
            assertEquals(configured, backend.row)
            assertEquals(1, backend.writes)
            assertEquals(1, backend.reloads)
        }
    }

    @Test
    fun unresolvedWritesRemainProtectedInTheLocalDiscardRoute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(temporaryFolder.newFolder(), "recovery.json")
        val backend = Backend(ColorOsRusRow("10", XML), failUnknown = true)
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, coordinator(context.packageName, file).apply(backend).status)
        val original = file.readBytes()
        val result = executeColorOsConfiguration(context, file, ColorOsAccessMode.ROOT, ColorOsConfigurationAction.DISCARD)
        assertEquals(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, result.status)
        assertTrue(file.exists())
        assertArrayEquals(original, file.readBytes())
        assertEquals(1, backend.writes)
        assertEquals(0, backend.reloads)
    }

    private fun coordinator(applicationId: String, file: File) = ColorOsConfigCoordinator(
        applicationId,
        { ColorOsSystemUiAsset(1600, 1, XML) },
        ColorOsConfigJournal(file),
    )

    private class Backend(var row: ColorOsRusRow, private val failUnknown: Boolean = false) : ColorOsConfigBackend {
        override val uid = 2000
        var writes = 0
        var reloads = 0
        override suspend fun read(): ColorOsRusRow = row
        override suspend fun compareAndSet(expected: ColorOsRusRow, replacement: ColorOsRusRow): Int {
            check(row == expected)
            writes++
            if (failUnknown) throw ColorOsConfigException(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, "lost_reply")
            row = replacement
            return 1
        }
        override suspend fun requestReload() { reloads++ }
        override fun close() = Unit
    }

    companion object {
        private const val XML = "<config><version>10</version><whitelist/><policy/></config>"
    }
}
