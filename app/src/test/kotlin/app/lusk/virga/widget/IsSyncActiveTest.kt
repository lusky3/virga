package app.lusk.virga.widget

import androidx.work.WorkInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [isSyncActive] — the pure predicate behind the Quick Settings
 * tile's "syncing" subtitle. Extracted to a top-level `internal` function so it can
 * be exercised directly, without the [SyncTileService] framework lifecycle or a live
 * WorkManager (which this project's unit tests can't stand up; see ReadDeepLinkTest).
 */
class IsSyncActiveTest {

    private fun workInfo(state: WorkInfo.State): WorkInfo =
        mockk(relaxed = true) { every { this@mockk.state } returns state }

    @Test
    fun `empty list is not active`() {
        assertThat(isSyncActive(emptyList())).isFalse()
    }

    @Test
    fun `a RUNNING work item is active`() {
        assertThat(isSyncActive(listOf(workInfo(WorkInfo.State.RUNNING)))).isTrue()
    }

    @Test
    fun `an ENQUEUED work item is active`() {
        assertThat(isSyncActive(listOf(workInfo(WorkInfo.State.ENQUEUED)))).isTrue()
    }

    @Test
    fun `only terminal-or-blocked states are not active`() {
        val inactive = listOf(
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            WorkInfo.State.BLOCKED,
        ).map { workInfo(it) }
        assertThat(isSyncActive(inactive)).isFalse()
    }

    @Test
    fun `a single active item among terminal ones still counts as active`() {
        val mixed = listOf(
            workInfo(WorkInfo.State.SUCCEEDED),
            workInfo(WorkInfo.State.RUNNING),
            workInfo(WorkInfo.State.CANCELLED),
        )
        assertThat(isSyncActive(mixed)).isTrue()
    }
}
