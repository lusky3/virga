package app.lusk.virga.core.common.notification

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NotificationDeepLinks] constants introduced in D6 (0.3.0).
 *
 * These are pure-JVM assertions — no Android framework dependency. The purpose
 * is to pin the string values so a typo in the constant is caught immediately
 * rather than discovered at runtime when the deep link silently fails to route.
 */
class NotificationDeepLinksTest {

    @Test
    fun `should expose ROUTE_ADD_REMOTE constant with expected value`() {
        assertThat(NotificationDeepLinks.ROUTE_ADD_REMOTE).isEqualTo("add_remote")
    }

    @Test
    fun `should expose EXTRA_OPEN_ROUTE with expected key`() {
        assertThat(NotificationDeepLinks.EXTRA_OPEN_ROUTE)
            .isEqualTo("app.lusk.virga.OPEN_ROUTE")
    }

    @Test
    fun `should expose ROUTE_SETTINGS with expected value`() {
        assertThat(NotificationDeepLinks.ROUTE_SETTINGS).isEqualTo("settings")
    }

    @Test
    fun `should expose ROUTE_TASK with expected value`() {
        assertThat(NotificationDeepLinks.ROUTE_TASK).isEqualTo("task")
    }

    @Test
    fun `ROUTE_ADD_REMOTE should be distinct from other route constants`() {
        assertThat(NotificationDeepLinks.ROUTE_ADD_REMOTE)
            .isNotEqualTo(NotificationDeepLinks.ROUTE_SETTINGS)
        assertThat(NotificationDeepLinks.ROUTE_ADD_REMOTE)
            .isNotEqualTo(NotificationDeepLinks.ROUTE_TASK)
    }
}
