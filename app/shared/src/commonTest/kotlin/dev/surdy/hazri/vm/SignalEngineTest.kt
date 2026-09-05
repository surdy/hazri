package dev.surdy.hazri.vm

import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.DistanceModel
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.RssiSmoother
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignalEngineTest {

    private val clock = TestClock(100_000L)

    private fun TestScope.engineWith(
        repository: HazriRepository = testRepository(),
    ) = SignalEngine(repository, backgroundScope, clock)

    @Test
    fun `a node appears once it has been heard`() = runTest(UnconfinedTestDispatcher()) {
        val repository = testRepository()
        val engine = engineWith(repository)
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        source.emit("kitchen", -60, clock.now())
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        val node = engine.live.value.nodes.single()
        assertEquals(NodeId("kitchen"), node.node.id)
        assertEquals(-60.0, node.smoothedRssi, 1e-9)
        assertEquals(1, repository.nodes.value.size)
    }

    @Test
    fun `history older than the window is evicted`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engineWith()
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        source.emit("kitchen", -60, clock.now())
        source.emit("kitchen", -60, clock.advance(SignalEngine.HISTORY_MILLIS + 1))
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        // The first sample is older than the 60 s window by the time the second arrives.
        assertEquals(1, engine.live.value.nodes.single().history.size)
    }

    @Test
    fun `the lead is the gap between the top two`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engineWith()
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        source.emit("kitchen", -55, clock.now())
        source.emit("hall", -70, clock.now())
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        val lead = engine.live.value.lead!!
        assertEquals(NodeId("kitchen"), lead.best.id)
        assertEquals(NodeId("hall"), lead.runnerUp!!.id)
        assertEquals(15.0, lead.marginDb!!, 1e-9)
    }

    @Test
    fun `a single node leads with no margin`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engineWith()
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        source.emit("kitchen", -55, clock.now())
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        assertNull(engine.live.value.lead!!.marginDb)
    }

    @Test
    fun `a source that will not start reports why and does not claim to be running`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engineWith()
            engine.useSource(SourceKind.DIRECT, FakeSignalSource(failToStart = "no permission"))

            // The bug this covers: the failure was recorded and then immediately overwritten
            // by an unconditional copy(isRunning = true, error = null).
            assertFalse(engine.live.value.isRunning)
            assertEquals("no permission", engine.live.value.error)
        }

    @Test
    fun `an explicit error is visible on the live state`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engineWith()
        engine.reportError("Bluetooth was not permitted")

        assertFalse(engine.live.value.isRunning)
        assertEquals("Bluetooth was not permitted", engine.live.value.error)
    }

    @Test
    fun `switching source stops the old one and forgets its history`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engineWith()
            val first = FakeSignalSource(Source.DIRECT)
            val second = FakeSignalSource(Source.MQTT)

            engine.useSource(SourceKind.DIRECT, first)
            first.emit("kitchen", -55, clock.now())
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)
            assertEquals(1, engine.live.value.nodes.size)

            engine.useSource(SourceKind.MQTT, second)
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

            assertTrue(first.stopped >= 1)
            assertEquals(SourceKind.MQTT, engine.live.value.sourceKind)
            // Direct RSSI and MQTT RSSI are different measurements; averaging across the
            // switch would produce a number that means nothing.
            assertTrue(engine.live.value.nodes.isEmpty())
        }

    @Test
    fun `a smoothing change applies without restarting the source`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = testRepository()
            val engine = engineWith(repository)
            val source = FakeSignalSource()
            engine.useSource(SourceKind.SIMULATED, source)

            source.emit("kitchen", -60, clock.now())
            source.emit("kitchen", -80, clock.advance(200))
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)
            val gentle = engine.live.value.nodes.single().smoothedRssi

            repository.updateSettings { it.copy(smoothingAlpha = 1.0, medianWindow = 1) }
            source.emit("kitchen", -80, clock.advance(200))
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)
            val eager = engine.live.value.nodes.single().smoothedRssi

            assertTrue(gentle > eager, "alpha 1.0 should track the step, was $gentle then $eager")
            assertEquals(-80.0, eager, 1e-9)
        }

    @Test
    fun `applySettings keeps the history it re-smooths`() = runTest(UnconfinedTestDispatcher()) {
        val repository = testRepository()
        val engine = engineWith(repository)
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        repeat(5) { index -> source.emit("kitchen", -60, clock.advance(100)) }
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)
        engine.applySettings(repository.settings.value)
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        assertEquals(5, engine.live.value.nodes.single().history.size)
    }

    @Test
    fun `the published state carries the clock the ages are measured against`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engineWith()
            val source = FakeSignalSource()
            engine.useSource(SourceKind.SIMULATED, source)

            source.emit("kitchen", -60, clock.now())
            clock.advance(3_000)
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

            val state = engine.live.value
            // "seen N s ago" needs two different clock readings. Taking both from the same
            // sample is why it always said zero.
            assertEquals(3_000L, state.updatedAt - state.nodes.single().lastSeen)
        }

    @Test
    fun `a hidden node is not published`() = runTest(UnconfinedTestDispatcher()) {
        val repository = testRepository()
        val engine = engineWith(repository)
        val source = FakeSignalSource()
        engine.useSource(SourceKind.SIMULATED, source)

        source.emit("kitchen", -55, clock.now())
        source.emit("hall", -70, clock.now())
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)
        repository.updateNode(NodeId("hall")) { it.copy(hidden = true) }
        testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

        assertEquals(listOf(NodeId("kitchen")), engine.live.value.nodes.map { it.node.id })
    }

    @Test
    fun `direct mode ranges against the node's transmit reference, not its receive one`() {
        val config = NodeConfig("kitchen", refRssi = -65, absorption = 2.7)

        val overMqtt = SignalEngine.referenceModelFor(SourceKind.MQTT, config)
        assertEquals(-65, overMqtt.refRssi)

        // Scanning, the phone hears the node's own iBeacon, whose calibrated power is
        // tx_ref_rssi (-59). Reading it against the receive reference (-65) is six dB of
        // constant error: 3.6 m where the model means 6.0 m.
        val scanning = SignalEngine.referenceModelFor(SourceKind.DIRECT, config)
        assertEquals(DistanceModel.TX_REF_RSSI, scanning.refRssi)
        assertEquals(2.7, scanning.absorption, 1e-9)
        assertEquals(6.0, scanning.distanceMetres(-80), 0.05)
        assertEquals(3.6, overMqtt.distanceMetres(-80), 0.05)
        assertTrue(scanning.distanceMetres(-80) > overMqtt.distanceMetres(-80))
    }

    @Test
    fun `window stats summarise a raw history`() {
        val history = listOf(-58, -62, -60).mapIndexed { index, rssi ->
            SignalSample(NodeId("kitchen"), rssi, index * 1_000L, Source.SIMULATED)
        }
        val stats = SignalEngine.statsOf(history)!!

        assertEquals(3, stats.count)
        assertEquals(-60.0, stats.mean, 1e-9)
        assertEquals(-62, stats.min)
        assertEquals(-58, stats.max)
        assertEquals(1.5, stats.packetRate, 1e-9)
        assertNull(SignalEngine.statsOf(emptyList()))
    }

    @Test
    fun `MQTT gets a longer stats window than a raw scan`() {
        // ESPresense publishes about every 5 s, so a 10 s window holds one or two readings
        // and the packet rate rounds to zero on every card.
        val settings = AppSettings.DEFAULT
        assertEquals(
            AppSettings.MQTT_STATS_WINDOW_MILLIS,
            settings.newSmoother(SourceKind.MQTT).statsWindowMillis,
        )
        assertEquals(1, settings.newSmoother(SourceKind.MQTT).medianWindow)
        assertEquals(
            RssiSmoother.DEFAULT_STATS_WINDOW_MILLIS,
            settings.newSmoother(SourceKind.DIRECT).statsWindowMillis,
        )
    }

    @Test
    fun `the rate is spelled per minute for MQTT and per second for a scan`() {
        val mqtt = AppSettings.rateUnit(SourceKind.MQTT)
        assertEquals("/min", mqtt.label)
        assertEquals(12.0, mqtt.convert(0.2), 1e-9)

        val direct = AppSettings.rateUnit(SourceKind.DIRECT)
        assertEquals("pkt/s", direct.label)
        assertEquals(9.0, direct.convert(9.0), 1e-9)
    }
}
