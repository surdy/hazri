package dev.surdy.hazri.vm

import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.DemoSeed
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.protocol.EspresenseSetting
import dev.surdy.hazri.source.DirectScanSource
import dev.surdy.hazri.source.MillisClock
import dev.surdy.hazri.source.MqttSignalSource
import dev.surdy.hazri.source.SignalSource
import dev.surdy.hazri.source.SimulatedSignalSource
import dev.surdy.hazri.source.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the platform-specific pieces the shared code cannot.
 *
 * Three methods, one per source. `null` means "this platform cannot do that" — a desktop
 * build with no Bluetooth returns `null` from [directScan] and the source picker greys the
 * option out rather than offering something that will fail.
 */
interface SourceFactory {
    /** A BLE scanner, or `null` if this platform has none. */
    fun directScan(scope: CoroutineScope): SignalSource?

    /** An MQTT source for [phoneId], or `null` if this platform has no MQTT client. */
    fun mqtt(scope: CoroutineScope, settings: AppSettings): MqttSignalSource?

    /** The simulator. Always available; the default in debug builds. */
    fun simulated(scope: CoroutineScope): SimulatedSignalSource
}

/**
 * The object graph.
 *
 * Hand-wired rather than Koin. There are seven objects and one of them is a repository —
 * a DI container here would be more code than the wiring it replaced, and the wiring is
 * the thing a reader most wants to be able to follow.
 */
class AppContainer(
    val repository: HazriRepository,
    private val sources: SourceFactory,
    val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
    /** Whether the simulated source is offered. Debug builds only. */
    val simulationAvailable: Boolean = true,
    /**
     * What keeps a recording collecting while the app is backgrounded.
     *
     * The no-op default is the right one everywhere except Android, where it is the handle
     * on a foreground service. See [SurveyKeepAlive].
     */
    keepAlive: SurveyKeepAlive = SurveyKeepAlive.None,
) {
    val engine: SignalEngine = SignalEngine(repository, scope, clock)

    private var mqttSource: MqttSignalSource? = null
    private var started = false

    val survey: SurveyViewModel = SurveyViewModel(repository, engine, scope, clock, keepAlive)
    val coverage: CoverageViewModel = CoverageViewModel(repository, scope)
    val tools: ToolsViewModel = ToolsViewModel(repository, engine, scope)
    val settings: SettingsViewModel = SettingsViewModel(repository, ::switchSource)

    /** The live MQTT source, if MQTT mode has ever been selected. */
    val mqtt: MqttSignalSource? get() = mqttSource

    /** Publishes a node's tuning block. Fails cleanly when MQTT is not connected. */
    val publisher: SettingPublisher = SettingPublisher { config -> pushTuning(config) }

    /** Which sources this build can actually offer, in the order the picker shows them. */
    fun availableSources(): List<SourceKind> = buildList {
        add(SourceKind.DIRECT)
        add(SourceKind.MQTT)
        if (simulationAvailable) add(SourceKind.SIMULATED)
    }

    /**
     * The source to actually run for [requested].
     *
     * `SIMULATED` is the shipped default because a debug build has to open onto a populated
     * app. A release build has no simulator to offer, and a persisted `SIMULATED` — from a
     * debug install, or an upgrade — would otherwise start the release app on invented data
     * with nothing on screen saying so. It falls back to a direct scan.
     */
    fun resolveSource(requested: SourceKind): SourceKind =
        if (requested == SourceKind.SIMULATED && !simulationAvailable) SourceKind.DIRECT
        else requested

    /**
     * Seeds an empty install and starts the source the settings name.
     *
     * Idempotent, and called from the UI rather than from wherever the process happened to
     * be built. A process started for a content-provider read or a service restart has no
     * screen and nothing that would ever stop a scan it began, so the graph is constructed
     * eagerly and started only when something is going to look at it.
     */
    fun start() {
        if (started) return
        started = true
        if (simulationAvailable) DemoSeed.seedIfEmpty(repository, clock.now())
        scope.launch { switchSource(repository.settings.value.sourceKind) }
    }

    /** A node detail view model. Created per navigation, discarded with the screen. */
    fun nodeDetail(nodeId: NodeId): NodeDetailViewModel =
        NodeDetailViewModel(nodeId, repository, engine, publisher, scope, clock)

    /** A second simulated source for Compare sources, seeded differently from the first. */
    fun comparisonSource(): SignalSource =
        SimulatedSignalSource(seed = COMPARISON_SEED, scope = scope, clock = clock)

    /**
     * Switches the running source, persisting the choice.
     *
     * A source this build or this device cannot provide leaves the previous one running and
     * reports why, rather than silently doing nothing.
     */
    suspend fun switchSource(kind: SourceKind) {
        val resolved = resolveSource(kind)
        val next = when (resolved) {
            SourceKind.DIRECT -> sources.directScan(scope)
            SourceKind.MQTT -> mqttFor(repository.settings.value)
            SourceKind.SIMULATED -> sources.simulated(scope)
        }
        if (next == null) {
            engine.reportError(unavailableReason(resolved))
            return
        }
        if (resolved != SourceKind.MQTT) releaseMqtt()
        if (next is DirectScanSource) tools.observeScanner(next.unidentified, next::clearUnidentified)
        else tools.stopObservingScanner()
        repository.updateSettings { it.copy(sourceKind = resolved) }
        engine.useSource(resolved, next)
    }

    private fun unavailableReason(kind: SourceKind): String = when (kind) {
        SourceKind.DIRECT -> "Bluetooth scanning is not available. Grant the scan permission " +
            "and switch back to Direct."
        SourceKind.MQTT -> "No broker configured. Set the host in Settings."
        SourceKind.SIMULATED -> "The simulator is not available in this build."
    }

    private suspend fun mqttFor(settings: AppSettings): MqttSignalSource? {
        // Every entry into MQTT mode builds a fresh gateway from the current settings, so
        // the previous one has to be closed or its client keeps a socket, a reconnect timer
        // and a subscription set alive for the rest of the process.
        releaseMqtt()
        val created = sources.mqtt(scope, settings) ?: return null
        mqttSource = created
        tools.observeMqtt(created.connection, created.inspector, created.telemetry)
        return created
    }

    private suspend fun releaseMqtt() {
        val previous = mqttSource ?: return
        mqttSource = null
        tools.stopObservingMqtt()
        previous.shutdown()
    }

    private suspend fun pushTuning(config: NodeConfig): Boolean {
        val client = mqttSource ?: return false
        if (config.room.isBlank()) return false
        val absorption = config.absorption.toString()
        val maxDistance = config.maxDistance.toString()
        return client.publishSetting(config.room, EspresenseSetting.REF_RSSI, config.refRssi.toString()) &&
            client.publishSetting(config.room, EspresenseSetting.ABSORPTION, absorption) &&
            client.publishSetting(config.room, EspresenseSetting.MAX_DISTANCE, maxDistance)
    }

    private companion object {
        /** A different walk from the primary simulator, so the two do not agree exactly. */
        const val COMPARISON_SEED = 71120
    }
}
