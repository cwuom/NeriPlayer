import com.android.build.api.dsl.ApplicationExtension
plugins {
    id("com.android.application")
    id("build-logic.android.base")
}

extensions.findByType(ApplicationExtension::class)?.run {
    defaultConfig {
        versionCode = Common.getBuildVersionCode()
        versionName = Common.getBuildVersionName(project)
    }

    androidResources {
        localeFilters += setOf("zh", "en")
    }
}
