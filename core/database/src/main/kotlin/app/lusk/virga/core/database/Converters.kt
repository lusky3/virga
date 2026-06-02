package app.lusk.virga.core.database

import androidx.room.TypeConverter
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus

/**
 * Enum <-> String converters so Room can persist the domain enums by name.
 *
 * Decoding tolerates an unknown name (a future enum rename, a manually-edited row,
 * or a row written by a newer app version) by falling back to a safe default rather
 * than throwing `IllegalArgumentException` mid-cursor-map, which would crash the
 * Flow that backs the history UI.
 */
class Converters {
    @TypeConverter
    fun directionToString(value: SyncDirection): String = value.name

    @TypeConverter
    fun stringToDirection(value: String): SyncDirection =
        SyncDirection.entries.firstOrNull { it.name == value } ?: SyncDirection.UPLOAD

    @TypeConverter
    fun statusToString(value: SyncStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): SyncStatus =
        SyncStatus.entries.firstOrNull { it.name == value } ?: SyncStatus.FAILED
}
