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
    // implementation (not api): the repositories' public API returns domain models
    // (core:common), so database/datastore types must NOT leak onto consumers'
    // classpaths. Modules that genuinely use those types (e.g. feature:sync injects
    // PreferencesRepository) declare their own explicit dependency. rclone is
    // likewise an internal implementation detail of the repositories.
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:rclone"))
    // room-ktx for `withTransaction` — RemoteRepository.deleteRemote wraps its two
    // DAO writes in one transaction so process death can't orphan task rows.
    implementation(libs.room.ktx)
    implementation(libs.paging.runtime)
    implementation(libs.bundles.coroutines)
}
