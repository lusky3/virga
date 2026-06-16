package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity

/**
 * Decodes a JSON-array-of-ints string (e.g. "[120,840]") to a list of
 * minutes-of-day. Returns an empty list for blank/null/unparseable input
 * so a bad stored value never crashes the mapping pipeline.
 */
internal fun decodeScheduleTimes(json: String): List<Int> {
    val trimmed = json.trim()
    if (trimmed.isBlank() || trimmed == "[]") return emptyList()
    return try {
        trimmed.removePrefix("[").removeSuffix("]")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..1439 }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Encodes a list of minutes-of-day to a compact JSON array string. */
internal fun encodeScheduleTimes(times: List<Int>): String {
    if (times.isEmpty()) return ""
    return "[${times.joinToString(",")}]"
}

/**
 * Entity ↔ domain mappers. Room `*Entity` types stay inside the data layer;
 * repositories convert to the domain models in [app.lusk.virga.core.common.model]
 * that ViewModels and UI consume. The shapes mirror each other field-for-field,
 * so these are plain field copies.
 */

internal fun SyncTaskEntity.toDomain() = SyncTask(
    id = id,
    name = name,
    sourcePath = sourcePath,
    remoteName = remoteName,
    remotePath = remotePath,
    direction = direction,
    intervalMinutes = intervalMinutes,
    scheduleDaysMask = scheduleDaysMask,
    scheduleHour = scheduleHour,
    scheduleMinute = scheduleMinute,
    filters = filters,
    minSize = minSize,
    maxSize = maxSize,
    minAge = minAge,
    maxAge = maxAge,
    bwLimitWifi = bwLimitWifi,
    bwLimitMetered = bwLimitMetered,
    transfers = transfers,
    checkers = checkers,
    bufferSize = bufferSize,
    deleteExtraneous = deleteExtraneous,
    deleteSource = deleteSource,
    wifiOnly = wifiOnly,
    requiresCharging = requiresCharging,
    enabled = enabled,
    createdAtEpochMs = createdAtEpochMs,
    checksum = checksum,
    backupDir = backupDir,
    maxDelete = maxDelete,
    extraConfig = extraConfig,
    maxTransfer = maxTransfer,
    maxRetries = maxRetries,
    retryOnRclone = retryOnRclone,
    backoffSeconds = backoffSeconds,
    backoffExponential = backoffExponential,
    scheduleTimes = decodeScheduleTimes(scheduleTimes),
)

internal fun SyncTask.toEntity() = SyncTaskEntity(
    id = id,
    name = name,
    sourcePath = sourcePath,
    remoteName = remoteName,
    remotePath = remotePath,
    direction = direction,
    intervalMinutes = intervalMinutes,
    scheduleDaysMask = scheduleDaysMask,
    scheduleHour = scheduleHour,
    scheduleMinute = scheduleMinute,
    filters = filters,
    minSize = minSize,
    maxSize = maxSize,
    minAge = minAge,
    maxAge = maxAge,
    bwLimitWifi = bwLimitWifi,
    bwLimitMetered = bwLimitMetered,
    transfers = transfers,
    checkers = checkers,
    bufferSize = bufferSize,
    deleteExtraneous = deleteExtraneous,
    deleteSource = deleteSource,
    wifiOnly = wifiOnly,
    requiresCharging = requiresCharging,
    enabled = enabled,
    // A new task (id == 0) gets its creation timestamp here, mirroring the
    // entity's former default; an existing task keeps its stored value.
    createdAtEpochMs = if (id == 0L && createdAtEpochMs == 0L) System.currentTimeMillis() else createdAtEpochMs,
    checksum = checksum,
    backupDir = backupDir,
    maxDelete = maxDelete,
    extraConfig = extraConfig,
    maxTransfer = maxTransfer,
    maxRetries = maxRetries,
    retryOnRclone = retryOnRclone,
    backoffSeconds = backoffSeconds,
    backoffExponential = backoffExponential,
    scheduleTimes = encodeScheduleTimes(scheduleTimes),
)

internal fun SyncRunEntity.toDomain() = SyncRun(
    id = id,
    taskId = taskId,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    status = status,
    filesTransferred = filesTransferred,
    bytesTransferred = bytesTransferred,
    errorCount = errorCount,
    errorMessage = errorMessage,
    logPath = logPath,
    failedFiles = failedFiles,
)

internal fun ConflictEntity.toDomain() = Conflict(
    id = id,
    taskId = taskId,
    remoteName = remoteName,
    basePath = basePath,
    variant1Path = variant1Path,
    variant2Path = variant2Path,
    variant1Size = variant1Size,
    variant2Size = variant2Size,
    detectedAtEpochMs = detectedAtEpochMs,
    resolved = resolved,
)
