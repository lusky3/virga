// Top-level build file. Plugins are declared here (apply false) and applied
// in each module so the Gradle plugin classpath is resolved once.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    // Applied (not apply-false) so the root project is the coverage aggregator.
    alias(libs.plugins.kover)
}

// Aggregate unit-test coverage from every code module into one report.
// rclone-build (no Kotlin) and benchmark (instrumentation only) are excluded.
// Each module applies Kover via its convention plugin and copies its testable
// Android build variant into the total ("") report; merging those totals here
// gives a single project-wide number. Run:
//   ./gradlew koverHtmlReport   (HTML at build/reports/kover/html)
//   ./gradlew koverLog          (prints % to the build log)
dependencies {
    listOf(
        ":app",
        ":core:common",
        ":core:ui",
        ":core:database",
        ":core:datastore",
        ":core:rclone",
        ":core:storage",
        ":core:data",
        ":sync-worker",
        ":feature:sync",
        ":feature:remotes",
        ":feature:settings",
        ":feature:explorer",
    ).forEach { kover(project(it)) }
}
