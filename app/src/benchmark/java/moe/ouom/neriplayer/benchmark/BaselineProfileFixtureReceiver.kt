package moe.ouom.neriplayer.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository

/**
 * 只打进 benchmark 类变体，供 Macrobenchmark 通过 adb shell 切换首启引导状态。
 * release 包没有 test APK 可写 DataStore，也不能 run-as，只能由应用进程自己写。
 */
class BaselineProfileFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val onboardingCompleted = intent.getBooleanExtra(EXTRA_ONBOARDING_COMPLETED, true)
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val settings = SettingsRepository(appContext)
                settings.setDisclaimerAccepted(onboardingCompleted)
                settings.setStartupOnboardingCompleted(onboardingCompleted)
                if (onboardingCompleted) {
                    // 正常完成引导时会回答预览主题提示，跳过引导后要补上，否则主界面会弹窗
                    PreviewThemePromptRepository(appContext).answer(enable = false)
                }
            }.onSuccess {
                pending.resultCode = Activity.RESULT_OK
            }.onFailure { error ->
                // 失败原因回传给 am broadcast 输出，便于生成器断言时定位
                pending.setResult(Activity.RESULT_CANCELED, error.toString(), null)
            }
            pending.finish()
        }
    }

    private companion object {
        const val EXTRA_ONBOARDING_COMPLETED = "onboardingCompleted"
    }
}
