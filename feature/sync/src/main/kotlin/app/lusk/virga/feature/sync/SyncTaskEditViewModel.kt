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
    val customIntervalMinutes: Int? = null,
    val wifiOnly: Boolean = true,
    val bwLimitWifi: String = "",
    val bwLimitMetered: String = "",
    val bufferSize: String = "16M",
    val bwLimitWifiError: String? = null,
    val bwLimitMeteredError: String? = null,
    val bufferSizeError: String? = null,
    val customIntervalError: String? = null,
    val directionError: String? = null,
    // Touched flags — errors only shown after field blur or failed save attempt
    val nameTouched: Boolean = false,
    val sourcePathTouched: Boolean = false,
    val remoteNameTouched: Boolean = false,
    val submitAttempted: Boolean = false,
) {
    val nameError: String? get() =
        if ((nameTouched || submitAttempted) && name.isBlank()) "Name is required" else null

    val sourcePathError: String? get() =
        if ((sourcePathTouched || submitAttempted) && sourcePath.isBlank()) "Source path is required" else null

    val remoteNameError: String? get() =
        if ((remoteNameTouched || submitAttempted) && remoteName.isBlank()) "Required — add a remote first if the list is empty" else null

    val isValid: Boolean
        get() = name.isNotBlank() &&
            sourcePath.isNotBlank() &&
            remoteName.isNotBlank() &&
            bwLimitWifiError == null &&
            bwLimitMeteredError == null &&
            bufferSizeError == null &&
            customIntervalError == null &&
            directionError == null
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

    /**
     * Loads an existing task, or applies prefill values for a new task.
     * [prefillRemote] and [prefillRemotePath] are only applied when [taskId] <= 0.
     */
    fun load(taskId: Long, prefillRemote: String? = null, prefillRemotePath: String? = null) {
        if (taskId > 0) {
            viewModelScope.launch {
                taskRepository.getTask(taskId)?.let { task ->
                    val isCustomInterval = task.intervalMinutes != null &&
                        task.intervalMinutes !in PRESET_INTERVAL_MINUTES
                    val isSaf = task.sourcePath.startsWith("content://")
                    _form.value = SyncTaskForm(
                        id = task.id,
                        name = task.name,
                        sourcePath = task.sourcePath,
                        remoteName = task.remoteName,
                        remotePath = task.remotePath,
                        direction = task.direction,
                        intervalMinutes = if (isCustomInterval) CUSTOM_INTERVAL_SENTINEL else task.intervalMinutes,
                        customIntervalMinutes = if (isCustomInterval) task.intervalMinutes else null,
                        wifiOnly = task.wifiOnly,
                        bwLimitWifi = task.bwLimitWifi.orEmpty(),
                        bwLimitMetered = task.bwLimitMetered.orEmpty(),
                        bufferSize = task.bufferSize,
                        directionError = if (isSaf && task.direction == SyncDirection.BISYNC) BISYNC_SAF_ERROR else null,
                    )
                }
            }
        } else {
            _form.update { current ->
                current.copy(
                    remoteName = prefillRemote ?: current.remoteName,
                    remotePath = prefillRemotePath ?: current.remotePath,
                )
            }
        }
    }

    fun applySourcePath(path: String) = _form.update {
        val isSaf = path.startsWith("content://")
        it.copy(
            sourcePath = path,
            sourcePathTouched = true,
            directionError = if (isSaf && it.direction == SyncDirection.BISYNC) BISYNC_SAF_ERROR else null,
        )
    }

    fun clearSourcePath() = _form.update { it.copy(sourcePath = "", sourcePathTouched = true) }

    fun touchName() = _form.update { it.copy(nameTouched = true) }
    fun touchSourcePath() = _form.update { it.copy(sourcePathTouched = true) }
    fun touchRemoteName() = _form.update { it.copy(remoteNameTouched = true) }

    fun update(transform: (SyncTaskForm) -> SyncTaskForm) = _form.update { current ->
        val next = transform(current)
        val isCustomInterval = next.intervalMinutes == CUSTOM_INTERVAL_SENTINEL
        val isSafSource = next.sourcePath.startsWith("content://")
        next.copy(
            bwLimitWifiError = validateBwLimit(next.bwLimitWifi),
            bwLimitMeteredError = validateBwLimit(next.bwLimitMetered),
            bufferSizeError = validateBufferSize(next.bufferSize),
            customIntervalError = if (isCustomInterval && (next.customIntervalMinutes ?: 0) < 15) {
                "Minimum 15 minutes"
            } else null,
            directionError = if (isSafSource && next.direction == SyncDirection.BISYNC) {
                BISYNC_SAF_ERROR
            } else null,
        )
    }

    fun save(onSaved: () -> Unit) {
        _form.update { it.copy(submitAttempted = true) }
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
                // Resolve the CUSTOM sentinel to the entered minutes; presets/manual pass
                // through. Guard the value so a stale/invalid custom entry can never persist
                // the sentinel or a sub-minimum interval (falls back to manual = null).
                intervalMinutes = if (form.intervalMinutes == CUSTOM_INTERVAL_SENTINEL) {
                    form.customIntervalMinutes?.takeIf { it >= 15 }
                } else {
                    form.intervalMinutes
                },
                wifiOnly = form.wifiOnly,
                bwLimitWifi = form.bwLimitWifi.trim().ifBlank { null },
                bwLimitMetered = form.bwLimitMetered.trim().ifBlank { null },
                bufferSize = form.bufferSize.trim().ifBlank { "16M" },
            )
            val id = taskRepository.save(entity)
            scheduler.schedule(entity.copy(id = id))
            onSaved()
        }
    }

    private companion object {
        const val CUSTOM_INTERVAL_SENTINEL = -1
        const val BISYNC_SAF_ERROR = "Two-way sync isn't available for this folder on this device."
        private val PRESET_INTERVAL_MINUTES = setOf(null, 15, 30, 60, 360, 720, 1440)
        private val BW_LIMIT_REGEX = Regex("""^\d+[KMGTkmgt]?(:\d+[KMGTkmgt]?)?$""")
        private val BUFFER_SIZE_REGEX = Regex("""^\d+[KMGkmg]?$""")

        fun validateBwLimit(value: String): String? {
            val trimmed = value.trim()
            return if (trimmed.isNotBlank() && !BW_LIMIT_REGEX.matches(trimmed)) {
                "Invalid format — use e.g. 1M or 10M:1M"
            } else null
        }

        fun validateBufferSize(value: String): String? {
            val trimmed = value.trim()
            return when {
                trimmed.isBlank() -> "Buffer size cannot be empty"
                !BUFFER_SIZE_REGEX.matches(trimmed) -> "Invalid format — use e.g. 16M or 256K"
                else -> null
            }
        }
    }
}

