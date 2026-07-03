package mobile.racemaster.data.db

import androidx.room.TypeConverter
import mobile.racemaster.data.db.entity.BibEntryType

class Converters {
    @TypeConverter
    fun fromBibEntryType(type: BibEntryType): String = type.name

    @TypeConverter
    fun toBibEntryType(value: String): BibEntryType = BibEntryType.valueOf(value)
}