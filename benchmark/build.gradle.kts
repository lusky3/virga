// Macrobenchmark + baseline-profile module. Produces a Compose-aware AOT
// profile that ships with the app and pre-compiles the cold-start hot path,
// trimming ~30% off startup on first launch / after install.
//
// Generate the profile (connected device or emulator required, API 28+):
//   ./gradlew :app:generateGithubReleaseBaselineProfile
//
// The profile lands at app/src/githubRelease/generated/baselineProfiles/.

plugins {
    // AGP 9 brought `com.android.test` onto the build-logic classpath via the
    // convention plugins' AGP dependency, so we cannot version-pin it again
    // here. AGP 9 also has built-in Kotlin support, so the standalone
    // kotlin-android plugin is not needed (and is rejected when applied).
    id("com.android.test")
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.lusk.virga.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 28          // benchmark requires API 28+
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app advertises three distribution flavors (github / fdroid / play);
        // the benchmark targets `github` (the primary sideload release). Declared on
        // defaultConfig because the benchmark module itself has no flavors.
        missingDimensionStrategy("distribution", "github")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// Drive the baseline profile against whichever device is connected. CI can
// substitute a Gradle Managed Device by adding one in `android.testOptions`
// and setting `managedDevices += "yourDevice"` here.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
}
