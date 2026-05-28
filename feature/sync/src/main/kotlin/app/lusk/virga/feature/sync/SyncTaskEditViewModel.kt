package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable form state for a sync task. */
data class SyncTaskForm(
    val id: Long = 0,
    val name: String = "",
    val sourcePath: String = "",
    val remoteName: String = "",
    val remotePath: String = "",
    val direction: SyncDirection = SyncDirection.UPLOAD,
    val intervalMinutes: Int? = null,
    val wifiOnly: Boolean = true,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && sourcePath.isNotBlank() && remoteName.isNotBlank()
}

@HiltViewModel
class SyncTaskEditViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val remoteRepository: RemoteRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val _form = MutableStateFlow(SyncTaskForm())
    val form: StateFlow<SyncTaskForm> = _form.asStateFlow()

    val availableRemotes: StateFlow<List<String>> =
        remoteRepository.remotes
            .map { remotes -> remotes.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Loads an existing task into the form, or leaves a blank form for new tasks. */
    fun load(taskId: Long) {
        if (taskId <= 0) return
        viewModelScope.launch {
            taskRepository.getTask(taskId)?.let { task ->
                _form.value = SyncTaskForm(
                    id = task.id,
                    name = task.name,
                    sourcePath = task.sourcePath,
                    remoteName = task.remoteName,
                    remotePath = task.remotePath,
                    direction = task.direction,
                    intervalMinutes = task.intervalMinutes,
                    wifiOnly = task.wifiOnly,
                )
            }
        }
    }

    fun update(transform: (SyncTaskForm) -> SyncTaskForm) = _form.update(transform)

    /** Persists the form and (re)schedules it. Invokes [onSaved] on success. */
    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) return
        viewModelScope.launch {
            val entity = SyncTaskEntity(
                id = form.id,
                name = form.name.trim(),
                sourcePath = form.sourcePath.trim(),
                remoteName = form.remoteName.trim(),
                remotePath = form.remotePath.trim(),
                direction = form.direction,
                intervalMinutes = form.intervalMinutes,
                wifiOnly = form.wifiOnly,
            )
            val id = taskRepository.save(entity)
            scheduler.schedule(entity.copy(id = id))
            onSaved()
        }
    }
}
