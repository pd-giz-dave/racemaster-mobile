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

    // Comma-joined — safe because course names are restricted to [a-zA-Z0-9-] (see
    // isValidCourseName), so a comma can never appear inside one to collide with the separator.
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")
}
