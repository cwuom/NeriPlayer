package moe.ouom.neriplayer.data.storage

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageUsageResourceTest {
    @Test
    fun downloadIndexRecordsWithoutLegacyFilesRenderCountArguments() {
        assertEquals(
            "索引记录 118 条",
            downloadIndexCountDescription(
                localizedResources(Locale.SIMPLIFIED_CHINESE),
                downloadIndexUsageStats(
                    fileStats = FileStats.Empty,
                    roomStats = DownloadIndexStorageStats(
                        databaseRecordCount = 118,
                        allocatedPageBytes = 0L
                    )
                )
            )
        )
        assertEquals(
            "1 index record",
            downloadIndexCountDescription(
                localizedResources(Locale.US),
                downloadIndexUsageStats(
                    fileStats = FileStats.Empty,
                    roomStats = DownloadIndexStorageStats(
                        databaseRecordCount = 1,
                        allocatedPageBytes = 0L
                    )
                )
            )
        )
    }

    private fun localizedResources(locale: Locale): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        return context.createConfigurationContext(configuration).resources
    }
}
