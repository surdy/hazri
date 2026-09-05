package dev.surdy.hazri.source

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An [MqttGateway] backed by the HiveMQ MQTT client.
 *
 * JVM-only, which is why it lives in `androidMain` and behind an interface. HiveMQ was
 * chosen over a multiplatform client because it is the one that compiles cleanly on
 * Android today; when a multiplatform client proves out, it replaces this file and nothing
 * in `commonMain` changes.
 *
 * MQTT 3.1.1 rather than 5: Mosquitto's Home Assistant add-on speaks both, ESPresense uses
 * none of 5's features, and 3.1.1 is the version every broker in a homelab accepts.
 *
 * Subscriptions are remembered so that the client's automatic reconnect restores them, and
 * the client's connected and disconnected callbacks are mapped onto [connection] so that a
 * broker going away is visible rather than leaving the last good state on screen.
 */
class HiveMqGateway(
    private val clock: MillisClock = SystemClock,
) : MqttGateway {

    private val state = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    override val connection: StateFlow<MqttConnectionState> = state.asStateFlow()

    private val received = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 256)
    override val messages: Flow<MqttMessage> = received.asSharedFlow()

    private var client: Mqtt3AsyncClient? = null
    private val subscriptions = LinkedHashSet<String>()

    override suspend fun connect(config: BrokerConfig) {
        disconnect()
        state.value = MqttConnectionState.Connecting

        val built = MqttClient.builder()
            .useMqttVersion3()
            .identifier("${config.clientId}-${clock.now()}")
            .serverHost(config.host)
            .serverPort(config.port)
            .automaticReconnectWithDefaultConfig()
            // Without this the state stays Connected after the broker goes away: the client
            // reconnects in the background and nothing above it ever hears that it dropped,
            // so the Tools pill stays green through an outage.
            .addConnectedListener { state.value = MqttConnectionState.Connected(config.host, config.port) }
            .addDisconnectedListener { context ->
                state.value = if (context.reconnector.isReconnect) {
                    MqttConnectionState.Connecting
                } else {
                    MqttConnectionState.Disconnected
                }
            }
            .buildAsync()

        val connect = built.connectWith().let { builder ->
            val user = config.username
            val pass = config.password
            if (user.isNullOrBlank()) {
                builder
            } else {
                builder.simpleAuth()
                    .username(user)
                    .password((pass ?: "").encodeToByteArray())
                    .applySimpleAuth()
            }
        }

        runCatching { connect.send().await() }
            .onSuccess {
                client = built
                state.value = MqttConnectionState.Connected(config.host, config.port)
                subscriptions.forEach { resubscribe(built, it) }
            }
            .onFailure { cause ->
                state.value = MqttConnectionState.Failed(cause.message ?: "Connection refused")
            }
    }

    override suspend fun subscribe(topicFilter: String) {
        subscriptions += topicFilter
        val active = client ?: return
        runCatching { resubscribe(active, topicFilter) }
    }

    override suspend fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        val active = client ?: return false
        return runCatching {
            active.publishWith()
                .topic(topic)
                .payload(payload.encodeToByteArray())
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
                .await()
            true
        }.getOrDefault(false)
    }

    override suspend fun disconnect() {
        val active = client ?: return
        client = null
        runCatching { active.disconnect().await() }
        state.value = MqttConnectionState.Disconnected
    }

    private fun resubscribe(active: Mqtt3AsyncClient, topicFilter: String) {
        active.subscribeWith()
            .topicFilter(topicFilter)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish ->
                received.tryEmit(
                    MqttMessage(
                        topic = publish.topic.toString(),
                        payload = publish.payloadAsBytes.decodeToString(),
                        receivedAt = clock.now(),
                    )
                )
            }
            .send()
    }

    /** Bridges HiveMQ's [CompletableFuture] API onto coroutines without a JDK8 artifact. */
    private suspend fun <T> CompletableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, error ->
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(value)
            }
            continuation.invokeOnCancellation { cancel(true) }
        }
}
