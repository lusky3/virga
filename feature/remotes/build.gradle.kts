plugins {
    id("virga.android.library")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.lusk.virga.feature.remotes"
    // The OAuthRedirectActivity intent-filter uses the ${googleOAuthScheme}
    // placeholder, which is normally injected by the app module. Provide a
    // library-local default so this module's own manifest merge (e.g. the
    // Robolectric unit-test manifest) resolves it. The real value is still
    // supplied by the app at app-merge time.
    defaultConfig {
        manifestPlaceholders["googleOAuthScheme"] = "com.googleusercontent.apps.unset"
    }
    // The OAuthRedirectActivity / classifyOAuthRedirect tests run under Robolectric
    // (they need a real android.net.Uri parser + the merged manifest). They are
    // JUnit 4 and run alongside the module's JUnit 5 tests via the Vintage engine.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    // Direct rclone use (OAuth flow types) — explicit now that core:data no
    // longer re-exports rclone via api().
    implementation(project(":core:rclone"))

    implementation(libs.bundles.coroutines)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
    testRuntimeOnly(libs.junit5.vintage)
}
