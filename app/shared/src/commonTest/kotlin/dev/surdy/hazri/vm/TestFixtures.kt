package dev.surdy.hazri.vm

import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.InMemoryFileStore
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.source.MillisClock
import dev.surdy.hazri.source.MqttSignalSource
import dev.surdy.hazri.source.SignalSource
import dev.surdy.hazri.source.SimulatedSignalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A clock the test moves by hand. Every timestamp in these suites comes from one. */
class TestClock(var millis: Long = 0L) : MillisClock {
    override fun now(): Long = millis

    /** Moves the clock forward and returns the new reading. */
    fun advance(by: Long): Long {
        millis += by
        return millis
    }
}

/**
 * A source the test feeds by hand.
 *
 * [started] and [stopped] are counted rather than asserted on a flag, because the bugs
 * worth catching here are double-starts and sources left running after a switch.
 */
class FakeSignalSource(
    override val source: Source = Source.SIMULATED,
    private val failToStart: String? = null,
) : SignalSource {
    private val emitted = MutableSharedFlow<SignalSample>(extraBufferCapacity = 64)
    override val samples: Flow<SignalSample> = emitted.asSharedFlow()

    var started: Int = 0
        private set
    var stopped: Int = 0
        private set

    override suspend fun start() {
        if (failToStart != null) throw IllegalStateException(failToStart)
        started += 1
    }

    override fun stop() {
        stopped += 1
    }

    /** Pushes one reading through. */
    suspend fun emit(nodeId: String, rssi: Int, at: Long) {
        emitted.emit(SignalSample(NodeId(nodeId), rssi, at, source))
    }
}

/** A repository whose writes complete before the call returns. */
fun testRepository(): HazriRepository =
    HazriRepository(InMemoryFileStore(), CoroutineScope(Dispatchers.Unconfined))

/** A source factory the test controls, with a per-kind availability switch. */
class FakeSourceFactory(
    private val direct: SignalSource? = FakeSignalSource(Source.DIRECT),
    private val mqtt: MqttSignalSource? = null,
    private val simulated: SimulatedSignalSource? = null,
) : SourceFactory {
    val requested = mutableListOf<SourceKind>()

    override fun directScan(scope: CoroutineScope): SignalSource? {
        requested += SourceKind.DIRECT
        return direct
    }

    override fun mqtt(scope: CoroutineScope, settings: AppSettings): MqttSignalSource? {
        requested += SourceKind.MQTT
        return mqtt
    }

    override fun simulated(scope: CoroutineScope): SimulatedSignalSource {
        requested += SourceKind.SIMULATED
        return simulated ?: SimulatedSignalSource(scope = scope)
    }
}

/**
 * A [SurveyKeepAlive] that records what it was told.
 *
 * Lists rather than counters: the bugs worth catching are a stop that never came, a start
 * announced with the wrong room, and an update per tick instead of per second, and all
 * three are questions about the sequence.
 */
class FakeSurveyKeepAlive : SurveyKeepAlive {
    val started = mutableListOf<String>()
    val updates = mutableListOf<SurveyProgress>()
    var stops: Int = 0
        private set

    override fun start(room: String) {
        started += room
    }

    override fun update(progress: SurveyProgress) {
        updates += progress
    }

    override fun stop() {
        stops += 1
    }
}
