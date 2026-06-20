package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WizardStep { INTRO, ACCOUNT, SOURCE, DESTINATION, DIRECTION_NAME, DONE }

data class WizardState(
    val step: WizardStep = WizardStep.INTRO,
    val remoteName: String = "",
    val sourcePath: String = "",
    val remotePath: String = "",
    val direction: SyncDirection = SyncDirection.UPLOAD,
    val taskName: String = "",
    /** Non-null while the save coroutine is in flight. */
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val canAdvanceAccount: Boolean get() = remoteName.isNotBlank()
    val canAdvanceSource: Boolean get() = sourcePath.isNotBlank()
    val canAdvanceDestination: Boolean get() = remotePath.isNotBlank()
    val canFinish: Boolean get() = taskName.isNotBlank() && !saving
}

@HiltViewModel
class FirstSyncWizardViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val remoteRepository: RemoteRepository,
    private val scheduler: SyncScheduler,
    private val pendingRemoteResult: PendingRemoteResult,
    private val folderPickStore: RemoteFolderPickStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    val availableRemotes: StateFlow<List<String>> =
        remoteRepository.remotes
            .map { remotes -> remotes.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // When the user returns from the add-remote flow, auto-select the new remote.
        viewModelScope.launch {
            pendingRemoteResult.created.filterNotNull().collect { name ->
                _state.update { s -> if (s.remoteName.isBlank()) s.copy(remoteName = name) else s }
                pendingRemoteResult.consume()
            }
        }
        // When the destination "Browse" button opens the remote file browser and the
        // user picks a folder, fill in the chosen remote + path on return. Mirrors
        // SyncTaskEditViewModel — the browser is launched with the already-selected
        // remote, so picked.remoteName matches the wizard's selection.
        viewModelScope.launch {
            folderPickStore.picked.filterNotNull().collect { picked ->
                _state.update { it.copy(remoteName = picked.remoteName, remotePath = picked.path) }
                folderPickStore.consume()
            }
        }
    }

    fun selectRemote(name: String) = _state.update { it.copy(remoteName = name) }

    fun applySourcePath(path: String) {
        val folderName = friendlyFolderName(path)
        _state.update { s ->
            val nameToSet = if (s.taskName.isBlank() && folderName.isNotBlank()) folderName else s.taskName
            s.copy(sourcePath = path, taskName = nameToSet)
        }
    }

    /**
     * A human-readable folder name for the default task name. A SAF tree URI like
     * `content://…/tree/primary%3ADownload%2Fvirgatest` URL-decodes to
     * `…/tree/primary:Download/virgatest`, so taking the last path-and-colon
     * segment yields "virgatest" rather than the percent-encoded blob. Plain
     * filesystem paths (e.g. `/storage/emulated/0/DCIM`) are unaffected.
     */
    private fun friendlyFolderName(path: String): String {
        val raw = path.trimEnd('/')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank {
            raw.substringAfterLast('/')
        }
    }

    fun setRemotePath(path: String) = _state.update { it.copy(remotePath = path) }

    fun setDirection(dir: SyncDirection) = _state.update { it.copy(direction = dir) }

    fun setTaskName(name: String) = _state.update { it.copy(taskName = name) }

    fun goNext() = _state.update { s ->
        val next = when (s.step) {
            WizardStep.INTRO -> WizardStep.ACCOUNT
            WizardStep.ACCOUNT -> if (s.canAdvanceAccount) WizardStep.SOURCE else s.step
            WizardStep.SOURCE -> if (s.canAdvanceSource) WizardStep.DESTINATION else s.step
            WizardStep.DESTINATION -> if (s.canAdvanceDestination) WizardStep.DIRECTION_NAME else s.step
            WizardStep.DIRECTION_NAME -> s.step
            WizardStep.DONE -> s.step
        }
        s.copy(step = next)
    }

    fun goBack() = _state.update { s ->
        val prev = when (s.step) {
            WizardStep.INTRO -> s.step
            WizardStep.ACCOUNT -> WizardStep.INTRO
            WizardStep.SOURCE -> WizardStep.ACCOUNT
            WizardStep.DESTINATION -> WizardStep.SOURCE
            WizardStep.DIRECTION_NAME -> WizardStep.DESTINATION
            WizardStep.DONE -> s.step
        }
        s.copy(step = prev)
    }

    fun save(onSaved: (taskId: Long) -> Unit) {
        val s = _state.value
        if (!s.canFinish) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            runCatching {
                val task = SyncTask(
                    id = 0,
                    name = s.taskName.trim(),
                    sourcePath = s.sourcePath.trim(),
                    remoteName = s.remoteName.trim(),
                    remotePath = s.remotePath.trim(),
                    direction = s.direction,
                    intervalMinutes = null,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                val id = taskRepository.save(task)
                scheduler.schedule(task.copy(id = id))
                id
            }.onSuccess { id ->
                _state.update { it.copy(saving = false, step = WizardStep.DONE) }
                onSaved(id)
            }.onFailure { e ->
                _state.update { it.copy(saving = false, saveError = e.message ?: "Save failed") }
            }
        }
    }
}
