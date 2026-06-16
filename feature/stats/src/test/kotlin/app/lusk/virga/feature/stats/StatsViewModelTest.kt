package app.lusk.virga.feature.stats

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteQuotaState
import app.lusk.virga.core.common.model.RemoteStat
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.TaskStat
import app.lusk.virga.core.common.model.TrendDay
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncTaskRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private fun repo(
        lifetime: LifetimeStats = LifetimeStats(),
        remotes: List<RemoteStat> = emptyList(),
        tasks: List<TaskStat> = emptyList(),
        trend: List<TrendDay> = emptyList(),
    ): StatsRepository = mockk(relaxed = true) {
        every { stats } returns flowOf(lifetime)
        every { remoteStats } returns flowOf(remotes)
        every { taskStats } returns flowOf(tasks)
        every { trendFlow(any()) } returns flowOf(trend)
    }

    private fun taskRepo(syncTasks: List<SyncTask> = emptyList()): SyncTaskRepository =
        mockk(relaxed = true) {
            every { tasks } returns flowOf(syncTasks)
        }

    private fun syncTask(id: Long, name: String) = SyncTask(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "remote",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    @Test fun `initial state is empty StatsUiState`() {
        val vm = StatsViewModel(repo(), taskRepo())
        assertThat(vm.state.value).isEqualTo(StatsUiState())
    }

    @Test fun `state collects lifetime stats`() = runTest(mainDispatcher.dispatcher) {
        val stats = LifetimeStats(totalRuns = 5, totalBytesTransferred = 1024)
        val vm = StatsViewModel(repo(lifetime = stats), taskRepo())
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.lifetime.totalRuns).isEqualTo(5)
        assertThat(vm.state.value.lifetime.totalBytesTransferred).isEqualTo(1024)
        job.cancel()
    }

    @Test fun `state collects remote stats`() = runTest(mainDispatcher.dispatcher) {
        val remotes = listOf(RemoteStat("gdrive", 3, 2, 500, 10))
        val vm = StatsViewModel(repo(remotes = remotes), taskRepo())
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.remoteStats).hasSize(1)
        assertThat(vm.state.value.remoteStats[0].remoteName).isEqualTo("gdrive")
        job.cancel()
    }

    @Test fun `state resolves task names from SyncTaskRepository`() = runTest(mainDispatcher.dispatcher) {
        val tasks = listOf(TaskStat(1L, 4, 3, 200, 5))
        val syncTasks = listOf(syncTask(1L, "My Backup"))
        val vm = StatsViewModel(repo(tasks = tasks), taskRepo(syncTasks))
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.taskStats).hasSize(1)
        assertThat(vm.state.value.taskStats[0].name).isEqualTo("My Backup")
        assertThat(vm.state.value.taskStats[0].totalRuns).isEqualTo(4)
        assertThat(vm.state.value.taskStats[0].successRuns).isEqualTo(3)
        job.cancel()
    }

    @Test fun `state uses fallback name for orphaned task id`() = runTest(mainDispatcher.dispatcher) {
        val tasks = listOf(TaskStat(99L, 2, 1, 100, 3))
        val vm = StatsViewModel(repo(tasks = tasks), taskRepo(emptyList()))
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.taskStats[0].name).isEqualTo("Task #99")
        job.cancel()
    }

    @Test fun `refreshQuotas sets quotas in state`() = runTest(mainDispatcher.dispatcher) {
        val r = repo()
        val quotas = listOf(RemoteQuotaState("gdrive", used = 500L, total = 2000L, free = 1500L))
        coEvery { r.fetchQuota(any()) } returns quotas
        val vm = StatsViewModel(r, taskRepo())
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.refreshQuotas(listOf("gdrive"))
        advanceUntilIdle()
        assertThat(vm.state.value.quotas).hasSize(1)
        assertThat(vm.state.value.quotas[0].remoteName).isEqualTo("gdrive")
        assertThat(vm.state.value.quotas[0].free).isEqualTo(1500L)
        job.cancel()
    }

    @Test fun `buildDenseTrend produces 30-element dense series from sparse input`() {
        val today = System.currentTimeMillis() / 86_400_000L
        val trend = listOf(
            TrendDay(dayOffset = (today - 2).toInt(), bytes = 100),
            TrendDay(dayOffset = today.toInt(), bytes = 300),
        )
        val dense = buildDenseTrend(trend, 30)
        assertThat(dense).hasSize(30)
        assertThat(dense.last()).isEqualTo(300)  // today = index 29
        assertThat(dense[27]).isEqualTo(100)     // 2 days ago = index 27
        assertThat(dense[28]).isEqualTo(0L)      // yesterday = gap = 0
    }

    @Test fun `buildDenseTrend all-zero for empty input`() {
        val dense = buildDenseTrend(emptyList(), 30)
        assertThat(dense).hasSize(30)
        assertThat(dense.all { it == 0L }).isTrue()
    }

    @Test fun `resetAll delegates to repo reset`() = runTest(mainDispatcher.dispatcher) {
        val r = repo()
        val vm = StatsViewModel(r, taskRepo())
        vm.resetAll()
        advanceUntilIdle()
        coVerify { r.reset() }
    }

    @Test fun `resetRuns delegates to repo resetAllRuns`() = runTest(mainDispatcher.dispatcher) {
        val r = repo()
        val vm = StatsViewModel(r, taskRepo())
        vm.resetRuns()
        advanceUntilIdle()
        coVerify { r.resetAllRuns() }
    }

    @Test fun `resetRemote delegates to repo resetRemote with the given name`() = runTest(mainDispatcher.dispatcher) {
        val r = repo()
        val vm = StatsViewModel(r, taskRepo())
        vm.resetRemote("gdrive")
        advanceUntilIdle()
        coVerify { r.resetRemote("gdrive") }
    }
}
