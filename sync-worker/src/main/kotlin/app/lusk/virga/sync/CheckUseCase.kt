package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.rclone.RcloneEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Outcome of a check (verify) run: how many files differ or are missing compared
 * to the destination. No data is transferred.
 */
data class CheckResult(
    /**
     * Files that differ or are missing between source and destination. Sourced from
     * rclone's `errors` stat for the check job (rclone counts each differing/missing
     * file as an error in check mode). No reliable "total compared" count is exposed
     * by `core/stats` for a check, so only this differing count is surfaced.
     */
    val differences: Int,
    /** Non-null when the check itself failed (e.g. remote unreachable). */
    val error: String? = null,
)

/**
 * Runs rclone check (compare source vs destination without transferring) and
 * reports how many files differ or are missing.
 *
 * SAF (`content://`) sources are excluded — rclone cannot read them directly
 * without the staging copy that a real run performs (same constraint as
 * [DryRunUseCase]). Callers must gate with [isAvailableFor].
 */
class CheckUseCase @Inject constructor(
    private val executor: SyncExecutor,
    private val engine: RcloneEngine,
) {
    fun isAvailableFor(task: SyncTask): Boolean = !task.sourcePath.startsWith("content://")

    suspend fun verify(task: SyncTask): CheckResult {
        var last: SyncProgress? = null
        var error: String? = null
        var leased = false
        try {
            engine.acquireDaemon()
            leased = true
            executor.runCheck(task)
                .catch { error = it.message ?: "Verify failed" }
                .collect { last = it }
        } finally {
            if (leased) withContext(NonCancellable) { runCatching { engine.releaseDaemon() } }
        }
        return CheckResult(
            differences = last?.errors ?: 0,
            error = error,
        )
    }
}
