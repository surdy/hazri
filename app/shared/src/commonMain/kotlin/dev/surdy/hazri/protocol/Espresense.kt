package dev.surdy.hazri.protocol

/**
 * ESPresense MQTT topic and setting names.
 *
 * ## What was verified, and against what
 *
 * Checked on 2026-09-04 against `ESPresense/ESPresense` `master` (v4.0.6),
 * <https://espresense.com/configuration/settings>, and this repository's own
 * `docs/espresense-topics.md`, which was verified independently against the same source:
 *
 *  - **Device reports.** `src/main.cpp:reportDevice` publishes to
 *    `espresense/devices/<device id>/<room slug>` — the room is the *last* segment, which
 *    is why [parseDeviceTopic] reads it from the end rather than by index. A second,
 *    longer form, `espresense/devices/<device id>/<room>/<report>`, carries scalar
 *    sub-reports such as a battery query; [parseDeviceTopic] rejects it so one is never
 *    mistaken for a distance reading.
 *  - **Settings.** `src/main.cpp:onMqttMessage` subscribes `espresense/rooms/<slug>/+/set`
 *    and the all-nodes form with a literal star for the room, splits the segment before
 *    `/set` and dispatches it.
 *    `name`, `restart`/`reboot`, `wifi-ssid` and `wifi-password` are handled inline;
 *    `ref_rssi`, `absorption` and `max_distance` fall through to
 *    `BleFingerprintCollection::Command` (`src/BleFingerprintCollection.cpp:404`).
 *  - **There is no `room` setting.** The brief named one; the firmware has none.
 *    `name` is the rename — it rewrites `/room` on the node and re-slugifies every topic
 *    the node uses on the next boot. That is a move, not a tuning, so it is deliberately
 *    absent from [EspresenseSetting] and reachable only through the raw-key overload of
 *    [settingTopic].
 *  - **`*` is a valid room** in a `/set` topic and writes every node at once. [ALL_ROOMS].
 *  - **Telemetry** is `espresense/rooms/<slug>/telemetry`, unretained, at most every 15 s.
 *  - **Retained state.** On connect a node republishes `name`, `max_distance`,
 *    `absorption`, `tx_ref_rssi`, `rx_adj_rssi`, `query`, `include`, `exclude`,
 *    `known_macs`, `known_irks` and `count_ids` retained on
 *    `espresense/rooms/<slug>/<key>` — the `set` topic without the `/set`. `ref_rssi` is
 *    settable but *not* in that snapshot, so a node's current `ref_rssi` cannot be read
 *    back over MQTT — which is why the app records what it pushed instead.
 *  - **Node identity.** Each node advertises a non-connectable iBeacon under
 *    [NODE_BEACON_UUID] with major/minor from its eFuse MAC, and publishes itself retained
 *    on `espresense/settings/iBeacon:<uuid>-<major>-<minor>/config` as
 *    `{"id":"node:<room>","name":"<room>"}`. That pair is the advert-to-room mapping the
 *    direct scanner needs, and [parseSettingsConfigTopic] reads the fingerprint out of it.
 */
object Espresense {
    /** The root of every topic. ESPresense calls this `CHANNEL`; it is not configurable. */
    const val CHANNEL: String = "espresense"

    /** The room that means "every node" in a `/set` topic. */
    const val ALL_ROOMS: String = "*"

    /**
     * The proximity UUID every ESPresense node advertises its own iBeacon under.
     * Constant across the fleet — major and minor are what distinguish two nodes.
     */
    const val NODE_BEACON_UUID: String = "e5ca1ade-f007-ba11-0000-000000000000"

    /** The `id` prefix a node uses for itself in a settings config payload. */
    const val NODE_ID_PREFIX: String = "node:"

    /** Topic a node publishes what it hears about one device on. */
    fun deviceTopic(deviceId: String, room: String): String =
        "$CHANNEL/devices/$deviceId/${slugifyRoom(room)}"

    /** Wildcard subscription for every room's report about one device. */
    fun deviceSubscription(deviceId: String): String = "$CHANNEL/devices/$deviceId/#"

    /** Subscription for every node's telemetry. */
    fun telemetrySubscription(): String = "$CHANNEL/rooms/+/telemetry"

    /** Subscription for the retained device-identity configs, nodes' own included. */
    fun settingsSubscription(): String = "$CHANNEL/settings/+/config"

    /** Topic that writes one setting on one node, or on every node when [room] is [ALL_ROOMS]. */
    fun settingTopic(room: String, setting: EspresenseSetting): String =
        settingTopic(room, setting.key)

    /** [settingTopic] by raw key, for every setting outside [EspresenseSetting]. */
    fun settingTopic(room: String, key: String): String =
        "$CHANNEL/rooms/${roomSegment(room)}/$key/set"

    /** Topic a node publishes [NodeTelemetry] on. */
    fun telemetryTopic(room: String): String = "$CHANNEL/rooms/${slugifyRoom(room)}/telemetry"

    /** Topic a node publishes `online`/`offline` on. Retained, and the node's last will. */
    fun statusTopic(room: String): String = "$CHANNEL/rooms/${slugifyRoom(room)}/status"

    /** The retained identity topic for a device fingerprint. */
    fun settingsConfigTopic(fingerprint: String): String = "$CHANNEL/settings/$fingerprint/config"

    /** The fingerprint an ESPresense node advertises itself under. */
    fun nodeFingerprint(major: Int, minor: Int): String =
        "iBeacon:$NODE_BEACON_UUID-$major-$minor"

    /**
     * Splits `espresense/devices/<device id>/<room>` into its two parts.
     *
     * Returns `null` for any other topic, including the four-segment sub-report form and
     * anything under `espresense/rooms/`.
     */
    fun parseDeviceTopic(topic: String): DeviceTopic? {
        val parts = topic.split('/')
        if (parts.size != 4) return null
        if (parts[0] != CHANNEL || parts[1] != "devices") return null
        if (parts[2].isBlank() || parts[3].isBlank()) return null
        return DeviceTopic(deviceId = parts[2], room = parts[3])
    }

    /** Reads the room out of `espresense/rooms/<room>/telemetry`, or `null`. */
    fun parseTelemetryTopic(topic: String): String? = parseRoomTopic(topic, "telemetry")

    /** Reads the room out of `espresense/rooms/<room>/status`, or `null`. */
    fun parseStatusTopic(topic: String): String? = parseRoomTopic(topic, "status")

    /** Reads the fingerprint out of `espresense/settings/<fingerprint>/config`, or `null`. */
    fun parseSettingsConfigTopic(topic: String): String? {
        val parts = topic.split('/')
        if (parts.size != 4) return null
        if (parts[0] != CHANNEL || parts[1] != "settings" || parts[3] != "config") return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    /**
     * Splits `iBeacon:<uuid>-<major>-<minor>` into its three parts, or returns `null`.
     *
     * The UUID itself contains hyphens, so this counts from the end rather than splitting.
     */
    fun parseBeaconFingerprint(fingerprint: String): BeaconFingerprint? {
        if (!fingerprint.startsWith(BEACON_PREFIX)) return null
        val body = fingerprint.removePrefix(BEACON_PREFIX)
        val minorAt = body.lastIndexOf('-')
        if (minorAt <= 0) return null
        val majorAt = body.lastIndexOf('-', minorAt - 1)
        if (majorAt <= 0) return null
        val minor = body.substring(minorAt + 1).toIntOrNull() ?: return null
        val major = body.substring(majorAt + 1, minorAt).toIntOrNull() ?: return null
        val uuid = body.substring(0, majorAt).lowercase()
        if (uuid.isBlank()) return null
        return BeaconFingerprint(uuid = uuid, major = major, minor = minor)
    }

    /**
     * The node's own `slugify` lives in a library this project does not vendor, so this is
     * the conservative reading of it: lower case, every run of characters outside
     * `[a-z0-9]` collapsed to a single `_`, and no leading or trailing `_`.
     *
     * It agrees with the firmware for every name the plan's convention allows (one
     * lower-case word). "Living Room" is where the two could disagree, which is why the
     * plan says to name nodes `living`, not `Living Room`.
     */
    fun slugifyRoom(room: String): String =
        room.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')

    private fun roomSegment(room: String): String =
        if (room == ALL_ROOMS) ALL_ROOMS else slugifyRoom(room)

    private fun parseRoomTopic(topic: String, leaf: String): String? {
        val parts = topic.split('/')
        if (parts.size != 4) return null
        if (parts[0] != CHANNEL || parts[1] != "rooms" || parts[3] != leaf) return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    private const val BEACON_PREFIX: String = "iBeacon:"
}

/** The two halves of an `espresense/devices/<device id>/<room>` topic. */
data class DeviceTopic(val deviceId: String, val room: String)

/** The three parts of an `iBeacon:<uuid>-<major>-<minor>` fingerprint. */
data class BeaconFingerprint(val uuid: String, val major: Int, val minor: Int)

/**
 * The settings Hazri writes, with the key as the firmware spells it.
 *
 * Only the three the Node detail screen edits. There is deliberately no `name`: renaming a
 * node rewrites `/room` on its filesystem and re-slugifies every topic it uses on the next
 * boot, which is a move rather than a tuning, and nothing in this app should be one button
 * press away from it. ESPresense has around twenty more settings
 * (`skip_ms`, `query`, `include`, `count_enter`, …); they are outside this app's job,
 * which is placement and per-node distance tuning. Anything else can still be pushed
 * through the raw-key overload of [Espresense.settingTopic].
 */
enum class EspresenseSetting(val key: String, val description: String) {
    /**
     * RSSI a node expects from a 0 dBm transmitter at one metre. Firmware default `-65`
     * (`DEFAULT_RX_REF_RSSI` in `include/defaults.h`). Settable, never published back.
     * Ignored for iBeacons and Eddystone, which carry their own calibrated power.
     */
    REF_RSSI("ref_rssi", "RSSI at 1 m"),

    /** Path-loss exponent, 1-5. Firmware default `2.7`. Higher means signal falls off faster. */
    ABSORPTION("absorption", "Path-loss exponent"),

    /** Metres beyond which the node drops a reading. Firmware default `16.0`. */
    MAX_DISTANCE("max_distance", "Report cut-off, metres"),
    ;

    companion object {
        /** The three settings that are safe to push together as a tuning block. */
        val TUNING: List<EspresenseSetting> = listOf(REF_RSSI, ABSORPTION, MAX_DISTANCE)

        /** The setting with this exact key, or `null`. */
        fun byKey(key: String): EspresenseSetting? = entries.firstOrNull { it.key == key }
    }
}
