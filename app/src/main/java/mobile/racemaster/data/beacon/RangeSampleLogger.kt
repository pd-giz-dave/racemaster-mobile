package mobile.racemaster.data.beacon

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The three field contexts TODO.md's "Bluetooth Beacon range estimation" section asks to
 *  compare — how RSSI drops off with range differs enormously between them (a human body
 *  blocking line-of-sight can attenuate 2.4GHz by tens of dB — see
 *  docs/beacon-detection-plan.md's range-calibration section), which is itself the likely reason
 *  a single fixed exponent ([BeaconScanDecoder.PATH_LOSS_EXPONENT]) is grossly inaccurate
 *  everywhere at once rather than needing a small tweak. */
enum class RangeContext(val label: String) {
    INDOOR_CROWDED("Indoor registration (crowded)"),
    OUTDOOR_SPARSE_CP("Outdoor CP (open, sparse)"),
    OUTDOOR_CROWDED_FINISH("Outdoor finish (crowded)"),
}

/** One recorded data point for later offline analysis (e.g. fitting a per-context model against
 *  the exported CSV) — this app never reads its own samples back to estimate anything live.
 *  [rssiMedian]/[rssiMin]/[rssiMax]/[sampleCount] summarize a short burst of raw readings (see
 *  [mobile.racemaster.ui.beaconops.RangeCalibrationViewModel]'s own doc) rather than trusting one
 *  noisy packet. [phoneModel]/[androidSdk] are captured automatically, not entered — receiving-
 *  device RSSI variance is itself a documented error source worth having on record. */
data class RangeSample(
    val timestampMillis: Long,
    val context: RangeContext,
    val distanceMeters: Double,
    val beaconNumber: Int,
    val measuredPowerDbm: Int,
    val rssiMedian: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val sampleCount: Int,
    val phoneModel: String,
    val androidSdk: Int,
)

/** Appends range-calibration samples to a plain CSV file in app-private internal storage (no
 *  storage permission needed) — a pure data logger, nothing here estimates distance itself; see
 *  [mobile.racemaster.ui.beaconops.RangeCalibrationScreen]'s own doc for why. [shareIntent] is
 *  the one way the data leaves the device, via `FileProvider` — the first use of that pattern in
 *  this app (confirmed nothing else here exports a file), so `AndroidManifest.xml`'s
 *  `<provider>` and `res/xml/file_paths.xml` exist solely for this. */
class RangeSampleLogger(private val context: Context) {
    private val file: File = File(context.filesDir, FILE_NAME)

    fun appendSample(sample: RangeSample) = appendSampleToFile(file, sample)

    fun sampleCount(): Int = sampleCountInFile(file)

    fun clear() {
        file.delete()
    }

    /** Null if nothing's been recorded yet (nothing to share). */
    fun shareIntent(): Intent? {
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share beacon range samples")
    }

    companion object {
        const val FILE_NAME = "beacon-range-samples.csv"
    }
}

// Free functions operating on a plain File, pulled apart from RangeSampleLogger so the actual
// row-formatting/counting logic is testable against a JUnit TemporaryFolder's File directly,
// with no real android.content.Context needed (this codebase has no Robolectric/mockk set up to
// fake one, and nothing else here has needed to).
internal const val RANGE_SAMPLE_CSV_HEADER =
    "timestamp,context,distance_m,beacon_number,measured_power_dbm,rssi_median,rssi_min,rssi_max,sample_count,phone_model,android_sdk"

internal fun appendSampleToFile(file: File, sample: RangeSample) {
    val needsHeader = !file.exists()
    file.appendText(
        buildString {
            if (needsHeader) appendLine(RANGE_SAMPLE_CSV_HEADER)
            appendLine(sample.toCsvRow())
        },
    )
}

internal fun sampleCountInFile(file: File): Int =
    if (file.exists()) (file.readLines().size - 1).coerceAtLeast(0) else 0

private val csvTimestampFormat: SimpleDateFormat by lazy {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

internal fun RangeSample.toCsvRow(): String = listOf(
    csvTimestampFormat.format(Date(timestampMillis)),
    context.name,
    distanceMeters,
    beaconNumber,
    measuredPowerDbm,
    rssiMedian,
    rssiMin,
    rssiMax,
    sampleCount,
    phoneModel.csvEscaped(),
    androidSdk,
).joinToString(",")

private fun String.csvEscaped(): String =
    if (contains(',') || contains('"')) "\"${replace("\"", "\"\"")}\"" else this
