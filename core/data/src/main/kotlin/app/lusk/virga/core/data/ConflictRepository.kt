package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.rclone.RcloneEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Constants for [ConflictEntity.conflictType] / [Conflict.conflictType]. */
object ConflictType {
    const val BISYNC = "bisync"
    const val ONE_WAY = "one-way"
}

/** Choices the user makes for resolving a [Conflict]. */
enum class ConflictChoice { KEEP_VARIANT_1, KEEP_VARIANT_2, KEEP_BOTH }

/**
 * Detects rclone bisync conflicts on the destination side of a task and
 * persists them; carries out the user's resolution choice via rclone
 * move/delete operations.
 *
 * Detection heuristic: bisync's default `--conflict-suffix conflict` with
 * `--conflict-loser num` yields paired files named `<base>.conflict1` and
 * `<base>.conflict2`. We scan the destination for matches and group by base.
 */
@Singleton
class ConflictRepository @Inject constructor(
    private val conflictDao: ConflictDao,
    private val engine: RcloneEngine,
) {
    val unresolved: Flow<List<Conflict>> =
        conflictDao.observeUnresolved().map { rows -> rows.map { it.toDomain() } }

    /**
     * Walks the destination of [task] for conflict-suffixed files and records them.
     * [conflictType] is stored on each new [ConflictEntity]; defaults to [ConflictType.BISYNC].
     */
    suspend fun detectFor(task: SyncTask, conflictType: String = ConflictType.BISYNC): Result<Int> = runCatching {
        // Use a filter so rclone returns only conflict-suffixed files instead of
        // materialising the entire subtree.
        val entries = engine.listDir(
            remote = "${task.remoteName}:",
            path = task.remotePath,
            recurse = true,
            filters = listOf("+ *.conflict[0-9]*", "- *"),
        )
        val grouped = entries
            .asSequence()
            .filter { !it.isDir }
            .mapNotNull { item ->
                CONFLICT_REGEX.matchEntire(item.name)?.let { match ->
                    val basePath = item.path.removeSuffix(item.name) + match.groupValues[1]
                    Triple(basePath, match.groupValues[2].toInt(), item)
                }
            }
            .groupBy { it.first }
            .mapValues { (_, list) -> list.sortedBy { it.second } }
            .filter { it.value.size >= 2 }

        val conflicts = grouped.map { (basePath, variants) ->
            val v1 = variants[0].third
            val v2 = variants[1].third
            ConflictEntity(
                taskId = task.id,
                remoteName = task.remoteName,
                basePath = basePath,
                variant1Path = v1.path,
                variant2Path = v2.path,
                variant1Size = v1.size,
                variant2Size = v2.size,
                conflictType = conflictType,
            )
        }
        // Atomically drop previously-resolved conflicts and record the current set,
        // so a crash between the two can't (briefly or permanently) show zero
        // conflicts for this task when a re-detection just found new ones.
        conflictDao.pruneResolvedAndUpsert(task.id, conflicts)
        conflicts.size
    }

    /**
     * Applies [choice] to [conflict] and marks it resolved.
     *
     * - KEEP_VARIANT_1 / KEEP_VARIANT_2: move the chosen variant onto the base
     *   path and delete the other; the base path holds the winner.
     * - KEEP_BOTH: leaves the `.conflictN` files in place and just marks the
     *   conflict resolved.
     */
    suspend fun resolve(conflict: Conflict, choice: ConflictChoice): Result<Unit> {
        val remoteFs = "${conflict.remoteName}:"
        return runCatching {
            when (choice) {
                ConflictChoice.KEEP_VARIANT_1 -> {
                    promote(remoteFs, conflict.variant1Path, conflict.basePath, conflict.variant2Path)
                }
                ConflictChoice.KEEP_VARIANT_2 -> {
                    promote(remoteFs, conflict.variant2Path, conflict.basePath, conflict.variant1Path)
                }
                ConflictChoice.KEEP_BOTH -> {
                    // Nothing to do on rclone -- both variant files remain.
                }
            }
            conflictDao.markResolved(conflict.id)
        }
    }

    /**
     * Records an advisory one-way conflict for [task] with [differences] differing files.
     * Stores a single row with basePath = "<differences> file(s) differ" so the user sees
     * the count in ConflictsScreen. DETECTION-ONLY: does not affect sync outcome.
     */
    suspend fun recordOneWayAdvisory(task: SyncTask, differences: Int): Result<Unit> = runCatching {
        val entity = ConflictEntity(
            taskId = task.id,
            remoteName = task.remoteName,
            basePath = "$differences file(s) differ (advisory)",
            variant1Path = "",
            variant2Path = "",
            variant1Size = 0,
            variant2Size = 0,
            conflictType = ConflictType.ONE_WAY,
        )
        conflictDao.pruneResolvedAndUpsert(task.id, listOf(entity))
    }

    private suspend fun promote(remoteFs: String, winnerPath: String, basePath: String, loserPath: String) {
        // Idempotent across a partial-failure retry: if a prior attempt already moved the
        // winner onto basePath but died before deleting the loser, the winner variant is
        // gone and the base exists. Re-running moveFile would then fail forever (source
        // missing). Detect that case with one cheap listing of the parent dir and skip the
        // move. engine.moveFile / deleteFile throw VirgaError on failure; the enclosing
        // runCatching in resolve() turns that into a Result.failure for the UI.
        if (!moveAlreadyDone(remoteFs, winnerPath, basePath)) {
            engine.moveFile("$remoteFs$winnerPath", "$remoteFs$basePath")
        }
        // Best-effort: a failed loser deletion still marks the conflict resolved. The lone
        // leftover .conflictN sits below the >=2-variant detection threshold, so it can't
        // re-trigger this conflict; swallowing the failure avoids a permanently stuck row.
        runCatching { engine.deleteFile(remoteFs, loserPath) }
    }

    /** True when the winner variant is absent and the base already exists — i.e. a prior
     *  attempt's move completed. Uses one listing of the parent dir to stay cheap.
     *  Paths here are remote-root-relative (matching resolve()'s "$remoteFs$path" usage);
     *  operations/list returns names relative to the listed dir, so compare on leaf names. */
    private suspend fun moveAlreadyDone(remoteFs: String, winnerPath: String, basePath: String): Boolean {
        val parent = basePath.substringBeforeLast('/', missingDelimiterValue = "")
        val leaves = engine.listDir(remote = remoteFs, path = parent, recurse = false)
            .filter { !it.isDir }
            .map { it.name }
            .toSet()
        val winnerLeaf = winnerPath.substringAfterLast('/')
        val baseLeaf = basePath.substringAfterLast('/')
        return winnerLeaf !in leaves && baseLeaf in leaves
    }

    private companion object {
        // e.g. "report.txt.conflict1" -> base "report.txt", number 1
        val CONFLICT_REGEX = Regex("""^(.+)\.conflict(\d+)$""")
    }
}
