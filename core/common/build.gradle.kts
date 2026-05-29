plugins {
    id("virga.android.library")
    id("virga.jvm.test")
}

android {
    namespace = "app.lusk.virga.core.common"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.coroutines)
}
