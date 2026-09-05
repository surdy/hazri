package dev.surdy.hazri.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceReportTest {

    @Test
    fun `a real 4x payload parses`() {
        val payload = """
            {"mac":"5a3f1c9d0e42","id":"iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1",
             "name":"pixel-8","rssi@1m":-59,"rssi":-72.35,"rxAdj":20,"rssiVar":1.84,
             "distance":3.42,"var":0.21,"int":1032}
        """.trimIndent()

        val report = EspresenseParser.parseDeviceReport(payload)!!
        assertEquals("iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1", report.id)
        assertEquals("pixel-8", report.name)
        assertEquals(-72.35, report.rssi!!, TOLERANCE)
        assertEquals(-59.0, report.refRssiAtOneMetre!!, TOLERANCE)
        assertEquals(20.0, report.rxAdj!!, TOLERANCE)
        assertEquals(3.42, report.distance!!, TOLERANCE)
        assertEquals(1.84, report.rssiVariance!!, TOLERANCE)
        assertEquals(0.21, report.distanceVariance!!, TOLERANCE)
        assertEquals(1032L, report.interval)
    }

    @Test
    fun `rssi is a float and rounds to the integer the app works in`() {
        assertEquals(-72, EspresenseParser.parseDeviceReport("""{"rssi":-72.35}""")!!.rssiOrNull())
        assertEquals(-73, EspresenseParser.parseDeviceReport("""{"rssi":-72.65}""")!!.rssiOrNull())
    }

    @Test
    fun `raw is preferred when an older firmware sends it`() {
        val report = EspresenseParser.parseDeviceReport("""{"rssi":-72.0,"raw":-75.0}""")!!
        assertEquals(-75.0, report.rawRssi!!, TOLERANCE)
    }

    @Test
    fun `4x payloads have no raw and fall back to rssi`() {
        val report = EspresenseParser.parseDeviceReport("""{"rssi":-72.0}""")!!
        assertNull(report.raw)
        assertEquals(-72.0, report.rawRssi!!, TOLERANCE)
    }

    @Test
    fun `a payload with no rssi at all yields no reading`() {
        val report = EspresenseParser.parseDeviceReport("""{"id":"x","batt":88}""")!!
        assertNull(report.rssiOrNull())
    }

    @Test
    fun `unknown keys are ignored rather than fatal`() {
        val report = EspresenseParser.parseDeviceReport(
            """{"rssi":-60.0,"somethingNew":42,"nested":{"a":1}}"""
        )!!
        assertEquals(-60, report.rssiOrNull())
    }

    @Test
    fun `malformed input parses to null rather than throwing`() {
        assertNull(EspresenseParser.parseDeviceReport(""))
        assertNull(EspresenseParser.parseDeviceReport("not json"))
        assertNull(EspresenseParser.parseDeviceReport("""{"rssi":"""))
        assertNull(EspresenseParser.parseDeviceReport("""{"rssi":"loud"}"""))
        assertNull(EspresenseParser.parseDeviceReport("[1,2,3]"))
    }

    @Test
    fun `a node announces itself in a settings config`() {
        val config = EspresenseParser.parseDeviceConfig(
            """{"id":"node:kitchen","name":"kitchen"}"""
        )!!
        assertEquals("kitchen", config.nodeRoom)
    }

    @Test
    fun `an enrolled device is not a node`() {
        val config = EspresenseParser.parseDeviceConfig(
            """{"id":"iBeacon:abc-1-2","name":"pixel-8"}"""
        )!!
        assertNull(config.nodeRoom)
    }

    @Test
    fun `telemetry parses the fields the tools screen shows`() {
        val telemetry = EspresenseParser.parseTelemetry(
            """{"ip":"10.0.0.31","uptime":81234,"ver":"4.0.6","rssi":-51,"freeHeap":120000,
                "fingerprints":14,"adverts":99231}"""
        )!!
        assertEquals("10.0.0.31", telemetry.ip)
        assertEquals(81234L, telemetry.uptime)
        assertEquals(-51, telemetry.rssi)
        assertEquals(14, telemetry.fingerprints)
    }

    @Test
    fun `an empty telemetry object is valid`() {
        assertTrue(EspresenseParser.parseTelemetry("{}") != null)
        assertNull(EspresenseParser.parseTelemetry("nonsense"))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
