package app.lusk.virga

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Smoke-tests the full Hilt graph by resolving a non-trivial dependency
 * ([SyncScheduler], which transitively brings up the database, repository,
 * WorkManager, etc). This is the canonical template for screen-level
 * instrumented tests — copy the rule order and `inject()` call, then add a
 * `createAndroidComposeRule<MainActivity>()` as `@get:Rule(order = 1)` and
 * exercise the UI. Kept rendering-free here so the scaffold passes
 * deterministically without the splash-gate / DataStore timing flakiness that
 * an entry-point screen test brings.
 */
@HiltAndroidTest
class MainActivityNavigationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun hiltGraph_resolvesAppScopedDependencies() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(
            context,
            app.lusk.virga.sync.BootReceiver.BootReceiverEntryPoint::class.java,
        )
        assertThat(entry.scheduler()).isNotNull()
    }
}
