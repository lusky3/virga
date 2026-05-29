plugins {
    id("virga.android.library")
    id("virga.android.hilt")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.core.data"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":core:rclone"))
    implementation(libs.bundles.coroutines)
}
