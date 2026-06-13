package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-function coverage for the per-task notification id derivation (audit
 * sync-M4). Concurrent syncs ("Sync all") must each get their own progress +
 * result notification; the bases are far apart so a foreground id never aliases
 * a result id for any plausible Room task id.
 */
class SyncNotificationsTest {

    @Test fun `foreground ids are unique per task`() {
        assertThat(SyncNotifications.foregroundId(1))
            .isNotEqualTo(SyncNotifications.foregroundId(2))
    }

    @Test fun `result ids are unique per task`() {
        assertThat(SyncNotifications.resultId(1))
            .isNotEqualTo(SyncNotifications.resultId(2))
    }

    @Test fun `foreground and result ids never collide for the same task`() {
        listOf(1L, 2L, 42L, 1000L, 99_999L).forEach { id ->
            assertThat(SyncNotifications.foregroundId(id))
                .isNotEqualTo(SyncNotifications.resultId(id))
        }
    }

    @Test fun `foreground id of one task never aliases the result id of another`() {
        // Collision would need a taskId difference >= 100000, which Room's
        // autoincrement won't reach in practice.
        val ids = listOf(0L, 1L, 50L, 500L, 5_000L, 50_000L, 99_999L)
        val foreground = ids.map { SyncNotifications.foregroundId(it) }.toSet()
        val result = ids.map { SyncNotifications.resultId(it) }.toSet()
        assertThat(foreground.intersect(result)).isEmpty()
    }
}
