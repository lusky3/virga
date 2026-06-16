plugins {
    id("virga.android.library")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.lusk.virga.feature.sync"
    // Compose unit tests run under Robolectric and need the merged AndroidManifest +
    // resources on the unit-test classpath. They are written against JUnit 4 (the
    // form `compose-ui-test-junit4` expects) and run alongside the module's JUnit 5
    // tests via the Vintage engine.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    // PreferencesRepository / AppPreferences are injected/used here (was transitive
    // via core:data's old api() export — now an explicit dependency).
    implementation(project(":core:datastore"))
    implementation(project(":sync-worker"))

    implementation(libs.bundles.coroutines)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    // (compose BOM is added by the virga.android.compose convention plugin)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    testImplementation(libs.paging.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
    testRuntimeOnly(libs.junit5.vintage)
}
