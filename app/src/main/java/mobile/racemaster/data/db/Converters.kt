package mobile.racemaster.data.db

import androidx.room.TypeConverter
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode

class Converters {
    @TypeConverter
    fun fromHistoryMode(mode: HistoryMode): String = mode.name

    @TypeConverter
    fun toHistoryMode(value: String): HistoryMode = HistoryMode.valueOf(value)

    @TypeConverter
    fun fromHistoryAction(action: HistoryAction): String = action.name

    @TypeConverter
    fun toHistoryAction(value: String): HistoryAction = HistoryAction.valueOf(value)

    // Comma-joined — backs RaceEntity.courses, always an empty list for every race created now
    // (see that field's own doc), kept only so its column doesn't need its own separate Room
    // migration until phase 4 drops it entirely.
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")
}
