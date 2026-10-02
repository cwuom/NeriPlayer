package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsQueryLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun resumeSamplesProcessTimeZoneAndDisposalUnregistersPlatformListeners() {
        val originalZone = TimeZone.getDefault()
        val initialZone = TimeZone.getTimeZone("UTC")
        val resumedZone = TimeZone.getTimeZone("GMT+12:00")
        val context = ReceiverTrackingContext(ApplicationProvider.getApplicationContext())
        val owner = TestLifecycleOwner()
        val visible = mutableStateOf(true)
        lateinit var day: State<StatsQueryDay>

        try {
            TimeZone.setDefault(initialZone)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            composeRule.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                    if (visible.value) day = rememberStatsQueryDay()
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(initialZone.id, day.value.key.timeZoneId)
                assertEquals(1, context.registrationCount)
                assertEquals(1, context.activeReceiverCount)
                assertEquals(1, owner.lifecycle.observerCount)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
            }
            composeRule.waitForIdle()
            val pausedDay = composeRule.runOnIdle { day.value }

            // 只改变测试进程时区，恢复事件负责重新采样，不依赖系统广播
            TimeZone.setDefault(resumedZone)
            composeRule.runOnIdle {
                assertSame(pausedDay, day.value)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            composeRule.waitUntil(timeoutMillis = 3_000) { day.value.key.timeZoneId == resumedZone.id }
            composeRule.runOnIdle {
                assertNotEquals(pausedDay.key, day.value.key)
                assertEquals(resumedZone.id, day.value.key.timeZoneId)
                visible.value = false
            }
            composeRule.waitForIdle()
            val disposedDay = composeRule.runOnIdle {
                assertEquals(1, context.unregistrationCount)
                assertEquals(0, context.activeReceiverCount)
                assertEquals(0, owner.lifecycle.observerCount)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                day.value
            }
            TimeZone.setDefault(initialZone)
            composeRule.waitForIdle()
            composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertSame(disposedDay, day.value) }
        } finally {
            try {
                composeRule.runOnIdle { visible.value = false }
                composeRule.waitForIdle()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                }
            } finally {
                TimeZone.setDefault(originalZone)
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    // 委托真实平台注册和注销，记录成功后的状态以验证 composition 清理
    private class ReceiverTrackingContext(base: Context) : ContextWrapper(base) {
        private val receivers = mutableSetOf<BroadcastReceiver>()
        var registrationCount = 0
            private set
        var unregistrationCount = 0
            private set
        val activeReceiverCount: Int get() = receivers.size

        override fun getApplicationContext(): Context = this

        override fun registerReceiver(
            receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?, flags: Int
        ): Intent? = baseContext.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
            .also { recordRegistration(receiver) }

        override fun registerReceiver(
            receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?
        ): Intent? = ContextCompat.registerReceiver(baseContext, receiver, filter,
            broadcastPermission, scheduler, ContextCompat.RECEIVER_NOT_EXPORTED)
            .also { recordRegistration(receiver) }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            baseContext.unregisterReceiver(receiver)
            receivers.remove(receiver)
            unregistrationCount += 1
        }

        private fun recordRegistration(receiver: BroadcastReceiver?) {
            if (receiver != null) {
                receivers.add(receiver)
                registrationCount += 1
            }
        }
    }
}
