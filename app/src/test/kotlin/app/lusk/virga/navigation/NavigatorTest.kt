package app.lusk.virga.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [Navigator] and [NavigationState].
 *
 * NavBackStack wraps SnapshotStateList which implements android.os.Parcelable, but
 * constructing and mutating it in a JVM test is safe because no Parcel/Android-framework
 * methods are invoked — the Parcelable interface is used only for serialization (config
 * change save/restore) and is never exercised here.
 *
 * Route objects are defined locally so this test has no dependency on the production
 * route objects that import Material icons (Compose UI).
 */
class NavigatorTest {

    // ── Local test routes ────────────────────────────────────────────────────

    @Serializable private object TabA : NavKey
    @Serializable private object TabB : NavKey
    @Serializable private object TabC : NavKey

    @Serializable private data class ChildRoute(val id: Int) : NavKey

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val topLevelRoutes: Set<NavKey> = setOf(TabA, TabB, TabC)

    /**
     * Builds a [NavigationState] seeded with one entry per top-level tab (mirroring
     * what [rememberNavBackStack] does inside [rememberNavigationState]).
     */
    private fun buildState(startRoute: NavKey = TabA): NavigationState {
        val backStacks: Map<NavKey, NavBackStack<NavKey>> = topLevelRoutes.associateWith { route ->
            @Suppress("UNCHECKED_CAST")
            NavBackStack(route) as NavBackStack<NavKey>
        }
        return NavigationState(
            startRoute = startRoute,
            topLevelRoute = mutableStateOf(startRoute),
            backStacks = backStacks,
        )
    }

    private fun buildNavigator(startRoute: NavKey = TabA): Navigator =
        Navigator(buildState(startRoute))

    // ── navigate() — top-level route ─────────────────────────────────────────

    @Nested
    inner class NavigateToTopLevelRoute {

        @Test
        fun `should switch activeTab when navigating to a different top-level route`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            assertThat(nav.state.topLevelRoute).isEqualTo(TabB)
        }

        @Test
        fun `should switch to third tab when navigating directly from start tab`() {
            val nav = buildNavigator()
            nav.navigate(TabC)
            assertThat(nav.state.topLevelRoute).isEqualTo(TabC)
        }

        @Test
        fun `should not affect other tabs backStacks when switching tabs`() {
            val nav = buildNavigator()
            // Push a child onto TabA before switching
            nav.navigate(ChildRoute(1))
            nav.navigate(TabB)

            // TabA still has its child
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(2)
            // TabB back stack is still at root (size 1)
            assertThat(nav.state.backStacks[TabB]?.size).isEqualTo(1)
        }
    }

    // ── navigate() — child route ──────────────────────────────────────────────

    @Nested
    inner class NavigateToChildRoute {

        @Test
        fun `should push child route onto current tab backStack`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(42))
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(2)
        }

        @Test
        fun `should push child onto the active non-start tab`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            nav.navigate(ChildRoute(1))
            assertThat(nav.state.backStacks[TabB]?.size).isEqualTo(2)
        }

        @Test
        fun `should not change topLevelRoute when pushing a child`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(7))
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `should allow pushing multiple children onto the same tab`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.navigate(ChildRoute(2))
            nav.navigate(ChildRoute(3))
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(4)
        }

        @Test
        fun `should place the child route at the top of the stack`() {
            val nav = buildNavigator()
            val child = ChildRoute(99)
            nav.navigate(child)
            assertThat(nav.state.backStacks[TabA]?.last()).isEqualTo(child)
        }
    }

    // ── navigate() — tab reselect (ia-06) ────────────────────────────────────

    @Nested
    inner class TabReselect {

        @Test
        fun `should pop backStack to root when reselecting already-active tab`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.navigate(ChildRoute(2))
            nav.navigate(TabA) // reselect
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(1)
        }

        @Test
        fun `should keep the root entry after reselect`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.navigate(TabA)
            assertThat(nav.state.backStacks[TabA]?.first()).isEqualTo(TabA)
        }

        @Test
        fun `should not change topLevelRoute when reselecting active tab`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.navigate(TabA)
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `should be idempotent when reselecting a tab that is already at root`() {
            val nav = buildNavigator()
            nav.navigate(TabA) // already at root — must not crash or alter state
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(1)
        }

        @Test
        fun `should only pop the active tab when reselecting it from another tab`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            nav.navigate(ChildRoute(1))
            nav.navigate(ChildRoute(2))
            nav.navigate(TabB) // reselect TabB
            assertThat(nav.state.backStacks[TabB]?.size).isEqualTo(1)
            // TabA untouched
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(1)
        }
    }

    // ── goBack() — child pop ──────────────────────────────────────────────────

    @Nested
    inner class GoBackChildPop {

        @Test
        fun `should pop child route from current tab when goBack is called`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.goBack()
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(1)
        }

        @Test
        fun `should not change topLevelRoute when popping a child`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.goBack()
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `should pop only the most recent child when multiple children are stacked`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            nav.navigate(ChildRoute(2))
            nav.goBack()
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(2)
            assertThat(nav.state.backStacks[TabA]?.last()).isEqualTo(ChildRoute(1))
        }
    }

    // ── goBack() — return-value contract (drives double-tap-to-exit) ─────────

    @Nested
    inner class GoBackReturnContract {

        @Test
        fun `returns true when a child was popped`() {
            val nav = buildNavigator()
            nav.navigate(ChildRoute(1))
            assertThat(nav.goBack()).isTrue()
        }

        @Test
        fun `returns true when switching from a non-home tab to home`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            assertThat(nav.goBack()).isTrue()
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `returns false at the home tab root and leaves state unchanged`() {
            val nav = buildNavigator()
            assertThat(nav.goBack()).isFalse()
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
            assertThat(nav.state.backStacks[TabA]?.size).isEqualTo(1)
        }

        @Test
        fun `pops child before falling back to home`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            nav.navigate(ChildRoute(1)) // child on TabB
            assertThat(nav.goBack()).isTrue() // pops child
            assertThat(nav.state.topLevelRoute).isEqualTo(TabB)
            assertThat(nav.goBack()).isTrue() // TabB root → home
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
            assertThat(nav.goBack()).isFalse() // home root → exit signal
        }
    }

    // ── goBack() — tab fallback to start ─────────────────────────────────────

    @Nested
    inner class GoBackTabFallback {

        @Test
        fun `should fall back to start tab when at root of a non-start tab`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            nav.goBack()
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `should fall back to start tab from TabC`() {
            val nav = buildNavigator()
            nav.navigate(TabC)
            nav.goBack()
            assertThat(nav.state.topLevelRoute).isEqualTo(TabA)
        }

        @Test
        fun `should not pop backStack of the non-start tab when falling back`() {
            val nav = buildNavigator()
            nav.navigate(TabB)
            val sizeBeforeBack = nav.state.backStacks[TabB]?.size
            nav.goBack()
            assertThat(nav.state.backStacks[TabB]?.size).isEqualTo(sizeBeforeBack)
        }
    }

    // ── NavigationState.stacksInUse ──────────────────────────────────────────

    @Nested
    inner class StacksInUse {

        @Test
        fun `should return only startRoute when on start tab`() {
            val state = buildState()
            assertThat(state.stacksInUse).containsExactly(TabA)
        }

        @Test
        fun `should return startRoute and active tab when on a non-start tab`() {
            val state = buildState()
            state.topLevelRoute = TabB
            assertThat(state.stacksInUse).containsExactly(TabA, TabB).inOrder()
        }

        @Test
        fun `should return only startRoute after navigating back to it from non-start tab`() {
            val state = buildState()
            state.topLevelRoute = TabB
            state.topLevelRoute = TabA
            assertThat(state.stacksInUse).containsExactly(TabA)
        }
    }
}
