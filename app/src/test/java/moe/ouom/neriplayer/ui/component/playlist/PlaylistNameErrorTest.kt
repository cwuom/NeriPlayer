package moe.ouom.neriplayer.ui.component.playlist

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaylistNameErrorTest {
    @Test
    fun `retained reserved-name validation resolves both the message and playlist name again`() {
        val error = PlaylistNameError.FAVORITES_RESERVED
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("en", "zh").forEach { tag ->
            val resources = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            ).resources
            assertEquals(
                resources.getString(
                    CoreCommonR.string.library_name_reserved,
                    resources.getString(CoreCommonR.string.favorite_my_music)
                ),
                error.message().resolve(resources)
            )
        }
    }
}
