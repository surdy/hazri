package dev.surdy.hazri.vm

import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.InMemoryFileStore
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {

    private fun TestScope.container(
        factory: FakeSourceFactory,
        simulationAvailable: Boolean = true,
        repository: HazriRepository = HazriRepository(
            InMemoryFileStore(),
            CoroutineScope(Dispatchers.Unconfined),
        ),
    ) = AppContainer(
        repository = repository,
        sources = factory,
        scope = backgroundScope,
        clock = TestClock(1_000L),
        simulationAvailable = simulationAvailable,
    )

    @Test
    fun `a debug build offers all three sources`() = runTest(UnconfinedTestDispatcher()) {
        val container = container(FakeSourceFactory())
        assertEquals(
            listOf(SourceKind.DIRECT, SourceKind.MQTT, SourceKind.SIMULATED),
            container.availableSources(),
        )
    }

    @Test
    fun `a release build does not offer the simulator`() = runTest(UnconfinedTestDispatcher()) {
        val container = container(FakeSourceFactory(), simulationAvailable = false)
        assertEquals(listOf(SourceKind.DIRECT, SourceKind.MQTT), container.availableSources())
    }

    @Test
    fun `a release build never runs the simulator, whatever the settings say`() =
        runTest(UnconfinedTestDispatcher()) {
            // SIMULATED is the shipped default so a debug build opens onto a populated app.
            // A release install inheriting it would present invented data as real.
            val factory = FakeSourceFactory()
            val container = container(factory, simulationAvailable = false)

            assertEquals(SourceKind.DIRECT, container.resolveSource(SourceKind.SIMULATED))
            container.switchSource(SourceKind.SIMULATED)

            assertEquals(listOf(SourceKind.DIRECT), factory.requested)
            assertEquals(SourceKind.DIRECT, container.repository.settings.value.sourceKind)
            assertEquals(SourceKind.DIRECT, container.engine.live.value.sourceKind)
        }

    @Test
    fun `a debug build runs the simulator as asked`() = runTest(UnconfinedTestDispatcher()) {
        val factory = FakeSourceFactory()
        val container = container(factory)
        container.switchSource(SourceKind.SIMULATED)

        assertEquals(SourceKind.SIMULATED, container.engine.live.value.sourceKind)
        assertEquals(SourceKind.SIMULATED, container.repository.settings.value.sourceKind)
    }

    @Test
    fun `a source the platform cannot provide reports why and changes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = FakeSourceFactory(direct = null)
            val container = container(factory)
            container.switchSource(SourceKind.SIMULATED)
            container.switchSource(SourceKind.DIRECT)

            // Still on the simulator, and the Live screen says what happened rather than
            // showing an empty list that looks like a house with no nodes in it.
            assertEquals(SourceKind.SIMULATED, container.repository.settings.value.sourceKind)
            assertNotNull(container.engine.live.value.error)
            assertTrue(container.engine.live.value.error!!.contains("Bluetooth"))
        }

    @Test
    fun `MQTT with no broker reports why`() = runTest(UnconfinedTestDispatcher()) {
        val container = container(FakeSourceFactory(mqtt = null))
        container.switchSource(SourceKind.MQTT)

        assertTrue(container.engine.live.value.error!!.contains("broker"))
        assertFalse(container.engine.live.value.isRunning)
    }

    @Test
    fun `seeding only happens where the simulator does`() = runTest(UnconfinedTestDispatcher()) {
        val seeded = container(FakeSourceFactory())
        seeded.start()
        assertTrue(seeded.repository.surveys.value.isNotEmpty())

        val bare = container(FakeSourceFactory(), simulationAvailable = false)
        bare.start()
        assertTrue(bare.repository.surveys.value.isEmpty())
    }

    @Test
    fun `switching away from a source stops it`() = runTest(UnconfinedTestDispatcher()) {
        val direct = FakeSignalSource(Source.DIRECT)
        val factory = FakeSourceFactory(direct = direct)
        val container = container(factory)

        container.switchSource(SourceKind.DIRECT)
        assertEquals(1, direct.started)

        container.switchSource(SourceKind.SIMULATED)
        assertTrue(direct.stopped >= 1)
    }

    @Test
    fun `a comparison source is a different walk from the primary`() =
        runTest(UnconfinedTestDispatcher()) {
            val container = container(FakeSourceFactory())
            val comparison = container.comparisonSource()
            assertEquals(Source.SIMULATED, comparison.source)
        }
}
