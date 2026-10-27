package moe.ouom.neriplayer.testutil

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.player.service.car.CarMediaBrowserService
import org.junit.Assert.assertEquals
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class MediaBrowserIsolationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            if (Build.VERSION.SDK_INT < 30 || Build.HARDWARE !in setOf("ranchu", "goldfish") ||
                context.resources.configuration.smallestScreenWidthDp >= 600) {
                base.evaluate()
                return
            }
            val manager = context.packageManager
            val component = ComponentName(context, CarMediaBrowserService::class.java)
            val originalState = manager.getComponentEnabledSetting(component)
            try {
                // 系统媒体恢复可能跨用例重绑服务，整组测试结束后再恢复浏览入口
                manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    manager.getComponentEnabledSetting(component))
                base.evaluate()
            } finally {
                manager.setComponentEnabledSetting(component, originalState, PackageManager.DONT_KILL_APP)
                assertEquals(originalState, manager.getComponentEnabledSetting(component))
            }
        }
    }
}
