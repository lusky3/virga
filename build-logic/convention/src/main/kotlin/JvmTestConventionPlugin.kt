import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * Wires the standard JUnit 5 + coroutines-test + Turbine + Truth + MockK
 * test set every module shares. Skip it on modules that only use Robolectric
 * with JUnit 4 (none today).
 */
class JvmTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                libs.findBundle("junit5").orElseThrow().get().forEach { dep ->
                    add("testImplementation", dep)
                }
                add("testRuntimeOnly", libs.library("junit5-engine"))
                // Gradle 9 requires junit-platform-launcher to be on the test
                // runtime classpath when `useJUnitPlatform()` is in effect.
                add("testRuntimeOnly", libs.library("junit5-launcher"))
                add("testImplementation", libs.library("coroutines-test"))
                add("testImplementation", libs.library("turbine"))
                add("testImplementation", libs.library("truth"))
                add("testImplementation", libs.library("mockk"))
            }

            // Pin the test JVM's timezone and locale so anything that formats a
            // date/time (e.g. RunDetail's run timestamps) renders identically on
            // every machine and in CI. Without this, epoch timestamps format in
            // the host zone — a golden recorded at UTC-5 shows "Dec 31, 1969" while
            // CI (UTC) renders "Jan 1, 1970", so Roborazzi screenshots drift purely
            // by where they were recorded. UTC + en-US is the canonical baseline.
            tasks.withType(Test::class.java).configureEach {
                systemProperty("user.timezone", "UTC")
                systemProperty("user.language", "en")
                systemProperty("user.country", "US")
            }
        }
    }
}
