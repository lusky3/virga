plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.sync"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Robolectric needs the merged manifest + resources on the unit-test
    // classpath; without this the test runtime cannot find AndroidManifest.xml.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    implementation(libs.bundles.coroutines)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.work.testing)
    // Integration tests run with Robolectric so they exercise the real
    // androidx.work.WorkManager backed by an in-memory database; this needs
    // the JUnit 4 vintage engine alongside the project's default JUnit 5.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit5.vintage)
}
