package moe.ouom.neriplayer.data.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.AccessResult
import moe.ouom.neriplayer.data.model.config.TypedPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigSettingsSanitizerDownloadDirectoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sanitizer = ConfigSettingsSanitizer(context)
    private val invalidWarning = context.getString(CoreCommonR.string.config_import_warning_invalid_setting_values)
    private val directoryWarning = context.getString(CoreCommonR.string.config_import_warning_download_directory)

    @Test
    fun `granted existing directories are kept and spelled canonically`() {
        val directory = granted(folder.newFolder("music"))

        assertEquals(Imported(mapOf(DIRECTORY to directory, LABEL to "Music")), import(DIRECTORY to directory, LABEL to "Music"))
        assertEquals(Imported(mapOf(DIRECTORY to directory), invalidWarning), import(DIRECTORY to "$directory/"))
        assertEquals(Imported(mapOf(DIRECTORY to directory), invalidWarning), import(DIRECTORY to directory, LABEL to "  "))
    }

    @Test
    fun `missing or ungranted directories are cleared with a directory warning`() {
        val missing = granted(File(folder.root, "missing"))
        val ungranted = Uri.fromFile(folder.newFolder("ungranted")).toString()

        assertEquals(Imported(emptyMap(), directoryWarning), import(DIRECTORY to missing, LABEL to "Music"))
        assertEquals(Imported(emptyMap(), directoryWarning), import(DIRECTORY to ungranted))
    }

    @Test
    fun `blank directories and orphan labels are dropped as invalid values`() {
        assertEquals(Imported(emptyMap(), invalidWarning), import(DIRECTORY to "  ", LABEL to "Music"))
        assertEquals(Imported(emptyMap(), invalidWarning), import(LABEL to "Music"))
        assertEquals(Imported(emptyMap()), import())
    }

    @Test
    fun `directory inspection results map onto persisted tree access`() {
        assertEquals(PersistedTreeAccess.Accessible, persistedTreeAccessOf(AccessResult.Accessible))
        assertEquals(PersistedTreeAccess.Missing, persistedTreeAccessOf(AccessResult.Missing))
        assertEquals(PersistedTreeAccess.PermissionLost, persistedTreeAccessOf(AccessResult.PermissionLost))
        assertEquals(
            PersistedTreeAccess.ProviderFailure,
            persistedTreeAccessOf(AccessResult.ProviderFailure(IllegalStateException("provider crashed")))
        )
    }

    private fun granted(directory: File): String {
        val uri = Uri.fromFile(directory)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri.toString()
    }

    private fun import(vararg strings: Pair<String, String>): Imported {
        val warnings = mutableListOf<String>()
        val sanitized = sanitizer.sanitize(TypedPreferenceSnapshot(strings = mapOf(*strings)), warnings)
        return Imported(sanitized.strings, warnings)
    }

    private data class Imported(val strings: Map<String, String>, val warnings: List<String>) {
        constructor(strings: Map<String, String>, vararg warnings: String) : this(strings, warnings.toList())
    }

    private companion object {
        val DIRECTORY = SettingsKeys.DOWNLOAD_DIRECTORY_URI.name
        val LABEL = SettingsKeys.DOWNLOAD_DIRECTORY_LABEL.name
    }
}
