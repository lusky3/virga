package app.lusk.virga

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Replaces the real [@AndroidEntryPoint][dagger.hilt.android.AndroidEntryPoint]
 * application with Hilt's [HiltTestApplication] for instrumented tests. Wired
 * via `testInstrumentationRunner` in this module's build script — tests using
 * `@HiltAndroidTest` and `HiltAndroidRule` won't bring up the graph without it.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
