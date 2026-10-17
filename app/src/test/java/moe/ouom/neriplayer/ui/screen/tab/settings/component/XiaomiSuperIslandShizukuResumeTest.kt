package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [35],
    application = Application::class,
    qualifiers = "zh-rCN-w360dp-h1000dp",
    shadows = [ReadySuperIslandShizuku::class],
    instrumentedPackages = ["rikka.shizuku"]
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XiaomiSuperIslandShizukuResumeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val autoSettingsRepository = AutoSettingsRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val pageOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private var pageVisible by mutableStateOf(true)

    @Before
    fun resetShizukuFixture() {
        SuperIslandShizukuService.reset()
    }

    @After
    fun disposePageBeforeResettingTheExternalService() {
        try {
            composeRule.runOnIdle {
                pageVisible = false
                pageOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            }
            composeRule.waitForIdle()
        } finally {
            SuperIslandShizukuService.reset()
        }
    }

    @Test
    fun returningAfterAnExternalGrantRefreshesTheMountedPage() {
        render(granted = false)
        assertStatus(granted = false)

        resumeAfterServiceChange(granted = true)

        assertStatus(granted = true)
        assertEquals(0, SuperIslandShizukuService.permissionRequests.size)
    }

    @Test
    fun returningAfterAReadyRebindClearsThePreviousTemporaryGrant() {
        render(granted = true)
        assertStatus(granted = true)

        // 新 binder 已就绪，临时授权随服务重启失效，不模拟会强停应用的 Manager 撤权
        resumeAfterServiceChange(granted = false)

        assertStatus(granted = false)
        assertEquals(0, SuperIslandShizukuService.permissionRequests.size)
    }

    private fun render(granted: Boolean) {
        SuperIslandShizukuService.granted.set(granted)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    if (pageVisible) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            XiaomiSuperIslandSettingsPage(autoSettingsRepository, settingsRepository)
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
    }

    private fun resumeAfterServiceChange(granted: Boolean) {
        composeRule.runOnIdle {
            pageOwner.lifecycle.currentState = Lifecycle.State.CREATED
            SuperIslandShizukuService.granted.set(granted)
            pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun assertStatus(granted: Boolean) {
        val visible = if (granted) {
            CoreCommonR.string.settings_xiaomi_super_island_shizuku_status_granted
        } else {
            CoreCommonR.string.settings_xiaomi_super_island_shizuku_status_missing
        }
        val hidden = if (granted) {
            CoreCommonR.string.settings_xiaomi_super_island_shizuku_status_missing
        } else {
            CoreCommonR.string.settings_xiaomi_super_island_shizuku_status_granted
        }
        composeRule.onNodeWithText(application.getString(visible))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(application.getString(hidden)).assertDoesNotExist()
    }
}

private object SuperIslandShizukuService {
    val granted = AtomicBoolean(false)
    val permissionRequests = CopyOnWriteArrayList<Int>()

    fun reset() {
        granted.set(false)
        permissionRequests.clear()
    }
}

@Implements(className = "rikka.shizuku.Shizuku", isInAndroidSdk = false)
class ReadySuperIslandShizuku {
    companion object {
        @JvmStatic
        @Implementation
        fun pingBinder(): Boolean = true

        @JvmStatic
        @Implementation
        fun checkSelfPermission(): Int = if (SuperIslandShizukuService.granted.get()) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }

        @JvmStatic
        @Implementation
        fun requestPermission(requestCode: Int) {
            SuperIslandShizukuService.permissionRequests.add(requestCode)
        }
    }
}
