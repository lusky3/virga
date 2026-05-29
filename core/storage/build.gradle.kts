plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.core.storage"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.bundles.coroutines)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}
