package dev.surdy.hazri.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the broker is and who Hazri connects as. */
data class BrokerConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String? = null,
    val password: String? = null,
    val clientId: String = DEFAULT_CLIENT_ID,
) {
    companion object {
        const val DEFAULT_PORT: Int = 1883
        const val DEFAULT_CLIENT_ID: String = "hazri"
    }
}

/** One message off the wire, exactly as received. Payload is UTF-8 text; ESPresense sends JSON. */
data class MqttMessage(
    val topic: String,
    val payload: String,
    val receivedAt: Long,
)

/** What the broker connection is doing. */
sealed interface MqttConnectionState {
    data object Disconnected : MqttConnectionState
    data object Connecting : MqttConnectionState
    data class Connected(val host: String, val port: Int) : MqttConnectionState
    data class Failed(val reason: String) : MqttConnectionState
}

/**
 * A broker connection, reduced to what Hazri needs: subscribe to a wildcard, read messages,
 * publish a setting.
 *
 * An interface rather than an `expect class` because the only Android-specific thing about
 * it is the client library. When a multiplatform MQTT client is proven out, it replaces the
 * implementation and nothing above this line changes.
 */
interface MqttGateway {
    /** Current connection state. */
    val connection: StateFlow<MqttConnectionState>

    /** Every message on every subscribed topic. Hot; nothing is replayed. */
    val messages: Flow<MqttMessage>

    /** Connects, or moves [connection] to [MqttConnectionState.Failed]. Does not throw. */
    suspend fun connect(config: BrokerConfig)

    /** Subscribes to a topic filter. Filters survive a reconnect. */
    suspend fun subscribe(topicFilter: String)

    /** Publishes [payload] to [topic]. Returns whether the broker accepted it. */
    suspend fun publish(topic: String, payload: String, retain: Boolean = false): Boolean

    /** Disconnects and forgets the subscriptions. */
    suspend fun disconnect()
}
