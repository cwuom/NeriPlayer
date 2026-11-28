plugins {
    alias(libs.plugins.android.test)
    id("build-logic.android.base")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "moe.ouom.neriplayer.baselineprofile"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // 生成依赖真机或本地模拟器，CI 不跑；产物提交在 app/src/release/generated/baselineProfiles
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId }
        )
    }
}
