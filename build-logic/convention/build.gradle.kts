import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "moe.ouom.wekit.buildLogic"

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.android.tools)
    implementation(libs.kotlin.gradle)
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation(libs.eclipse.jgit)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
