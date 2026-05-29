plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.core.datastore"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.datastore.preferences)
    implementation(libs.bundles.coroutines)
}
