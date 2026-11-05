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
    /** Log-distance path-loss estimate — the same family of formula Apple's own iBeacon ranging
     *  uses, just fed [measuredPowerDbm] (this beacon's own 1-metre calibration reading, entered
     *  via Setup Beacons' own Calibrate button and encoded straight into the device name) instead
     *  of a hardcoded manufacturer constant. Uses a fixed free-space exponent
     *  ([BeaconScanDecoder.PATH_LOSS_EXPONENT]), not one recalibrated per environment — a
     *  reasonable starting point, not a final value; see TODO.md's own "tune path-loss exponent
     *  against real field data" phase-3 item. */
    val estimatedDistanceMeters: Double
        get() = BeaconScanDecoder.estimateDistanceMeters(measuredPowerDbm, rssi)
}

object BeaconScanDecoder {
    const val PATH_LOSS_EXPONENT = 2.0

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

    /** `10 ^ ((measuredPowerDbm - rssi) / (10 * n))` — equals exactly 1.0m when [rssi] matches
     *  [measuredPowerDbm] (the beacon's own 1m calibration reading), grows as the signal weakens
     *  further than that. */
    fun estimateDistanceMeters(measuredPowerDbm: Int, rssi: Int): Double =
        estimateDistanceMeters(measuredPowerDbm, rssi.toDouble())

    /** [rssi] as a `Double` so a smoothed (e.g. EMA'd) reading — see
     *  [mobile.racemaster.ui.beaconidentify.IdentifyFobViewModel]'s own `smoothedRssi` — can feed
     *  this without rounding first and reintroducing the jitter the smoothing was meant to kill. */
    fun estimateDistanceMeters(measuredPowerDbm: Int, rssi: Double): Double =
        Math.pow(10.0, (measuredPowerDbm - rssi) / (10.0 * PATH_LOSS_EXPONENT))
}
