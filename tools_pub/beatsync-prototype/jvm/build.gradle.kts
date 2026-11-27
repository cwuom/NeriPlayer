plugins {
    kotlin("jvm") version "2.4.10"
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.mozilla:rhino:1.8.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("prototype.MainKt") }
tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir.parentFile)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}

val ziplineProbeRuntime = configurations.create("ziplineProbeRuntime")
configurations.named(ziplineProbeRuntime.name) {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { add(ziplineProbeRuntime.name, "app.cash.zipline:zipline-jvm:1.28.0") }
tasks.register<JavaExec>("ziplineProbe") {
    dependsOn("classes")
    mainClass.set("prototype.QuickJsExperimentKt")
    classpath = sourceSets.main.get().runtimeClasspath + ziplineProbeRuntime
}
