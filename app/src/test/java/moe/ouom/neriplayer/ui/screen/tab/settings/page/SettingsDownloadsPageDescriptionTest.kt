package moe.ouom.neriplayer.ui.screen.tab.settings.page

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsDownloadsPageDescriptionTest {

    @Test
    fun downloadDirectoryAndFileNameSettingsLiveOnStoragePage() {
        listOf(
            "download_directory_uri",
            "download_directory_label",
            "download_file_name_template"
        ).forEach { key ->
            val setting = AutoSettingsMetadata.settings.first { it.keyName == key }
            assertEquals(key, SettingsPage.Storage, setting.settingsPage())
        }
    }

    // #725：下载目录与文件名格式在存储页，下载管理的副标题不能再把它们列为入口内容
    @Test
    fun downloadsPageDescriptionDoesNotAdvertiseStorageOwnedOptions() {
        val storageOnlyTerms = mapOf(
            Locale.SIMPLIFIED_CHINESE to listOf("目录", "文件名", "保存格式"),
            Locale.ENGLISH to listOf("location", "directory", "folder", "file name", "file format")
        )
        storageOnlyTerms.forEach { (locale, terms) ->
            val description = localizedString(locale, SettingsPage.Downloads.descriptionRes)
            terms.forEach { term ->
                assertFalse(
                    "$locale description \"$description\" mentions \"$term\"",
                    description.contains(term, ignoreCase = true)
                )
            }
        }
    }

    private fun localizedString(locale: Locale, resId: Int): String {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).getString(resId)
    }
}
