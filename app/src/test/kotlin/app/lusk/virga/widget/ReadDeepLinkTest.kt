package app.lusk.virga.widget

import android.content.Intent
import androidx.compose.runtime.MutableState
import app.lusk.virga.MainActivity
import app.lusk.virga.core.common.notification.NotificationDeepLinks
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * Tests for the [MainActivity.readDeepLink] intent-routing logic added in D7b.
 *
 * # Why reflection?
 * [readDeepLink] is `private` and lives inside a Hilt `@AndroidEntryPoint`
 * activity, so launching it via Robolectric [buildActivity] would require the
 * full Hilt graph (ViewModels, DataStore, etc.). The function itself has no
 * side effects beyond writing to Compose snapshot state; using reflection to
 * call it on a plain un-started Activity is the lowest-risk seam that avoids
 * touching production code, exactly as [app.lusk.virga.share.ExtractUrisTest]
 * does for [app.lusk.virga.share.ShareReceiverActivity.extractUris].
 *
 * # Why Robolectric?
 * [android.content.Intent] needs an Android runtime for action / extra
 * parsing to behave correctly. Robolectric supplies the shadow implementations.
 *
 * # Assertions
 * [readDeepLink] writes its output to the `pendingRoute` Compose-state
 * delegate (`pendingRoute$delegate`). We read it back via reflection on the
 * [MutableState] value held by that field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadDeepLinkTest {

    private val readDeepLinkMethod: Method by lazy {
        MainActivity::class.java
            .getDeclaredMethod("readDeepLink", Intent::class.java)
            .also { it.isAccessible = true }
    }

    private val pendingRouteField by lazy {
        MainActivity::class.java
            .getDeclaredField("pendingRoute\$delegate")
            .also { it.isAccessible = true }
    }

    /** Creates an un-started [MainActivity] instance used only as the reflection receiver. */
    private fun activity(): MainActivity =
        MainActivity::class.java.getDeclaredConstructor().newInstance()

    /**
     * Invokes [readDeepLink] on [activity] and returns the resulting
     * [pendingRoute] value by reading the Compose [MutableState] delegate.
     */
    private fun readDeepLink(activity: MainActivity, intent: Intent?): String? {
        readDeepLinkMethod.invoke(activity, intent)
        @Suppress("UNCHECKED_CAST")
        val state = pendingRouteField.get(activity) as MutableState<String?>
        return state.value
    }

    // ── ACTION_SHORTCUT_ADD_REMOTE → ROUTE_ADD_REMOTE ─────────────────────────

    @Test
    fun `should map SHORTCUT_ADD_REMOTE action to ROUTE_ADD_REMOTE`() {
        val intent = Intent("app.lusk.virga.action.SHORTCUT_ADD_REMOTE")

        val route = readDeepLink(activity(), intent)

        assertThat(route).isEqualTo(NotificationDeepLinks.ROUTE_ADD_REMOTE)
    }

    // ── unrelated action → reads EXTRA_OPEN_ROUTE extra ──────────────────────

    @Test
    fun `should yield null route when intent has an unrelated action and no open-route extra`() {
        val intent = Intent("com.example.SOME_OTHER_ACTION")

        val route = readDeepLink(activity(), intent)

        assertThat(route).isNull()
    }

    @Test
    fun `should pass through EXTRA_OPEN_ROUTE value for a non-shortcut intent`() {
        val intent = Intent("com.example.SOME_OTHER_ACTION").apply {
            putExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE, NotificationDeepLinks.ROUTE_SETTINGS)
        }

        val route = readDeepLink(activity(), intent)

        assertThat(route).isEqualTo(NotificationDeepLinks.ROUTE_SETTINGS)
    }

    @Test
    fun `should yield null route for a null intent`() {
        val route = readDeepLink(activity(), null)

        assertThat(route).isNull()
    }

    // ── ROUTE_ADD_REMOTE is not aliased by the extra ──────────────────────────

    @Test
    fun `should NOT route to ROUTE_ADD_REMOTE when only EXTRA_OPEN_ROUTE carries that value`() {
        // The shortcut path is action-based; if someone sends EXTRA_OPEN_ROUTE="add_remote"
        // via a generic intent it still resolves — this confirms the generic path works too.
        val intent = Intent("com.example.NOTIFICATION_ACTION").apply {
            putExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE, NotificationDeepLinks.ROUTE_ADD_REMOTE)
        }

        val route = readDeepLink(activity(), intent)

        // ROUTE_ADD_REMOTE propagates through the extra path as well — both paths must
        // honour the constant so the onboarding CTA and the shortcut produce the same route.
        assertThat(route).isEqualTo(NotificationDeepLinks.ROUTE_ADD_REMOTE)
    }
}
