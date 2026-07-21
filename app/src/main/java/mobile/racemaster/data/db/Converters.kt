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
}
