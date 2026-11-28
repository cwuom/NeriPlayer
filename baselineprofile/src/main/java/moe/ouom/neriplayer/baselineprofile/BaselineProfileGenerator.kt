package moe.ouom.neriplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 app/src/release/generated/baselineProfiles 下的 baseline / startup profile：
 * ./gradlew :app:generateBaselineProfile
 * 需要已连接 API 33+ 的模拟器或设备（更低版本需 root）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** 只有冷启动到首屏的路径进入 startup profile，R8 按它重排 dex。 */
    @Test
    fun startup() = rule.collect(
        packageName = targetPackage,
        includeInStartupProfile = true
    ) {
        // 首次安装后先看到引导页；fixture 广播会拉起进程，杀掉后才是冷启动
        killProcess()
        setOnboardingCompleted(false)
        killProcess()
        pressHome()
        startActivityAndWait()
        waitForOnboarding()

        // 老用户的常规冷启动直接进主界面
        killProcess()
        setOnboardingCompleted(true)
        killProcess()
        pressHome()
        startActivityAndWait()
        waitForMainScreen()
    }

    /** 首屏之后的切页与滚动只进 baseline profile，避免撑大启动 dex。 */
    @Test
    fun mainTabs() = rule.collect(packageName = targetPackage) {
        killProcess()
        setOnboardingCompleted(true)
        killProcess()
        pressHome()
        startActivityAndWait()
        waitForMainScreen()
        browseMainTabs()
    }
}
