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
    fun estimatedDistanceIsAboutOneMetreWhenRssiMatchesMeasuredPower() {
        // AltBeacon's curve isn't pinned to exactly 1.0 at ratio=1.0 the way the old n=2 formula
        // was — it's a fit against real measurements, not an identity.
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -59)
        assertEquals(1.01076, beacon.estimatedDistanceMeters, 1e-5)
    }

    @Test
    fun estimatedDistanceGrowsAsTheSignalWeakensBeyondTheOneMetreReading() {
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -79)
        assertEquals(8.65181, beacon.estimatedDistanceMeters, 1e-5)
    }

    @Test
    fun estimatedDistanceShrinksSharplyWhenTheSignalIsStrongerThanTheOneMetreReading() {
        // The curve's ratio^10 branch below 1.0 falls off far more steeply than the old n=2
        // formula did — a beacon held right up to the phone reads as a couple of centimetres,
        // not a tenth of a metre.
        val beacon = DetectedRaceBeacon(address = "x", beaconNumber = 1, measuredPowerDbm = -59, rssi = -39)
        assertEquals(0.01593, beacon.estimatedDistanceMeters, 1e-5)
    }
}
