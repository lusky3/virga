package app.lusk.virga.core.database

import androidx.room.TypeConverter
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus

/** Enum <-> String converters so Room can persist the domain enums by name. */
class Converters {
    @TypeConverter
    fun directionToString(value: SyncDirection): String = value.name

    @TypeConverter
    fun stringToDirection(value: String): SyncDirection = SyncDirection.valueOf(value)

    @TypeConverter
    fun statusToString(value: SyncStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
