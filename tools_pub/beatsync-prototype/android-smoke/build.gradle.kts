plugins {
    id("com.android.application") version "9.4.1"
    kotlin("jvm") version "2.4.10" apply false
}
val candidateOnly=providers.gradleProperty("candidate").map(String::toBoolean).getOrElse(false)
android {
    namespace="prototype.smoke"
    compileSdk=37
    defaultConfig {
        applicationId=if(candidateOnly) "moe.ouom.neriplayer.beatsynccandidate" else "moe.ouom.neriplayer.beatsyncprototype"
        minSdk=28;targetSdk=36;versionCode=1;versionName="0.1"
        manifestPlaceholders["entryActivity"]=if(candidateOnly) ".CandidateActivity" else ".MainActivity"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    sourceSets.getByName("main") {
        kotlin.directories += "../jvm/src/main/kotlin"
        assets.directories += "../js/generated"
        if(candidateOnly) {
            java.directories.clear()
            kotlin.directories.clear()
            kotlin.directories += "src/candidate/kotlin"
            kotlin.directories += "build/generated/candidate"
        }
    }
    buildTypes.getByName("release") {
        isMinifyEnabled=true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),if(candidateOnly) "candidate-rules.pro" else "proguard-rules.pro")
    }
}
dependencies {
    implementation("app.cash.zipline:zipline:1.28.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    if(!candidateOnly) {
        implementation("org.mozilla:rhino:1.8.0")
        implementation("io.ktor:ktor-server-cio:3.6.0")
        implementation("io.ktor:ktor-server-websockets:3.6.0")
    }
}

if(candidateOnly) {
    val prepareCandidateSources=tasks.register<Sync>("prepareCandidateSources") {
        from("../jvm/src/main/kotlin") { include("**/QuickJsExperiment.kt","**/Validation.kt","**/ProbeServers.kt") }
        into(layout.buildDirectory.dir("generated/candidate"))
    }
    tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach { dependsOn(prepareCandidateSources) }
    tasks.matching { it.name.startsWith("lint") || it.name.startsWith("generate") && it.name.contains("Lint") }.configureEach { dependsOn(prepareCandidateSources) }
}