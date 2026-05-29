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
    /** rclone --bwlimit on WiFi; blank = no limit. e.g. "1M" or "10M:1M". */
    val bwLimitWifi: String = "",
    /** rclone --bwlimit on metered connections; blank = no limit. */
    val bwLimitMetered: String = "",
    /** rclone --buffer-size; positive integer with optional unit suffix. */
    val bufferSize: String = "16M",
    /** Non-null when bwLimitWifi or bwLimitMetered have an invalid format. */
    val bwLimitError: String? = null,
    /** Non-null when bufferSize has an invalid format. */
    val bufferSizeError: String? = null,
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
            sourcePath.isNotBlank() &&
            remoteName.isNotBlank() &&
            bwLimitError == null &&
            bufferSizeError == null
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
                    bwLimitWifi = task.bwLimitWifi.orEmpty(),
                    bwLimitMetered = task.bwLimitMetered.orEmpty(),
                    bufferSize = task.bufferSize,
                )
            }
        }
    }

    /**
     * Updates the form, validating bwLimit and bufferSize fields inline so
     * the UI can reflect errors immediately. SEC-L: validates free-text rclone
     * flags at the ViewModel boundary before they can reach the repository.
     */
    fun update(transform: (SyncTaskForm) -> SyncTaskForm) = _form.update { current ->
        val next = transform(current)
        next.copy(
            bwLimitError = validateBwLimit(next.bwLimitWifi)
                ?: validateBwLimit(next.bwLimitMetered),
            bufferSizeError = validateBufferSize(next.bufferSize),
        )
    }

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
        // rclone bwlimit: rate[SUFFIX][:timetable] where rate is digits with
        // optional K/M/G/T suffix, timetable is same format.
        // Regex: \d+[KMGTkmgt]?(:\d+[KMGTkmgt]?)? — blank is allowed (no limit).
        private val BW_LIMIT_REGEX = Regex("""^\d+[KMGTkmgt]?(:\d+[KMGTkmgt]?)?$""")

        // rclone buffer-size: digits with optional K/M/G suffix (no T per rclone docs).
        // Regex: \d+[KMGkmg]? — blank not allowed for bufferSize (default enforced).
        private val BUFFER_SIZE_REGEX = Regex("""^\d+[KMGkmg]?$""")

        /** Returns an error message if [value] is non-blank and does not match bwlimit format. */
        fun validateBwLimit(value: String): String? {
            val trimmed = value.trim()
            return if (trimmed.isNotBlank() && !BW_LIMIT_REGEX.matches(trimmed)) {
                "Invalid format — use e.g. 1M or 10M:1M"
            } else null
        }

        /** Returns an error message if [value] is blank or does not match buffer-size format. */
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
