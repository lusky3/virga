plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.room)
}

android {
    namespace = "app.lusk.virga.core.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // BuildConfig.DEBUG gates the destructive-migration fallback (debug only).
    buildFeatures { buildConfig = true }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.bundles.coroutines)

    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    // Robolectric is a JUnit4 runner; run it under the JUnit Platform via the
    // vintage engine alongside the project's JUnit5 setup.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit5.vintage)
}
