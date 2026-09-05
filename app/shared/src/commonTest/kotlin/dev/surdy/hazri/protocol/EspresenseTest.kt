package dev.surdy.hazri.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EspresenseTest {

    @Test
    fun `a device topic keeps the fingerprint whole and slugifies only the room`() {
        assertEquals(
            "espresense/devices/iBeacon:abc-100-1/living_room",
            Espresense.deviceTopic("iBeacon:abc-100-1", "Living Room"),
        )
    }

    @Test
    fun `a device topic parses into fingerprint and room`() {
        val parsed = Espresense.parseDeviceTopic("espresense/devices/apple:iphone10-1/kitchen")!!
        assertEquals("apple:iphone10-1", parsed.deviceId)
        assertEquals("kitchen", parsed.room)
    }

    @Test
    fun `a five-segment sub-report is not a device reading`() {
        // espresense/devices/<id>/<room>/<report> carries scalars such as a battery level.
        assertNull(Espresense.parseDeviceTopic("espresense/devices/id/kitchen/battery"))
    }

    @Test
    fun `topics from other namespaces are rejected`() {
        assertNull(Espresense.parseDeviceTopic("espresense/rooms/kitchen/telemetry"))
        assertNull(Espresense.parseDeviceTopic("homeassistant/sensor/x/config"))
        assertNull(Espresense.parseDeviceTopic("espresense/devices//kitchen"))
        assertNull(Espresense.parseDeviceTopic(""))
    }

    @Test
    fun `setting topics use the firmware's own keys`() {
        assertEquals(
            "espresense/rooms/kitchen/ref_rssi/set",
            Espresense.settingTopic("kitchen", EspresenseSetting.REF_RSSI),
        )
        assertEquals(
            "espresense/rooms/kitchen/absorption/set",
            Espresense.settingTopic("Kitchen", EspresenseSetting.ABSORPTION),
        )
        assertEquals(
            "espresense/rooms/kitchen/max_distance/set",
            Espresense.settingTopic("kitchen", EspresenseSetting.MAX_DISTANCE),
        )
    }

    @Test
    fun `a wildcard room is passed through rather than slugified`() {
        assertEquals(
            "espresense/rooms/*/absorption/set",
            Espresense.settingTopic(Espresense.ALL_ROOMS, EspresenseSetting.ABSORPTION),
        )
    }

    @Test
    fun `a setting outside the enum is still addressable by raw key`() {
        // `name` is deliberately absent from EspresenseSetting: it renames the node rather
        // than tuning it. It has to remain reachable, and spelled the firmware's way.
        assertEquals(
            "espresense/rooms/hall/name/set",
            Espresense.settingTopic("hall", "name"),
        )
    }

    @Test
    fun `telemetry and status topics parse back to a room`() {
        assertEquals("hall", Espresense.parseTelemetryTopic(Espresense.telemetryTopic("hall")))
        assertEquals("hall", Espresense.parseStatusTopic(Espresense.statusTopic("hall")))
        assertNull(Espresense.parseTelemetryTopic("espresense/rooms/hall/status"))
    }

    @Test
    fun `a settings config topic yields the fingerprint`() {
        assertEquals(
            "iBeacon:e5ca1ade-f007-ba11-0000-000000000000-1-2",
            Espresense.parseSettingsConfigTopic(
                "espresense/settings/iBeacon:e5ca1ade-f007-ba11-0000-000000000000-1-2/config"
            ),
        )
        assertNull(Espresense.parseSettingsConfigTopic("espresense/settings/x/state"))
    }

    @Test
    fun `a beacon fingerprint splits on the last two hyphens`() {
        // The UUID itself contains hyphens, so a naive split would take it apart.
        val parsed = Espresense.parseBeaconFingerprint(Espresense.nodeFingerprint(41, 7))!!
        assertEquals(Espresense.NODE_BEACON_UUID, parsed.uuid)
        assertEquals(41, parsed.major)
        assertEquals(7, parsed.minor)
    }

    @Test
    fun `a malformed fingerprint parses to nothing`() {
        assertNull(Espresense.parseBeaconFingerprint("irk:0123456789abcdef"))
        assertNull(Espresense.parseBeaconFingerprint("iBeacon:uuid-only"))
        assertNull(Espresense.parseBeaconFingerprint("iBeacon:uuid-x-y"))
    }

    @Test
    fun `slugify lowercases and collapses everything else to one underscore`() {
        assertEquals("living_room", Espresense.slugifyRoom("Living Room"))
        assertEquals("hall", Espresense.slugifyRoom("  Hall  "))
        assertEquals("under_stairs", Espresense.slugifyRoom("Under--Stairs"))
        assertEquals("bed2", Espresense.slugifyRoom("Bed2"))
    }

    @Test
    fun `settings are addressable by key`() {
        assertEquals(EspresenseSetting.REF_RSSI, EspresenseSetting.byKey("ref_rssi"))
        assertNull(EspresenseSetting.byKey("room"))
        assertNull(EspresenseSetting.byKey("name"))
        assertEquals(3, EspresenseSetting.TUNING.size)
        assertEquals(EspresenseSetting.entries.size, EspresenseSetting.TUNING.size)
    }
}
