package mobile.racemaster.data.beacon

import com.juul.kable.Advertisement

/**
 * A race beacon identified purely from its advertisement — no GATT connect needed. Decodes
 * [DxCp27Protocol.raceDeviceName]'s own format straight off the scan's device-name AD field via
 * [DxCp27Protocol.parseRaceDeviceName]. This is the real identity scheme the hardware settled on
 * (see docs/beacon-detection-plan.md's 2026-11-05 staleness flag): an earlier design assumed a
 * generic Apple-iBeacon manufacturer-data fob, written while the plan was parked waiting on
 * AliExpress fobs to be acquired, but the DX-CP27 beacons actually bought/programmed carry their
 * identity in the device name instead ([DxCp27Protocol.RACE_SLOT]'s own doc).
 */
data class DetectedRaceBeacon(
    val address: String,
    val beaconNumber: Int,
    val measuredPowerDbm: Int,
    val rssi: Int,
) {
    /** AltBeacon/iBeacon-style empirical curve-fit estimate, fed [measuredPowerDbm] (this
     *  beacon's own 1-metre calibration reading, entered via Setup Beacons' own Calibrate button
     *  and encoded straight into the device name) — see [BeaconScanDecoder.estimateDistanceMeters]
     *  for why this replaced the original fixed-exponent path-loss formula, and TODO.md's own
     *  "tune against real field data" phase-3 item for what's still open. */
    val estimatedDistanceMeters: Double
        get() = BeaconScanDecoder.estimateDistanceMeters(measuredPowerDbm, rssi)
}

object BeaconScanDecoder {
    /** Thin wrapper over [decode] for the real scan path — pulled apart from it so the actual
     *  decoding logic stays testable against plain synthetic name/rssi/address values (see
     *  [BeaconScanDecoderTest]) without needing a real or mocked Kable [Advertisement], which
     *  wraps a platform `ScanResult` this JVM-only test suite has no way to construct. */
    fun decodeFromAdvertisement(advertisement: Advertisement): DetectedRaceBeacon? =
        decode(advertisement.name, advertisement.rssi, advertisement.identifier)

    /** Null for any advertisement that either carries no device name at all, or one not shaped
     *  like [DxCp27Protocol.raceDeviceName]'s format (a factory-default beacon, something else
     *  entirely, or a DX-CP27 this app hasn't programmed for a race yet). */
    fun decode(name: String?, rssi: Int, address: String): DetectedRaceBeacon? {
        val nonNullName = name ?: return null
        val (beaconNumber, measuredPowerDbm) = DxCp27Protocol.parseRaceDeviceName(nonNullName) ?: return null
        return DetectedRaceBeacon(
            address = address,
            beaconNumber = beaconNumber,
            measuredPowerDbm = measuredPowerDbm,
            rssi = rssi,
        )
    }

    /** Was a textbook log-distance path-loss formula with a fixed `n=2` free-space exponent —
     *  confirmed grossly inaccurate in the field (see docs/beacon-detection-plan.md's "Range
     *  calibration data collection" section, and the Beacons > Range Calibration screen built to
     *  gather the real data needed to fit a better one). Until that data exists, this uses the
     *  empirical curve AltBeacon/iBeacon ranging itself uses instead of a theoretical formula —
     *  [Radius Networks' CurveFittedDistanceCalculator](https://developer.radiusnetworks.com/2014/12/04/fundamentals-of-beacon-ranging.html),
     *  fitted against real (RSSI, distance) measurements rather than derived from physics. Still
     *  borrowed constants (originally fitted on a Nexus 4/iPhone 4S, not this app's own hardware),
     *  so an interim improvement, not the final model. [ratio] is `rssi / measuredPowerDbm` (both
     *  negative dBm, so a positive ratio): below 1.0 the current reading is stronger than the
     *  beacon's own 1m calibration reading, so distance shrinks below 1m. */
    fun estimateDistanceMeters(measuredPowerDbm: Int, rssi: Int): Double =
        estimateDistanceMeters(measuredPowerDbm, rssi.toDouble())

    /** [rssi] as a `Double` so a smoothed (e.g. EMA'd) reading — see
     *  [mobile.racemaster.ui.beaconidentify.IdentifyFobViewModel]'s own `smoothedRssi` — can feed
     *  this without rounding first and reintroducing the jitter the smoothing was meant to kill. */
    fun estimateDistanceMeters(measuredPowerDbm: Int, rssi: Double): Double {
        val ratio = rssi / measuredPowerDbm
        return if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
    }
}
