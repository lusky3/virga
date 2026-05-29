plugins {
    id("virga.android.library")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.feature.remotes"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(libs.bundles.coroutines)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
}
