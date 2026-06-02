package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask

/**
 * Overall sync state shown on the Home hero (BRAND §10 vocabulary).
 *
 * Priority: Running > NeedsAttention > UpToDate > Idle. All variants are pure
 * data — no Android types — so they are unit-testable without instrumentation.
 */
sealed interface HomeStatus {
    /** At least one task has a RUNNING or QUEUED run right now. */
    data object Running : HomeStatus

    /** No running tasks, but [count] tasks have a FAILED latest run. */
    data class NeedsAttention(val count: Int) : HomeStatus

    /**
     * All tasks are healthy. [lastBackupEpochMs] is the max [SyncRun.endedAtEpochMs]
     * across all tasks whose latest run succeeded; null if no successful run exists yet.
     */
    data class UpToDate(val lastBackupEpochMs: Long?) : HomeStatus

    /** No tasks yet, or tasks exist but none has a successful run. */
    data object Idle : HomeStatus
}

/**
 * Pure derivation of [HomeStatus] from tasks + their latest runs (no Android
 * types). Priority: Running > NeedsAttention > UpToDate > Idle.
 *
 * "Everything's backed up" (UpToDate) is only claimed when an actual SUCCESS run
 * exists — a task whose only run was cancelled/idle (or failed-then-cleared) is
 * Idle ("Ready to back up"), never a false all-clear (BRAND §1/§2: honest state).
 */
internal fun deriveHomeStatus(
    tasks: List<SyncTask>,
    latestRuns: Map<Long, SyncRun>,
): HomeStatus {
    if (tasks.isEmpty()) return HomeStatus.Idle
    val runningCount = tasks.count { t ->
        val s = latestRuns[t.id]?.status
        s == SyncStatus.RUNNING || s == SyncStatus.QUEUED
    }
    if (runningCount > 0) return HomeStatus.Running
    val failedCount = tasks.count { latestRuns[it.id]?.status == SyncStatus.FAILED }
    if (failedCount > 0) return HomeStatus.NeedsAttention(failedCount)
    val lastSuccess = latestRuns.values
        .filter { it.status == SyncStatus.SUCCESS }
        .maxOfOrNull { it.endedAtEpochMs ?: it.startedAtEpochMs }
    return if (lastSuccess != null) HomeStatus.UpToDate(lastSuccess) else HomeStatus.Idle
}
