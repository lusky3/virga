package app.lusk.virga.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures Virga's cold-start time across three compilation modes. The
 * generated profile slot ([CompilationMode.Partial] with a `BaselineProfile`
 * warmup) should land between `None` and `Full` — typically ~30% faster than
 * `None` once the profile is generated.
 *
 * Run with:
 *   ./gradlew :benchmark:pixel6Api34BenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfile() = startup(
        CompilationMode.Partial(warmupIterations = 0),
    )

    @Test
    fun startupCompilationFull() = startup(CompilationMode.Full())

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = "app.lusk.virga",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        iterations = 5,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
