package app.lusk.virga.feature.remotes

import app.lusk.virga.core.designsystem.back.OverlayBackRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [OverlayBackRegistry] — the stack the nav host consults so an
 * open in-window overlay (VirgaBottomSheet) claims Back. Lives in :feature:remotes
 * (which has the test harness); :core:designsystem ships no test source set.
 */
class OverlayBackRegistryTest {

    @Test fun `dismissTop is false and a no-op when no overlay is registered`() {
        assertThat(OverlayBackRegistry().dismissTop()).isFalse()
    }

    @Test fun `hasOverlay reflects registration`() {
        val registry = OverlayBackRegistry()
        assertThat(registry.hasOverlay).isFalse()
        val entry = { }
        registry.register(entry)
        assertThat(registry.hasOverlay).isTrue()
        registry.unregister(entry)
        assertThat(registry.hasOverlay).isFalse()
    }

    @Test fun `dismissTop invokes the most-recently-registered overlay (LIFO) and returns true`() {
        val registry = OverlayBackRegistry()
        val calls = mutableListOf<String>()
        val first = { calls += "first" }
        val second = { calls += "second" }
        registry.register(first)
        registry.register(second)

        assertThat(registry.dismissTop()).isTrue()
        // LIFO: the top (most recent) overlay closes first.
        assertThat(calls).containsExactly("second")
    }

    @Test fun `unregister removes a specific entry without disturbing others`() {
        val registry = OverlayBackRegistry()
        val calls = mutableListOf<String>()
        val first = { calls += "first" }
        val second = { calls += "second" }
        registry.register(first)
        registry.register(second)
        registry.unregister(second)

        assertThat(registry.dismissTop()).isTrue()
        assertThat(calls).containsExactly("first")
    }
}
