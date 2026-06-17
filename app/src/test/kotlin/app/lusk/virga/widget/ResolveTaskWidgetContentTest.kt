package app.lusk.virga.widget

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [resolveTaskWidgetContent] and [formatTaskStatusLine] — the two
 * pure display-logic helpers in TaskWidget.kt that are fully unit-testable without
 * Glance, WorkManager, or Hilt (see the exclusion note in ReadDeepLinkTest).
 */
class ResolveTaskWidgetContentTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun task(
        id: Long = 1L,
        name: String = "My Backup",
        direction: SyncDirection = SyncDirection.UPLOAD,
        enabled: Boolean = true,
    ) = SyncTask(
        id = id,
        name = name,
        sourcePath = "/local/path",
        remoteName = "gdrive",
        remotePath = "/remote/path",
        direction = direction,
        intervalMinutes = null,
        enabled = enabled,
    )

    // ── resolveTaskWidgetContent: Unconfigured branch ─────────────────────────

    @Test
    fun `should return Unconfigured when boundTaskId is NO_TASK_ID and task is null`() {
        val result = resolveTaskWidgetContent(boundTaskId = NO_TASK_ID, task = null)
        assertThat(result).isEqualTo(TaskWidgetContent.Unconfigured)
    }

    @Test
    fun `should return Unconfigured when boundTaskId is NO_TASK_ID even with a non-null task`() {
        // task argument is ignored entirely when boundTaskId == NO_TASK_ID
        val result = resolveTaskWidgetContent(boundTaskId = NO_TASK_ID, task = task())
        assertThat(result).isEqualTo(TaskWidgetContent.Unconfigured)
    }

    // ── resolveTaskWidgetContent: TaskRemoved branch ──────────────────────────

    @Test
    fun `should return TaskRemoved when boundTaskId is valid but task is null`() {
        val result = resolveTaskWidgetContent(boundTaskId = 42L, task = null)
        assertThat(result).isEqualTo(TaskWidgetContent.TaskRemoved)
    }

    @Test
    fun `should return TaskRemoved when boundTaskId is 1 and task is null`() {
        val result = resolveTaskWidgetContent(boundTaskId = 1L, task = null)
        assertThat(result).isEqualTo(TaskWidgetContent.TaskRemoved)
    }

    // ── resolveTaskWidgetContent: Ready branch ────────────────────────────────

    @Test
    fun `should return Ready with the task id when task exists`() {
        val t = task(id = 7L)
        val result = resolveTaskWidgetContent(boundTaskId = 7L, task = t)
        assertThat(result).isInstanceOf(TaskWidgetContent.Ready::class.java)
        assertThat((result as TaskWidgetContent.Ready).taskId).isEqualTo(7L)
    }

    @Test
    fun `should return Ready with the task name when task exists`() {
        val t = task(name = "Photos Upload")
        val result = resolveTaskWidgetContent(boundTaskId = t.id, task = t)
        assertThat((result as TaskWidgetContent.Ready).name).isEqualTo("Photos Upload")
    }

    @Test
    fun `should return Ready whose statusLine matches formatTaskStatusLine`() {
        val t = task(direction = SyncDirection.BISYNC, enabled = false)
        val result = resolveTaskWidgetContent(boundTaskId = t.id, task = t)
        val expected = formatTaskStatusLine(t)
        assertThat((result as TaskWidgetContent.Ready).statusLine).isEqualTo(expected)
    }

    // ── formatTaskStatusLine: enabled flag ────────────────────────────────────

    @Test
    fun `should prefix the status line with Enabled when task is enabled`() {
        val line = formatTaskStatusLine(task(enabled = true, direction = SyncDirection.UPLOAD))
        assertThat(line).startsWith("Enabled")
    }

    @Test
    fun `should prefix the status line with Disabled when task is not enabled`() {
        val line = formatTaskStatusLine(task(enabled = false, direction = SyncDirection.UPLOAD))
        assertThat(line).startsWith("Disabled")
    }

    // ── formatTaskStatusLine: direction capitalisation ────────────────────────

    @Test
    fun `should format UPLOAD direction as Upload with capitalised first letter`() {
        val line = formatTaskStatusLine(task(direction = SyncDirection.UPLOAD))
        assertThat(line).isEqualTo("Enabled · Upload")
    }

    @Test
    fun `should format DOWNLOAD direction as Download with capitalised first letter`() {
        val line = formatTaskStatusLine(task(direction = SyncDirection.DOWNLOAD))
        assertThat(line).isEqualTo("Enabled · Download")
    }

    @Test
    fun `should format BISYNC direction as Bisync with capitalised first letter`() {
        val line = formatTaskStatusLine(task(direction = SyncDirection.BISYNC))
        assertThat(line).isEqualTo("Enabled · Bisync")
    }

    @Test
    fun `should produce Disabled dot Bisync for a disabled bisync task`() {
        val line = formatTaskStatusLine(task(enabled = false, direction = SyncDirection.BISYNC))
        assertThat(line).isEqualTo("Disabled · Bisync")
    }

    // ── sentinel value sanity ─────────────────────────────────────────────────

    @Test
    fun `NO_TASK_ID should equal minus one`() {
        assertThat(NO_TASK_ID).isEqualTo(-1L)
    }
}
