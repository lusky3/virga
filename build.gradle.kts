// Force-upgrade vulnerable *build-tooling* transitive dependencies that arrive
// through the Android Gradle Plugin's classpath (Dependabot flags these against
// settings.gradle.kts). None of them ship in the APK — they run only on the
// build machine — but pinning patched versions clears the alerts.
//
// Caveat: these versions are newer than the set AGP 9.2.1 was tested against.
// Verified green via `./gradlew assembleFossRelease test connectedCheck`. Revisit
// this list on every AGP bump — a newer AGP may render entries redundant or, in
// the worst case, ship an incompatible API that needs the pin relaxed.
buildscript {
    configurations.classpath {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "io.netty" ->
                    // netty enforces a single aligned version across all its
                    // modules; pin them together to the latest 4.1.x patch.
                    useVersion("4.1.135.Final")
                "org.bouncycastle" -> useVersion("1.84")
            }
            when (requested.module.toString()) {
                "com.squareup.wire:wire-runtime" -> useVersion("6.3.0")
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.6")
                "org.jdom:jdom2" -> useVersion("2.0.6.1")
                "org.apache.commons:commons-lang3" -> useVersion("3.20.0")
                "org.apache.httpcomponents:httpclient" -> useVersion("4.5.14")
            }
        }
    }
}

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
    // Applied at root: the sonar task analyzes the whole multi-module build.
    alias(libs.plugins.sonarqube)
}

// SonarCloud analysis (org "lusk", project "lusky3_virga"). Coverage is fed from
// Kover's aggregated XML — Kover emits a JaCoCo-format report, which Sonar reads
// via sonar.coverage.jacoco.xmlReportPaths. host.url is omitted: the plugin
// targets SonarCloud automatically when sonar.organization is set. CI runs
// `./gradlew koverXmlReport sonar` with SONAR_TOKEN.
sonar {
    properties {
        property("sonar.projectKey", "lusky3_virga")
        property("sonar.organization", "lusk")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/kover/report.xml").get().asFile.path,
        )
    }
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
        ":core:designsystem",
        ":core:database",
        ":core:datastore",
        ":core:rclone",
        ":core:data",
        ":sync-worker",
        ":feature:sync",
        ":feature:remotes",
        ":feature:settings",
        ":feature:explorer",
    ).forEach { kover(project(it)) }
}
