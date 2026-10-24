package moe.ouom.neriplayer.testing

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime

class NeriPlayerInstrumentationTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(withAdditionalExcludedTestClass(arguments))
    }

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application {
        InstrumentationTestRuntime.markActive()
        return super.newApplication(cl, className, context)
    }
}

internal fun withAdditionalExcludedTestClass(arguments: Bundle?): Bundle {
    val merged = arguments?.let(::Bundle) ?: Bundle()
    val additional = merged.getString("neriplayerAdditionalExcludedClass").orEmpty().trim()
    merged.remove("neriplayerAdditionalExcludedClass")
    if (additional.isNotEmpty()) {
        // AGP 会按逗号拆分参数，在设备端合并才能保留完整排除列表
        val excluded = merged.getString("notClass").orEmpty().split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        merged.putString("notClass", (excluded + additional).distinct().joinToString(","))
    }
    return merged
}
