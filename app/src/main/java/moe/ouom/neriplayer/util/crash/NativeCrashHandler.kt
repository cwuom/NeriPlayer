package moe.ouom.neriplayer.util.crash

import android.content.Context

object NativeCrashHandler {
    enum class TestCrashType(val nativeValue: Int) {
        SigSegv(1),
        SigAbrt(2),
    }

    fun init(context: Context) {
        moe.ouom.neriplayer.util.NativeCrashHandler.init(context)
    }

    fun triggerTestCrash(context: Context, crashType: TestCrashType) {
        moe.ouom.neriplayer.util.NativeCrashHandler.triggerTestCrash(
            context = context,
            crashType = crashType.toRootType()
        )
    }

    private fun TestCrashType.toRootType(): moe.ouom.neriplayer.util.NativeCrashHandler.TestCrashType {
        return when (this) {
            TestCrashType.SigSegv -> moe.ouom.neriplayer.util.NativeCrashHandler.TestCrashType.SigSegv
            TestCrashType.SigAbrt -> moe.ouom.neriplayer.util.NativeCrashHandler.TestCrashType.SigAbrt
        }
    }
}
