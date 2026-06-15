package app.lusk.virga.feature.sync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import app.lusk.virga.sync.CheckResult
import app.lusk.virga.sync.CheckUseCase
import app.lusk.virga.sync.DryRunResult
import app.lusk.virga.sync.DryRunUseCase
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Coverage tests for the Verify (check) result AlertDialog in [SyncTaskSummaryScreen].
 *
 * AlertDialog renders into a separate popup window. Under Robolectric the popup
 * never becomes idle (cursor-blink / idling-resource loop), so waitForIdle() hangs
 * if called on the popup. The adopted pattern (mirroring ConfigTransferDialogsTest):
 *
 *  1. Set content with the PRODUCTION screen using a real ViewModel wired to mocked
 *     repositories. waitForIdle() settles the MAIN window only.
 *  2. Emit the state that opens the dialog (by calling the VM action directly on the
 *     UI thread, having pre-stubbed the suspend use-case to return immediately).
 *  3. Pump frames inside runOnUiThread to drive the popup's initial composition —
 *     this executes the dialog body code paths that codecov needs.
 *  4. Dismiss via the VM (not a popup node click) so teardown's idle-check passes.
 *
 * This exercises the PRODUCTION composable code for coverage without asserting on
 * popup nodes (which would hang under Robolectric's popup-idle constraint).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncTaskSummaryDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val task = SyncTask(
        id = 1L,
        name = "DCIM backup",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup/DCIM",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    /**
     * Builds a ViewModel wired to stubbed repositories. [checkResult] is returned by
     * [CheckUseCase.verify] so the dialog can be opened by calling [vm.verifyChanges].
     * [dryRunResult] likewise opens the preview dialog.
     */
    private fun buildViewModel(
        checkResult: CheckResult = CheckResult(differences = 0),
        dryRunResult: DryRunResult = DryRunResult(filesToTransfer = 0, bytesToTransfer = 0, errors = 0),
    ): SyncTaskSummaryViewModel {
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) {
            every { task(any()) } returns flowOf(task)
        }
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) {
            every { runsForTask(any()) } returns flowOf(emptyList())
        }
        val scheduler: SyncScheduler = mockk(relaxed = true)
        val monitor: SyncProgressMonitor = mockk(relaxed = true) {
            every { progressFor(any()) } returns flowOf(null)
        }
        val checkUseCase: CheckUseCase = mockk(relaxed = true) {
            every { isAvailableFor(any()) } returns true
            coEvery { verify(any()) } returns checkResult
        }
        val dryRunUseCase: DryRunUseCase = mockk(relaxed = true) {
            every { isAvailableFor(any()) } returns true
            coEvery { preview(any()) } returns dryRunResult
        }
        return SyncTaskSummaryViewModel(taskRepo, historyRepo, scheduler, monitor, dryRunUseCase, checkUseCase)
    }

    /**
     * Renders [SyncTaskSummaryScreen] with the given ViewModel, waits for the main
     * window to idle, then calls [openDialog] (which must run on the UI thread) and
     * pumps frames to drive popup composition.
     */
    private fun renderAndOpenDialog(
        vm: SyncTaskSummaryViewModel,
        openDialog: () -> Unit,
    ) {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskSummaryScreen(
                        taskId = 1L,
                        onBack = {},
                        onEdit = {},
                        onOpenRun = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Open the dialog and pump frames to drive the popup's composition.
        composeRule.runOnUiThread {
            openDialog()
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        }
    }

    // -------------------------------------------------------------------------
    // Verify button availability — exercised in the main window (no popup)
    // -------------------------------------------------------------------------

    @Test
    fun summaryScreen_verifyButtonShown_whenVerifyAvailable() {
        // checkUseCase.isAvailableFor returns true; the Verify button should render.
        val vm = buildViewModel()
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskSummaryScreen(
                        taskId = 1L,
                        onBack = {},
                        onEdit = {},
                        onOpenRun = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // The Verify button text is rendered in the main window — no popup involved.
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.sync_verify_action),
        ).assertExists()
    }

    @Test
    fun summaryScreen_verifyButtonNotShown_whenVerifyUnavailable() {
        // SAF source → isAvailableFor returns false; button must be absent.
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) {
            every { task(any()) } returns flowOf(
                task.copy(sourcePath = "content://tree/primary:DCIM"),
            )
        }
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) {
            every { runsForTask(any()) } returns flowOf(emptyList())
        }
        val checkUseCase: CheckUseCase = mockk(relaxed = true) {
            every { isAvailableFor(any()) } returns false
        }
        val dryRunUseCase: DryRunUseCase = mockk(relaxed = true) {
            every { isAvailableFor(any()) } returns false
        }
        val vm = SyncTaskSummaryViewModel(
            taskRepo,
            historyRepo,
            mockk(relaxed = true),
            mockk(relaxed = true) { every { progressFor(any()) } returns flowOf(null) },
            dryRunUseCase,
            checkUseCase,
        )
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskSummaryScreen(
                        taskId = 1L,
                        onBack = {},
                        onEdit = {},
                        onOpenRun = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.sync_verify_action),
        ).assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // Verify result dialog — 4 states: running, in-sync, differs, error
    // -------------------------------------------------------------------------

    /**
     * Verify running state: checkState.running=true renders "Verifying…" text
     * inside the Verify button in the MAIN window. No popup is involved — the
     * button label changes on the main window when a verify is in flight but
     * the result has not yet been set.
     */
    @Test
    fun summaryScreen_verifyButtonLabel_showsRunningText_whenVerifyInFlight() {
        // We pre-wire the check use case to never complete (suspend forever) so
        // running=true is the steady state while the screen is being examined.
        val checkUseCase: CheckUseCase = mockk(relaxed = true) {
            every { isAvailableFor(any()) } returns true
            coEvery { verify(any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        }
        val vm = SyncTaskSummaryViewModel(
            mockk(relaxed = true) { every { task(any()) } returns flowOf(task) },
            mockk(relaxed = true) { every { runsForTask(any()) } returns flowOf(emptyList()) },
            mockk(relaxed = true),
            mockk(relaxed = true) { every { progressFor(any()) } returns flowOf(null) },
            mockk(relaxed = true) { every { isAvailableFor(any()) } returns true },
            checkUseCase,
        )
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskSummaryScreen(
                        taskId = 1L,
                        onBack = {},
                        onEdit = {},
                        onOpenRun = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Kick off a verify — the VM sets running=true immediately.
        composeRule.runOnUiThread { vm.verifyChanges() }
        composeRule.waitForIdle()

        // "Verifying…" is rendered inside the Verify button in the MAIN window.
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.sync_verify_running),
        ).assertExists()
    }

    /**
     * In-sync result: checkState.result with differences==0 → dialog body executes
     * the [sync_verify_in_sync] branch. Pumping frames drives the popup composition.
     */
    @Test
    fun verifyDialog_inSync_composesDialogBody() {
        val vm = buildViewModel(checkResult = CheckResult(differences = 0))

        // Load the task first so verifyChanges has a task to work with.
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._checkStateForTest(CheckResult(differences = 0)) }

        // Dismiss via the VM so teardown's idle-check passes.
        composeRule.runOnUiThread { vm.dismissVerify() }
    }

    /**
     * Differs result (1 file): checkState.result with differences==1 → pluralStringResource
     * branch for differences. Pumping frames exercises the plural string path.
     */
    @Test
    fun verifyDialog_differsSingular_composesDialogBody() {
        val vm = buildViewModel(checkResult = CheckResult(differences = 1))
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._checkStateForTest(CheckResult(differences = 1)) }
        composeRule.runOnUiThread { vm.dismissVerify() }
    }

    /**
     * Differs result (3 files): plural path with count > 1.
     */
    @Test
    fun verifyDialog_differsPlural_composesDialogBody() {
        val vm = buildViewModel(checkResult = CheckResult(differences = 3))
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._checkStateForTest(CheckResult(differences = 3)) }
        composeRule.runOnUiThread { vm.dismissVerify() }
    }

    /**
     * Error result: checkState.result with non-null error → error message branch.
     */
    @Test
    fun verifyDialog_error_composesErrorBranch() {
        val errorResult = CheckResult(differences = 0, error = "remote unreachable")
        val vm = buildViewModel(checkResult = errorResult)
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._checkStateForTest(errorResult) }
        composeRule.runOnUiThread { vm.dismissVerify() }
    }

    // -------------------------------------------------------------------------
    // Preview result dialog — for completeness (WS2.3 dialog body coverage)
    // -------------------------------------------------------------------------

    @Test
    fun previewDialog_noError_composesSuccessBody() {
        val result = DryRunResult(filesToTransfer = 5, bytesToTransfer = 1024L, errors = 0)
        val vm = buildViewModel(dryRunResult = result)
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._dryRunResultForTest(result) }
        composeRule.runOnUiThread { vm.dismissPreview() }
    }

    @Test
    fun previewDialog_withError_composesErrorBody() {
        val result = DryRunResult(filesToTransfer = 0, bytesToTransfer = 0, errors = 1, error = "network error")
        val vm = buildViewModel(dryRunResult = result)
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._dryRunResultForTest(result) }
        composeRule.runOnUiThread { vm.dismissPreview() }
    }

    @Test
    fun previewDialog_withDeletions_composesDeletionWarning() {
        val result = DryRunResult(filesToTransfer = 2, bytesToTransfer = 512L, errors = 0, filesToDelete = 3)
        val vm = buildViewModel(dryRunResult = result)
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._dryRunResultForTest(result) }
        composeRule.runOnUiThread { vm.dismissPreview() }
    }

    @Test
    fun previewDialog_mirrorTask_composesMirrorWarning() {
        val result = DryRunResult(filesToTransfer = 1, bytesToTransfer = 256L, errors = 0, mirrors = true)
        val vm = buildViewModel(dryRunResult = result)
        composeRule.runOnUiThread { vm.load(1L) }
        renderAndOpenDialog(vm) { vm._dryRunResultForTest(result) }
        composeRule.runOnUiThread { vm.dismissPreview() }
    }
}

// ---------------------------------------------------------------------------
// Test-only seams on the ViewModel
//
// These extensions inject state directly into the private MutableStateFlows
// so that Compose UI tests can open dialogs without running full coroutines.
// They use reflection to access private fields — minimal, call-site isolated,
// and flagged here so reviewers can audit.
// ---------------------------------------------------------------------------

/**
 * Directly injects a [CheckResult] into [SyncTaskSummaryViewModel._checkState] so
 * that dialog tests can open the verify result dialog without waiting for a real
 * coroutine to complete.
 */
internal fun SyncTaskSummaryViewModel._checkStateForTest(result: CheckResult) {
    val field = SyncTaskSummaryViewModel::class.java.getDeclaredField("_checkState")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<SyncTaskSummaryViewModel.CheckUiState>
    flow.value = SyncTaskSummaryViewModel.CheckUiState(running = false, result = result)
}

/**
 * Directly injects a [DryRunResult] into [SyncTaskSummaryViewModel._dryRun] so
 * that dialog tests can open the preview dialog without waiting for a real
 * coroutine to complete.
 */
internal fun SyncTaskSummaryViewModel._dryRunResultForTest(result: DryRunResult) {
    val field = SyncTaskSummaryViewModel::class.java.getDeclaredField("_dryRun")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<SyncTaskSummaryViewModel.DryRunUiState>
    flow.value = SyncTaskSummaryViewModel.DryRunUiState(running = false, result = result)
}
