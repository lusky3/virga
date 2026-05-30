plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.lusk.virga.core.rclone"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // SEC-M1: Enable BuildConfig generation so RcloneDaemonManager can gate
    // debug-only logging behind BuildConfig.DEBUG at compile time.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.bundles.coroutines)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.security.crypto)
    implementation(libs.favre.bcrypt)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.coroutines.test)
}

// Wire native binary production into the normal Android build graph. The
// rclone-build module owns the actual cross-compile; preBuild here just
// ensures the artifacts are present before merging jniLibs. The dependency
// is conditional: if the prebuilt binaries are already on disk (the common
// case for IDE / contributor builds), the Exec task is up-to-date and is a
// no-op. Set -PskipRcloneBuild to opt out entirely (e.g. for hermetic CI
// jobs that ship pre-staged binaries).
if (!project.hasProperty("skipRcloneBuild")) {
    tasks.named("preBuild").configure {
        dependsOn(":rclone-build:assembleNativeBinaries")
    }
}
