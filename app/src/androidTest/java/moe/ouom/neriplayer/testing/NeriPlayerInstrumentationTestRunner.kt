package moe.ouom.neriplayer.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime

class NeriPlayerInstrumentationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application {
        InstrumentationTestRuntime.markActive()
        return super.newApplication(cl, className, context)
    }
}
