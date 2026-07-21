plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mobile.racemaster"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "mobile.racemaster"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Never resolves to a real address outside dev — matches the empty defaults in
            // defaultConfig below, so a release build never carries live-looking placeholder
            // credentials even though the field itself must exist to compile either variant.
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            buildConfigField("String", "DEV_SERVER_USERNAME", "\"\"")
            buildConfigField("String", "DEV_SERVER_PASSWORD", "\"\"")
        }
        debug {
            // Lets a fresh debug install's Setup Server screen self-populate against the local
            // dev server (see scripts/dev-server.sh) instead of typing these in by hand every
            // time — only ever used as a fallback when nothing's been saved yet (see
            // MuleServerSetupScreen), so an existing real login is never overwritten.
            buildConfigField("String", "DEV_SERVER_URL", "\"http://127.0.0.1:3000\"")
            buildConfigField("String", "DEV_SERVER_USERNAME", "\"mobiletest\"")
            buildConfigField("String", "DEV_SERVER_PASSWORD", "\"test1234\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// One-click convenience for local manual testing (see scripts/dev-server.sh's own doc):
// starts the local racemaster server if it isn't already running and points every connected
// device's "localhost" at it via `adb reverse`. Shows up in Android Studio's Gradle tool
// window under Tasks/other, and can be added as a Run Configuration's "before launch" step.
tasks.register<Exec>("devServer") {
    group = "other"
    description = "Start the local racemaster server and adb reverse it to every connected device"
    commandLine("bash", "${rootDir}/scripts/dev-server.sh")
}

// It's detached from devServer's own Exec process (see scripts/dev-server.sh), so it keeps
// running across builds/IDE restarts on its own — this is the one-click way to kill it again.
tasks.register<Exec>("stopDevServer") {
    group = "other"
    description = "Stop the local racemaster server started by devServer"
    commandLine("bash", "${rootDir}/scripts/stop-dev-server.sh")
}

// Wired into every debug build/install and every debug instrumented-test run (both go through
// these variant preBuild tasks) so "Run" and "connectedDebugAndroidTest" from Android Studio or
// the command line always have a reachable local server and adb-reversed devices first — no
// separate `./gradlew devServer` step to remember each session. Left off preDebugUnitTestBuild
// since JVM unit tests don't touch a real device or network.
tasks.configureEach {
    if (name == "preDebugBuild" || name == "preDebugAndroidTestBuild") {
        dependsOn("devServer")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kable.core)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}