package app.lusk.virga.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lusk.virga.core.common.model.SyncProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads live [SyncProgress] for a task off WorkManager (WS1.1). A task can run
 * under two unique-work names — the scheduled one (`sync_task_<id>`) and the
 * manual one (`sync_task_<id>_now`) — so both are observed and the RUNNING one
 * wins. Emits null whenever nothing is running for the task.
 *
 * This is the clean, process-death-surviving mechanism (the alternative — an
 * in-memory bus — is lost when the worker's process dies).
 */
@Singleton
class SyncProgressMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun progressFor(taskId: Long): Flow<SyncProgress?> {
        val base = SyncWorker.UNIQUE_PREFIX + taskId
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(base),
            workManager.getWorkInfosForUniqueWorkFlow(base + "_now"),
        ) { scheduled, now -> scheduled + now }
            .map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?.let { SyncProgressData.decode(it.progress) }
            }
            .distinctUntilChanged()
    }
}
