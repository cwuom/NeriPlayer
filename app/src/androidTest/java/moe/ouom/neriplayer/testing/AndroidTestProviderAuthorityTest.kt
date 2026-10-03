package moe.ouom.neriplayer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.download.ManagedDownloadDelayedDocumentsProvider
import moe.ouom.neriplayer.core.download.ManagedDownloadMigrationTestDocumentProvider
import moe.ouom.neriplayer.core.download.execution.DownloadPreflightTestProvider
import moe.ouom.neriplayer.data.local.media.Issue339LyricsTestDocumentProvider
import moe.ouom.neriplayer.data.local.media.StagedMetadataTestProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTestProviderAuthorityTest {
    @Test
    fun fixtureAuthoritiesResolveToTheCurrentTestPackage() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val authorities = listOf(
            ManagedDownloadDelayedDocumentsProvider.AUTHORITY,
            DownloadPreflightTestProvider.AUTHORITY,
            StagedMetadataTestProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY
        )

        for (authority in authorities) {
            assertTrue(authority, authority.startsWith("${context.packageName}."))
            val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0)) {
                "Provider is not registered: $authority"
            }
            assertEquals(context.packageName, provider.packageName)
            assertEquals(authority, provider.authority)
        }
    }
}
