package mobile.racemaster.data.mule

import java.util.Locale
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.db.entity.FinishSplitEntity

/**
 * Maps a Time Mode split into the wire/server record shape. [raceStartedAtMillis] is the
 * race's `timeModeStartedAtMillis` (the split's own t=0 reference) — `time` is formatted
 * elapsed-since-start to match the racemaster server's existing finisher time convention
 * (confirmed against real data, e.g. `"00:26:51"`); `timestampMillis` carries the raw
 * wall-clock time alongside it for full fidelity.
 */
fun FinishSplitEntity.toSyncRecord(raceStartedAtMillis: Long?): SyncRecord {
    val elapsedMillis = raceStartedAtMillis?.let { timestampMillis - it } ?: 0L
    return SyncRecord(
        recordUuid = recordUuid,
        action = "Finish",
        number = null,
        time = formatElapsedAsClock(elapsedMillis),
        splitNumber = splitNumber,
        note = note,
        timestampMillis = timestampMillis,
        deviceName = deviceName,
    )
}

/**
 * Maps a Bibs Mode entry into the wire/server record shape. Unlike Time Mode, Bibs Mode has
 * no stopwatch of its own — a bib entry's only legitimate time signal is the wall-clock
 * `timestampMillis` it was logged at; an "elapsed since race start" is meaningless without
 * externally correlating it against a Time Mode device's splits by `splitNumber`, so `time`
 * is left null here rather than fabricating one.
 */
fun BibEntryEntity.toSyncRecord(): SyncRecord {
    return SyncRecord(
        recordUuid = recordUuid,
        action = type.toServerAction(),
        number = bibNumber,
        time = null,
        splitNumber = splitNumber,
        note = note,
        timestampMillis = timestampMillis,
        deviceName = deviceName,
    )
}

private fun BibEntryType.toServerAction(): String = when (this) {
    BibEntryType.START -> "Start"
    BibEntryType.FINISH -> "Finish"
    BibEntryType.RETIRE -> "DNF"
    BibEntryType.IGNORE -> "Ignore"
    BibEntryType.SENIORS -> "Seniors"
    BibEntryType.JUNIORS -> "Juniors"
    BibEntryType.MALE -> "Male"
    BibEntryType.FEMALE -> "Female"
    BibEntryType.CLOCK -> "Clock"
    BibEntryType.STOP -> "Stop"
}

private fun formatElapsedAsClock(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
