package app.lusk.virga.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures a cold-start baseline profile for Virga. The generator launches the
 * activity (or the FOSS-debug applicationId on dev runs) and waits for first
 * frame; the produced profile pre-compiles the Compose hot path so the next
 * cold start AOT-skips JIT warmup.
 *
 * Run with:
 *   ./gradlew :app:generateFossBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        // Wait until the welcome onboarding text is on screen — that's the
        // first interactive state, after splash and the DataStore preferences
        // gate resolve.
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        // Use the debug applicationId for local generation runs because that
        // is the variant we actually install on the AVD; release builds keep
        // the bare applicationId and the same profile applies.
        const val TARGET_PACKAGE = "app.lusk.virga"
    }
}
