package dev.surdy.hazri.data

import dev.surdy.hazri.domain.RssiSmoother
import dev.surdy.hazri.domain.VerdictThresholds
import dev.surdy.hazri.source.BrokerConfig
import kotlinx.serialization.Serializable

/** Which [dev.surdy.hazri.source.SignalSource] is feeding the app. */
enum class SourceKind {
    DIRECT,
    MQTT,
    SIMULATED,
}

/** Broker coordinates, as stored. Mirrors [BrokerConfig] without depending on it. */
@Serializable
data class BrokerSettings(
    val host: String = DEFAULT_HOST,
    val port: Int = BrokerConfig.DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
) {
    /** The runtime form, with blank credentials read as absent. */
    fun toConfig(clientId: String = BrokerConfig.DEFAULT_CLIENT_ID): BrokerConfig = BrokerConfig(
        host = host,
        port = port,
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        clientId = clientId,
    )

    /** Whether there is enough here to attempt a connection. */
    val isConfigured: Boolean get() = host.isNotBlank() && port in 1..65535

    companion object {
        /**
         * The broker the repository's own simulator runs on (`tools/espresense-sim`), so a
         * fresh install can be pointed at MQTT mode without typing anything.
         *
         * On the Android emulator this must be `10.0.2.2` instead — `localhost` inside the
         * emulator is the emulated device, not the machine running the simulator. See
         * `app/README.md`.
         */
        const val DEFAULT_HOST: String = "localhost"
    }
}

/**
 * Everything the Settings screen edits.
 *
 * [phoneId] is the ESPresense fingerprint the phone advertises under — the third segment
 * of `espresense/devices/<id>/<room>`. It comes from whatever is doing the advertising,
 * normally the Home Assistant Companion app's BLE transmitter, and Hazri cannot discover
 * it: the phone cannot hear its own advertisement.
 */
@Serializable
data class AppSettings(
    val sourceKind: SourceKind = SourceKind.SIMULATED,
    val broker: BrokerSettings = BrokerSettings(),
    val phoneId: String = DEFAULT_PHONE_ID,
    val smoothingAlpha: Double = RssiSmoother.DEFAULT_ALPHA,
    val medianWindow: Int = RssiSmoother.DEFAULT_MEDIAN_WINDOW,
    val mqttAlpha: Double = DEFAULT_MQTT_ALPHA,
    val marginDb: Double = VerdictThresholds.DEFAULT_MARGIN_DB,
    val floorDbm: Double = VerdictThresholds.DEFAULT_FLOOR_DBM,
) {
    /** The thresholds these settings imply. */
    fun thresholds(): VerdictThresholds = VerdictThresholds(marginDb, floorDbm)

    /**
     * A smoother configured for [kind]. One per node.
     *
     * MQTT gets a different one, and this is not a preference. The `rssi` in a device
     * report is already ESPresense's own filtered value — a Tukey-fenced mean over a 15 s
     * window, computed on the node — so running Hazri's median-of-five and alpha-0.2 EMA
     * over it again would produce a number that lags reality by the better part of a
     * minute. The median window is dropped to 1 and the EMA weight raised to
     * [DEFAULT_MQTT_ALPHA], which does nothing but take the edge off the step changes that
     * arrive when the node re-publishes.
     *
     * Direct scan and the simulator get the full treatment, because those samples are raw.
     *
     * The stats window differs for the same reason. ESPresense publishes about every 5 s
     * (`skip_ms` defaults to 5000), so a 10 s window holds one or two readings: the packet
     * rate rounds to zero and sigma comes out 0.0 on every card. [MQTT_STATS_WINDOW_MILLIS]
     * is a minute, which is a dozen reports, and the rate is shown per minute rather than
     * per second — see [rateUnit].
     */
    fun newSmoother(kind: SourceKind = SourceKind.DIRECT): RssiSmoother = when (kind) {
        SourceKind.MQTT -> RssiSmoother(
            alpha = mqttAlpha,
            medianWindow = 1,
            statsWindowMillis = MQTT_STATS_WINDOW_MILLIS,
        )
        SourceKind.DIRECT, SourceKind.SIMULATED -> RssiSmoother(
            alpha = smoothingAlpha,
            medianWindow = medianWindow,
            statsWindowMillis = RssiSmoother.DEFAULT_STATS_WINDOW_MILLIS,
        )
    }

    companion object {
        /** EMA weight used on MQTT samples, which arrive already smoothed by the node. */
        const val DEFAULT_MQTT_ALPHA: Double = 0.6

        /** Stats window for MQTT: long enough to hold a dozen of the node's 5 s reports. */
        const val MQTT_STATS_WINDOW_MILLIS: Long = 60_000L

        /**
         * How a packet rate should be spelled for [kind], and what to multiply the
         * per-second figure by to get there.
         *
         * MQTT counts node reports, which arrive every few seconds; per second they read as
         * zero. Direct and simulated count advertisements, which arrive many times a second.
         */
        fun rateUnit(kind: SourceKind): RateUnit = when (kind) {
            SourceKind.MQTT -> RateUnit("/min", 60.0)
            SourceKind.DIRECT, SourceKind.SIMULATED -> RateUnit("pkt/s", 1.0)
        }

        /**
         * The fingerprint `tools/espresense-sim` publishes under, so MQTT mode works out of
         * the box against it. Replace it with the real phone's once the Home Assistant
         * Companion app's BLE transmitter is configured — the two will not match.
         */
        const val DEFAULT_PHONE_ID: String =
            "iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1"

        val DEFAULT: AppSettings = AppSettings()
    }
}

/** How a packet rate is spelled on screen, and the factor that gets it there from per-second. */
data class RateUnit(val label: String, val perSecondFactor: Double) {
    /** [perSecond] in this unit. */
    fun convert(perSecond: Double): Double = perSecond * perSecondFactor
}
