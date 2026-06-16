package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.sync.ExtraConfigParser
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** intervalMinutes sentinel meaning "custom interval (minutes typed below)". */
private const val CUSTOM_INTERVAL_SENTINEL = -1
/** intervalMinutes sentinel meaning "specific days & time" (calendar schedule). */
private const val CALENDAR_SENTINEL = -2

/** ISO weekdays (1=Mon … 7=Sun) → bitmask (Mon=bit0 … Sun=bit6), and back. */
private fun daysToMask(days: Set<Int>): Int = days.fold(0) { acc, d -> acc or (1 shl (d - 1)) }
private fun maskToDays(mask: Int): Set<Int> = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.toSet()

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
    /** Calendar schedule (when intervalMinutes == CALENDAR_SENTINEL): ISO weekdays
     *  selected (1=Mon … 7=Sun) plus the local time(s)-of-day to run at. */
    val scheduleDays: Set<Int> = emptySet(),
    val scheduleHour: Int = 9,
    val scheduleMinute: Int = 0,
    /**
     * Additional times for the calendar schedule, as minutes-of-day (0..1439).
     * When non-empty the multi-time list is used instead of [scheduleHour]/[scheduleMinute].
     * Empty = single-time fallback (behavior-preserving default).
     */
    val scheduleTimes: List<Int> = emptyList(),
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val bwLimitWifi: String = "",
    val bwLimitMetered: String = "",
    val bufferSize: String = "16M",
    val transfers: Int = 4,
    val checkers: Int = 8,
    /** rclone FilterRule lines (newline-joined): "+ pattern" / "- pattern". */
    val filters: String = "",
    /** rclone --min-size / --max-size (SizeSuffix, e.g. "10M", "1.5G"); blank = unset. */
    val minSize: String = "",
    val maxSize: String = "",
    /** rclone --min-age / --max-age (Duration, e.g. "30d", "1h30m"); blank = unset. */
    val minAge: String = "",
    val maxAge: String = "",
    val deleteExtraneous: Boolean = false,
    val deleteSource: Boolean = false,
    // WS3.1 Tier-2 options -------------------------------------------------------
    /** Checksum toggle: compare by hash rather than size+modtime. */
    val checksum: Boolean = false,
    /** Backup folder for replaced/deleted files; blank = unset. */
    val backupDir: String = "",
    /** Max-delete abort threshold; null = unset. */
    val maxDelete: Int? = null,
    val maxDeleteText: String = "",
    /** Raw "Key=Value" extra config block (newline-separated). */
    val extraConfig: String = "",
    /** rclone MaxTransfer cap (SizeSuffix, e.g. "10G"); blank = unset. */
    val maxTransfer: String = "",
    val maxTransferError: String? = null,
    // B8: configurable retry ---------------------------------------------------
    /** Maximum total WorkManager attempts; must be >= 1. */
    val maxRetries: Int = 3,
    val maxRetriesText: String = "3",
    /** When true, non-auth rclone errors also retry (up to [maxRetries]). */
    val retryOnRclone: Boolean = false,
    /** Initial backoff delay in seconds. */
    val backoffSeconds: Long = 30,
    val backoffSecondsText: String = "30",
    /** EXPONENTIAL backoff when true; LINEAR when false. */
    val backoffExponential: Boolean = true,
    // B10: sync-all group and ordering ----------------------------------------
    /** Optional group label — tasks with the same label can be synced/cancelled together. */
    val groupTag: String = "",
    /** Ordering hint within a syncAll run; lower values run first. Default 0. */
    val sortOrder: Int = 0,
    val sortOrderText: String = "0",
    /** Bisync conflict-resolve: none/newer/older/larger/smaller/path1/path2 or blank = default. */
    val conflictResolve: String = "",
    /** When true and direction is one-way, run a pre-sync advisory check before sync. */
    val conflictCheck: Boolean = false,
    val bwLimitWifiError: String? = null,
    val bwLimitMeteredError: String? = null,
    val bufferSizeError: String? = null,
    val customIntervalError: String? = null,
    val directionError: String? = null,
    /** Null when valid; non-null describes the first offending line. */
    val extraConfigError: String? = null,
    val minSizeError: String? = null,
    val maxSizeError: String? = null,
    val minAgeError: String? = null,
    val maxAgeError: String? = null,
    // Touched flags — errors only shown after field blur or failed save attempt
    val nameTouched: Boolean = false,
    val sourcePathTouched: Boolean = false,
    val remoteNameTouched: Boolean = false,
    val submitAttempted: Boolean = false,
    /** Original creation time for an existing task; 0 for a new one. Preserved on
     *  save so editing a task doesn't reset its creation timestamp. */
    val createdAtEpochMs: Long = 0L,
) {
    val nameError: String? get() =
        if ((nameTouched || submitAttempted) && name.isBlank()) "Name is required" else null

    val sourcePathError: String? get() =
        if ((sourcePathTouched || submitAttempted) && sourcePath.isBlank()) "Source path is required" else null

    val remoteNameError: String? get() =
        if ((remoteNameTouched || submitAttempted) && remoteName.isBlank()) "Required — add a remote first if the list is empty" else null

    // An empty destination resolves to the remote ROOT ("remoteName:"), which is
    // almost never intended and is dangerous; require an explicit path.
    val remotePathError: String? get() =
        if (submitAttempted && remotePath.isBlank()) "Destination is required (e.g. Backups/Photos)" else null

    /** Whether the "specific days & time" calendar schedule mode is selected. */
    val isCalendarSchedule: Boolean get() = intervalMinutes == CALENDAR_SENTINEL

    val scheduleDaysError: String? get() =
        if (isCalendarSchedule && scheduleDays.isEmpty()) "Pick at least one day" else null

    val isValid: Boolean
        get() = name.isNotBlank() &&
            sourcePath.isNotBlank() &&
            remoteName.isNotBlank() &&
            remotePath.isNotBlank() &&
            bwLimitWifiError == null &&
            bwLimitMeteredError == null &&
            bufferSizeError == null &&
            customIntervalError == null &&
            scheduleDaysError == null &&
            directionError == null &&
            extraConfigError == null &&
            minSizeError == null &&
            maxSizeError == null &&
            minAgeError == null &&
            maxAgeError == null &&
            maxTransferError == null
}

@HiltViewModel
class SyncTaskEditViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val remoteRepository: RemoteRepository,
    private val scheduler: SyncScheduler,
    private val folderPickStore: RemoteFolderPickStore,
    private val pendingRemoteResult: PendingRemoteResult,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(SyncTaskForm())
    val form: StateFlow<SyncTaskForm> = _form.asStateFlow()

    init {
        // When the remote file browser (opened via the destination "Browse"
        // button) returns a chosen folder, fill the destination fields.
        viewModelScope.launch {
            folderPickStore.picked.filterNotNull().collect { picked ->
                _form.update {
                    it.copy(remoteName = picked.remoteName, remotePath = picked.path)
                }
                folderPickStore.consume()
            }
        }
        // Returnable add-remote (WS1.2): if the user tapped "add a remote" from
        // the empty dropdown and created one, auto-select it on return — but
        // never clobber a remote the user already picked.
        viewModelScope.launch {
            pendingRemoteResult.created.filterNotNull().collect { name ->
                _form.update { if (it.remoteName.isBlank()) it.copy(remoteName = name) else it }
                pendingRemoteResult.consume()
            }
        }
    }

    val availableRemotes: StateFlow<List<String>> =
        remoteRepository.remotes
            .map { remotes -> remotes.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether Tier 2/3 advanced options are revealed (Settings toggle, WS2.0). */
    val showAdvanced: StateFlow<Boolean> =
        preferences.preferences
            .map { it.showAdvancedOptions }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Guards [load] so it initialises the form exactly once. The screen calls it
     *  from a `LaunchedEffect` that re-fires whenever the editor re-enters
     *  composition (e.g. returning from the destination browser or the remotes
     *  screen) — without this guard, re-loading would clobber the destination just
     *  picked, and any other in-progress edits, with the stored values. */
    private var initialized = false

    /**
     * Loads an existing task, or applies prefill values for a new task.
     * [prefillRemote] and [prefillRemotePath] are only applied when [taskId] <= 0.
     * One-time: subsequent calls (on recomposition) are no-ops.
     */
    fun load(taskId: Long, prefillRemote: String? = null, prefillRemotePath: String? = null) {
        if (initialized) return
        initialized = true
        if (taskId > 0) {
            viewModelScope.launch {
                taskRepository.getTask(taskId)?.let { task ->
                    val isCalendar = task.scheduleDaysMask != 0
                    val isCustomInterval = !isCalendar && task.intervalMinutes != null &&
                        task.intervalMinutes !in PRESET_INTERVAL_MINUTES
                    val isSaf = task.sourcePath.startsWith("content://")
                    _form.value = SyncTaskForm(
                        id = task.id,
                        name = task.name,
                        sourcePath = task.sourcePath,
                        remoteName = task.remoteName,
                        remotePath = task.remotePath,
                        direction = task.direction,
                        intervalMinutes = when {
                            isCalendar -> CALENDAR_SENTINEL
                            isCustomInterval -> CUSTOM_INTERVAL_SENTINEL
                            else -> task.intervalMinutes
                        },
                        customIntervalMinutes = if (isCustomInterval) task.intervalMinutes else null,
                        scheduleDays = if (isCalendar) maskToDays(task.scheduleDaysMask) else emptySet(),
                        scheduleHour = task.scheduleHour,
                        scheduleMinute = task.scheduleMinute,
                        scheduleTimes = if (isCalendar) task.scheduleTimes else emptyList(),
                        wifiOnly = task.wifiOnly,
                        requiresCharging = task.requiresCharging,
                        bwLimitWifi = task.bwLimitWifi.orEmpty(),
                        bwLimitMetered = task.bwLimitMetered.orEmpty(),
                        bufferSize = task.bufferSize,
                        transfers = task.transfers,
                        checkers = task.checkers,
                        filters = task.filters,
                        minSize = task.minSize,
                        maxSize = task.maxSize,
                        minAge = task.minAge,
                        maxAge = task.maxAge,
                        deleteExtraneous = task.deleteExtraneous,
                        deleteSource = task.deleteSource,
                        checksum = task.checksum,
                        backupDir = task.backupDir.orEmpty(),
                        maxDelete = task.maxDelete,
                        maxDeleteText = task.maxDelete?.toString() ?: "",
                        extraConfig = task.extraConfig,
                        maxTransfer = task.maxTransfer,
                        createdAtEpochMs = task.createdAtEpochMs,
                        directionError = if (isSaf && task.direction == SyncDirection.BISYNC) BISYNC_SAF_ERROR else null,
                        maxRetries = task.maxRetries,
                        maxRetriesText = task.maxRetries.toString(),
                        retryOnRclone = task.retryOnRclone,
                        backoffSeconds = task.backoffSeconds,
                        backoffSecondsText = task.backoffSeconds.toString(),
                        backoffExponential = task.backoffExponential,
                        groupTag = task.groupTag,
                        sortOrder = task.sortOrder,
                        sortOrderText = task.sortOrder.toString(),
                        conflictResolve = task.conflictResolve,
                        conflictCheck = task.conflictCheck,
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
            // Seed Tier-0/1 defaults from app Settings for a NEW task (WS2.0).
            viewModelScope.launch {
                val prefs = preferences.preferences.first()
                _form.update {
                    it.copy(
                        wifiOnly = prefs.wifiOnlyByDefault,
                        requiresCharging = prefs.requireChargingByDefault,
                        bwLimitWifi = prefs.defaultBwLimitWifi ?: it.bwLimitWifi,
                        bwLimitMetered = prefs.defaultBwLimitMetered ?: it.bwLimitMetered,
                    )
                }
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
        val raw = transform(current)
        // Mutual exclusion: mirror (deleteExtraneous) and move (deleteSource) are
        // semantically incompatible — enabling one forces the other off.
        val next = when {
            raw.deleteSource && !current.deleteSource -> raw.copy(deleteExtraneous = false)
            raw.deleteExtraneous && !current.deleteExtraneous -> raw.copy(deleteSource = false)
            else -> raw
        }
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
            extraConfigError = ExtraConfigParser.firstError(next.extraConfig),
            minSizeError = validateSizeSuffix(next.minSize),
            maxSizeError = validateSizeSuffix(next.maxSize),
            minAgeError = validateDuration(next.minAge),
            maxAgeError = validateDuration(next.maxAge),
            maxTransferError = validateSizeSuffix(next.maxTransfer),
        )
    }

    /** Switches the schedule to "specific days & time" (calendar) mode. */
    fun selectCalendarSchedule() = update { it.copy(intervalMinutes = CALENDAR_SENTINEL) }

    /** Toggles an ISO weekday (1=Mon … 7=Sun) in the calendar schedule. */
    fun toggleScheduleDay(isoDay: Int) = update { f ->
        val days = if (isoDay in f.scheduleDays) f.scheduleDays - isoDay else f.scheduleDays + isoDay
        f.copy(scheduleDays = days)
    }

    fun setScheduleTime(hour: Int, minute: Int) =
        update { it.copy(scheduleHour = hour.coerceIn(0, 23), scheduleMinute = minute.coerceIn(0, 59)) }

    /** Adds a time (minutes-of-day, 0..1439) to the multi-time list; ignores duplicates. */
    fun addScheduleTime(minuteOfDay: Int) = update { f ->
        val clamped = minuteOfDay.coerceIn(0, 1439)
        if (clamped in f.scheduleTimes) f else f.copy(scheduleTimes = f.scheduleTimes + clamped)
    }

    /** Removes a time at [index] from the multi-time list; no-op for out-of-bounds. */
    fun removeScheduleTime(index: Int) = update { f ->
        if (index !in f.scheduleTimes.indices) f
        else f.copy(scheduleTimes = f.scheduleTimes.toMutableList().also { it.removeAt(index) })
    }

    fun save(onSaved: () -> Unit) {
        _form.update { it.copy(submitAttempted = true) }
        val form = _form.value
        if (!form.isValid) return
        viewModelScope.launch {
            val task = SyncTask(
                id = form.id,
                name = form.name.trim(),
                sourcePath = form.sourcePath.trim(),
                remoteName = form.remoteName.trim(),
                remotePath = form.remotePath.trim(),
                direction = form.direction,
                // Calendar mode persists no interval (schedule columns drive it).
                // Otherwise resolve the CUSTOM sentinel to the entered minutes;
                // presets/manual pass through. Guard so a stale/invalid custom entry
                // can never persist the sentinel or a sub-minimum interval.
                intervalMinutes = when {
                    form.isCalendarSchedule -> null
                    form.intervalMinutes == CUSTOM_INTERVAL_SENTINEL ->
                        form.customIntervalMinutes?.takeIf { it >= 15 }
                    else -> form.intervalMinutes
                },
                scheduleDaysMask = if (form.isCalendarSchedule) daysToMask(form.scheduleDays) else 0,
                scheduleHour = form.scheduleHour,
                scheduleMinute = form.scheduleMinute,
                scheduleTimes = if (form.isCalendarSchedule) form.scheduleTimes else emptyList(),
                wifiOnly = form.wifiOnly,
                requiresCharging = form.requiresCharging,
                bwLimitWifi = form.bwLimitWifi.trim().ifBlank { null },
                bwLimitMetered = form.bwLimitMetered.trim().ifBlank { null },
                bufferSize = form.bufferSize.trim().ifBlank { "16M" },
                transfers = form.transfers,
                checkers = form.checkers,
                filters = form.filters,
                minSize = form.minSize.trim(),
                maxSize = form.maxSize.trim(),
                minAge = form.minAge.trim(),
                maxAge = form.maxAge.trim(),
                // Normalize Mirror to match the UI: it's inert (write-back never deletes)
                // for a DOWNLOAD into a SAF folder, so don't persist a misleading `true`
                // there. Mirrors the disabled-toggle condition in SyncTaskEditScreen.
                deleteExtraneous = form.deleteExtraneous &&
                    !(form.direction == SyncDirection.DOWNLOAD && form.sourcePath.startsWith("content://")),
                // Normalize Move: forbidden for SAF sources and BISYNC (inert conditions
                // match the moveInert check in SyncTaskEditScreen).
                deleteSource = form.deleteSource &&
                    !form.sourcePath.startsWith("content://") &&
                    form.direction != SyncDirection.BISYNC,
                checksum = form.checksum,
                backupDir = form.backupDir.trim().ifBlank { null },
                maxDelete = form.maxDelete,
                extraConfig = form.extraConfig.trim(),
                maxTransfer = form.maxTransfer.trim(),
                // New task: stamp creation time now. Existing task (edit): preserve the
                // original timestamp loaded into the form, so editing doesn't reset it.
                createdAtEpochMs = if (form.id == 0L) System.currentTimeMillis() else form.createdAtEpochMs,
                maxRetries = form.maxRetries.coerceAtLeast(1),
                retryOnRclone = form.retryOnRclone,
                backoffSeconds = form.backoffSeconds.coerceAtLeast(10L),
                backoffExponential = form.backoffExponential,
                groupTag = form.groupTag.trim(),
                sortOrder = form.sortOrder,
                conflictResolve = form.conflictResolve.trim(),
                conflictCheck = form.conflictCheck,
            )
            val id = taskRepository.save(task)
            scheduler.schedule(task.copy(id = id))
            onSaved()
        }
    }

    private companion object {
        const val BISYNC_SAF_ERROR = "Two-way sync isn't available for this folder on this device."
        private val PRESET_INTERVAL_MINUTES = setOf(null, 15, 30, 60, 360, 720, 1440)
        // Single-rate: e.g. 1M, 10M:1M (upload:download)
        private val BW_LIMIT_SINGLE_REGEX = Regex("""^\d+[KMGTkmgt]?(:\d+[KMGTkmgt]?)?$""")
        // One timetable token: HH:MM,RATE — HH 00-23, MM 00-59,
        // RATE = "off" | SizeSuffix | SizeSuffix:SizeSuffix
        private val BW_LIMIT_TOKEN_REGEX =
            Regex("""^(?:[01]\d|2[0-3]):[0-5]\d,(?:off|\d+[KMGTkmgt]?(?::\d+[KMGTkmgt]?)?)$""")
        private val BUFFER_SIZE_REGEX = Regex("""^\d+[KMGkmg]?$""")
        // rclone SizeSuffix: optional decimal number followed by an optional unit.
        // Accepts: plain bytes (e.g. "1024"), SI (e.g. "10M", "1.5G"), IEC (e.g. "16Mi").
        private val SIZE_SUFFIX_REGEX = Regex(
            """^\d+(\.\d+)?\s*([kKmMgGtTpP][iI]?[bB]?)?$""",
        )
        // rclone Duration: one or more <number><unit> pairs.
        // Units: ms, s, m, h, d, w, M, y (case-sensitive in rclone, accept all here).
        private val DURATION_REGEX = Regex(
            """^(\d+(\.\d+)?\s*(ms|[smhdwMy]))+$""",
        )

        fun validateBwLimit(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null
            if (BW_LIMIT_SINGLE_REGEX.matches(trimmed)) return null
            // Accept a timetable: one-or-more whitespace-separated HH:MM,RATE tokens.
            // Split on a whitespace run (not a single space) so pasted input with
            // doubled spaces doesn't yield an empty token and spuriously fail.
            val tokens = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.isNotEmpty() && tokens.all { BW_LIMIT_TOKEN_REGEX.matches(it) }) return null
            return "Invalid bandwidth limit — use e.g. 1M, 10M:1M, or a timetable like 08:00,512k 19:00,off"
        }

        fun validateBufferSize(value: String): String? {
            val trimmed = value.trim()
            return when {
                trimmed.isBlank() -> "Buffer size cannot be empty"
                !BUFFER_SIZE_REGEX.matches(trimmed) -> "Invalid format — use e.g. 16M or 256K"
                else -> null
            }
        }

        /** Accepts blank (unset) or a valid rclone SizeSuffix (e.g. "10M", "1.5G", "512"). */
        fun validateSizeSuffix(value: String): String? {
            val trimmed = value.trim()
            return if (trimmed.isNotBlank() && !SIZE_SUFFIX_REGEX.matches(trimmed)) {
                "Invalid size — use e.g. 10M, 1.5G, or 512"
            } else null
        }

        /** Accepts blank (unset) or a valid rclone Duration (e.g. "30d", "1h30m", "100ms"). */
        fun validateDuration(value: String): String? {
            val trimmed = value.trim()
            return if (trimmed.isNotBlank() && !DURATION_REGEX.matches(trimmed)) {
                "Invalid age — use e.g. 30d, 1h30m, or 100ms"
            } else null
        }
    }
}
