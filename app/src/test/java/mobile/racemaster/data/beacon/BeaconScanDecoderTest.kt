package mobile.racemaster.data.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconScanDecoderTest {
    @Test
    fun decodesARaceProgrammedNameIntoBeaconNumberMeasuredPowerAndRssi() {
        val beacon = BeaconScanDecoder.decode(name = "RM.007-71", rssi = -71, address = "AA:BB:CC:DD:EE:FF")
        assertEquals(DetectedRaceBeacon(address = "AA:BB:CC:DD:EE:FF", beaconNumber = 7, measuredPowerDbm = -71, rssi = -71), beacon)
    }

    @Test
    fun returnsNullForAFactoryOrUnrelatedName() {
        assertNull(BeaconScanDecoder.decode(name = "CP27-4236", rssi = -60, address = "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun returnsNullWhenNoNameWasAdvertisedAtAll() {
        assertNull(BeaconScanDecoder.decode(name = null, rssi = -60, address = "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun estimatedDistanceIsOneMetreWhenRssiMatchesMeasuredPower() {
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -59)
        assertEquals(1.0, beacon.estimatedDistanceMeters, 1e-9)
    }

    @Test
    fun estimatedDistanceGrowsAsTheSignalWeakensBeyondTheOneMetreReading() {
        // n=2 free-space exponent: a 20dB weaker signal than the 1m reading is 10x further away.
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -79)
        assertEquals(10.0, beacon.estimatedDistanceMeters, 1e-9)
    }

    @Test
    fun estimatedDistanceShrinksWhenTheSignalIsStrongerThanTheOneMetreReading() {
        // 20dB stronger than the 1m reading (e.g. the beacon held right up to the phone) implies
        // a tenth of a metre.
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -39)
        assertEquals(0.1, beacon.estimatedDistanceMeters, 1e-9)
    }
}
