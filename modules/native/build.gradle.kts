plugins {
    id("build-logic.android.library")
}

android {
    namespace = "moe.ouom.neriplayer.nativebridge"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-fexceptions", "-frtti")
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
