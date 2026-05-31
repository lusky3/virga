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
    // database/datastore types (entities, AppPreferences) still cross the
    // repository boundary in return types, so they remain api until domain
    // models are introduced. rclone is purely an internal implementation detail
    // of the repositories — it must NOT leak onto consumers' classpaths.
    api(project(":core:database"))
    api(project(":core:datastore"))
    implementation(project(":core:rclone"))
    implementation(libs.bundles.coroutines)
}
