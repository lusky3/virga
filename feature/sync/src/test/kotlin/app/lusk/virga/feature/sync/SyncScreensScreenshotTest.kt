package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import app.lusk.virga.sync.DryRunUseCase
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden-image tests for the remaining major feature:sync screens
 * (Home, the task list, conflicts, the per-task summary). Mirrors the harness in
 * [SyncTaskEditScreenshotTest] exactly: Robolectric on the JVM, NATIVE graphics,
 * a fixed device qualifier, and goldens committed under `src/test/snapshots/`.
 *
 * All representative states are pinned to fixed data so captures are
 * deterministic — in particular no screen here renders a relative timestamp
 * ("3 minutes ago"), which would drift run-to-run. Screens are wrapped in
 * [VirgaTheme] (not bare [MaterialTheme]) because the Home hero and live panels
 * read `LocalVirgaColors`, which throws without the Virga theme provider.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:sync:recordRoborazziDebug
 * Verify against goldens (CI default; fails on visual diff):
 *   ./gradlew :feature:sync:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncScreensScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
        ),
    )

    // --- Sample data (all timestamps fixed; nothing relative-time formatted) ----

    private val task1 = SyncTask(
        id = 1L,
        name = "DCIM backup",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup/DCIM",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = 60,
    )
    private val task2 = SyncTask(
        id = 2L,
        name = "Documents mirror",
        sourcePath = "/storage/emulated/0/Documents",
        remoteName = "box",
        remotePath = "/Docs",
        direction = SyncDirection.BISYNC,
        intervalMinutes = null,
        deleteExtraneous = true,
        enabled = false,
    )

    private val conflict1 = Conflict(
        id = 1L,
        taskId = 2L,
        remoteName = "box",
        basePath = "Docs/report.txt",
        variant1Path = "Docs/report.txt",
        variant1Size = 12_345L,
        variant2Path = "Docs/report.conflict-1.txt",
        variant2Size = 12_400L,
        detectedAtEpochMs = 0L,
    )
    private val conflict2 = Conflict(
        id = 2L,
        taskId = 2L,
        remoteName = "box",
        basePath = "Docs/budget.xlsx",
        variant1Path = "Docs/budget.xlsx",
        variant1Size = 98_765L,
        variant2Path = "Docs/budget.conflict-1.xlsx",
        variant2Size = 99_000L,
        detectedAtEpochMs = 0L,
    )

    // --- Home --------------------------------------------------------------------

    private fun homeViewModel(
        tasksData: List<SyncTask>,
        runsData: List<SyncRun>,
        statsData: LifetimeStats,
        remotesData: List<Remote>,
    ): HomeViewModel {
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) { every { tasks } returns flowOf(tasksData) }
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) { every { recentRuns } returns flowOf(runsData) }
        val statsRepo: StatsRepository = mockk(relaxed = true) { every { stats } returns flowOf(statsData) }
        val remoteRepo: RemoteRepository = mockk(relaxed = true) { every { remotes } returns flowOf(remotesData) }
        val scheduler: SyncScheduler = mockk(relaxed = true)
        return HomeViewModel(taskRepo, historyRepo, statsRepo, remoteRepo, scheduler)
    }

    @Test
    fun homeScreen_needsAttention() {
        // NeedsAttention (not UpToDate) keeps the hero deterministic — UpToDate
        // renders a relative "last backup" timestamp.
        val failedRun = SyncRun(
            id = 10L, taskId = 1L, startedAtEpochMs = 0L, endedAtEpochMs = 0L,
            status = SyncStatus.FAILED, filesTransferred = 0, bytesTransferred = 0, errorCount = 3,
        )
        val viewModel = homeViewModel(
            tasksData = listOf(task1, task2),
            runsData = listOf(failedRun),
            statsData = LifetimeStats(totalRuns = 42, totalBytesTransferred = 5_368_709_120L),
            remotesData = listOf(Remote("gdrive", "drive"), Remote("box", "box")),
        )
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onOpenStats = {},
                        onAddTask = {},
                        onOpenSync = {},
                        onOpenRemotes = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun homeScreen_idleEmpty() {
        // No tasks → Idle hero + "Create sync task" primary action.
        val viewModel = homeViewModel(
            tasksData = emptyList(),
            runsData = emptyList(),
            statsData = LifetimeStats(),
            remotesData = emptyList(),
        )
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onOpenStats = {},
                        onAddTask = {},
                        onOpenSync = {},
                        onOpenRemotes = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    // --- Sync task list ----------------------------------------------------------

    private fun syncTasksViewModel(tasks: List<SyncTask>, conflicts: List<Conflict>): SyncTasksViewModel {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) { every { this@mockk.tasks } returns flowOf(tasks) }
        // Leave latestRuns empty (no recent runs) so cards omit the relative-time
        // "last run" line — keeps the populated list deterministic.
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) { every { recentRuns } returns flowOf(emptyList()) }
        val conflictRepo: ConflictRepository = mockk(relaxed = true) { every { unresolved } returns flowOf(conflicts) }
        val statsRepo: StatsRepository = mockk(relaxed = true) { every { stats } returns flowOf(LifetimeStats()) }
        val scheduler: SyncScheduler = mockk(relaxed = true)
        val monitor: SyncProgressMonitor = mockk(relaxed = true) { every { progressFor(any()) } returns flowOf(null) }
        return SyncTasksViewModel(context, taskRepo, historyRepo, conflictRepo, statsRepo, scheduler, monitor)
    }

    @Test
    fun syncTasksScreen_populated() {
        val viewModel = syncTasksViewModel(tasks = listOf(task1, task2), conflicts = listOf(conflict1))
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTasksScreen(
                        onAddTask = {},
                        onOpenTask = {},
                        onEditTask = {},
                        onOpenHistory = {},
                        onOpenConflicts = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun syncTasksScreen_empty() {
        val viewModel = syncTasksViewModel(tasks = emptyList(), conflicts = emptyList())
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTasksScreen(
                        onAddTask = {},
                        onOpenTask = {},
                        onEditTask = {},
                        onOpenHistory = {},
                        onOpenConflicts = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    // --- Conflicts ---------------------------------------------------------------

    private fun conflictsViewModel(conflicts: List<Conflict>): ConflictsViewModel {
        val repo: ConflictRepository = mockk(relaxed = true) { every { unresolved } returns flowOf(conflicts) }
        return ConflictsViewModel(repo)
    }

    @Test
    fun conflictsScreen_populated() {
        // detectedAtEpochMs=0L formats through DateFormat (absolute, fixed locale/TZ
        // under Robolectric) — deterministic, unlike a relative span.
        val viewModel = conflictsViewModel(listOf(conflict1, conflict2))
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ConflictsScreen(onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun conflictsScreen_empty() {
        val viewModel = conflictsViewModel(emptyList())
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ConflictsScreen(onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    // --- Per-task summary --------------------------------------------------------

    @Test
    fun syncTaskSummaryScreen_noRuns() {
        // runsForTask = empty so the "Recent runs" section shows the "no runs yet"
        // copy instead of relative-time RunRows. previewAvailable() is false
        // (relaxed DryRunUseCase) and liveProgress is null, so the layout is stable.
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) { every { task(any()) } returns flowOf(task1) }
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) { every { runsForTask(any()) } returns flowOf(emptyList()) }
        val scheduler: SyncScheduler = mockk(relaxed = true)
        val monitor: SyncProgressMonitor = mockk(relaxed = true) { every { progressFor(any()) } returns flowOf(null) }
        val dryRun: DryRunUseCase = mockk(relaxed = true)
        val viewModel = SyncTaskSummaryViewModel(taskRepo, historyRepo, scheduler, monitor, dryRun)
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskSummaryScreen(
                        taskId = 1L,
                        onBack = {},
                        onEdit = {},
                        onOpenRun = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
