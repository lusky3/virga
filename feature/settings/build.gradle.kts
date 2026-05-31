plugins {
    id("virga.android.library")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.feature.settings"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:ui"))

    implementation(libs.bundles.coroutines)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
}
