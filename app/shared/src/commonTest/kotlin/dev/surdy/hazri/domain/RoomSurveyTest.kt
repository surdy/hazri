package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSurveyTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")

    private fun sample(node: NodeId, rssi: Int, at: Long) =
        SignalSample(node, rssi, at, Source.SIMULATED)

    @Test
    fun `an accumulator averages per node`() {
        val accumulator = SurveyAccumulator("Kitchen", 0L, Source.SIMULATED)
        listOf(-58, -60, -62).forEachIndexed { index, rssi ->
            accumulator.add(sample(kitchen, rssi, index * 100L))
        }
        accumulator.add(sample(hall, -80, 400L))

        val stats = accumulator.statsNow()
        assertEquals(2, stats.size)
        assertEquals(kitchen, stats.first().nodeId)
        assertEquals(-60.0, stats.first().mean, TOLERANCE)
        assertEquals(3, stats.first().count)
        assertEquals(4, accumulator.sampleCount)
    }

    @Test
    fun `sigma is the population spread of the samples`() {
        val accumulator = SurveyAccumulator("Kitchen", 0L, Source.SIMULATED)
        listOf(-58, -62).forEachIndexed { index, rssi ->
            accumulator.add(sample(kitchen, rssi, index * 100L))
        }
        assertEquals(2.0, accumulator.statsNow().first().sigma, 1e-6)
    }

    @Test
    fun `a constant node has zero sigma and never a negative one`() {
        val accumulator = SurveyAccumulator("Kitchen", 0L, Source.SIMULATED)
        repeat(50) { index -> accumulator.add(sample(kitchen, -61, index * 100L)) }
        val sigma = accumulator.statsNow().first().sigma
        assertTrue(sigma >= 0.0, "sigma must never be negative, was $sigma")
        assertEquals(0.0, sigma, 1e-6)
    }

    @Test
    fun `stats are ordered strongest first`() {
        val accumulator = SurveyAccumulator("Hallway", 0L, Source.SIMULATED)
        accumulator.add(sample(hall, -80, 0L))
        accumulator.add(sample(kitchen, -55, 100L))
        assertEquals(listOf(kitchen, hall), accumulator.statsNow().map { it.nodeId })
    }

    @Test
    fun `the running verdict is available before the walk ends`() {
        val accumulator = SurveyAccumulator("Hallway", 0L, Source.SIMULATED)
        accumulator.add(sample(hall, -66, 0L))
        accumulator.add(sample(kitchen, -68, 100L))
        assertEquals(Verdict.TIGHT, accumulator.verdictNow().verdict)
    }

    @Test
    fun `finishing seals the walk with its timings`() {
        val accumulator = SurveyAccumulator("Kitchen", 1_000L, Source.MQTT)
        accumulator.add(sample(kitchen, -60, 1_500L))
        accumulator.add(sample(kitchen, -60, 4_000L))

        val survey = accumulator.finish()
        assertEquals("Kitchen", survey.room)
        assertEquals(Source.MQTT, survey.source)
        assertEquals(3_000L, survey.durationMillis)
        assertEquals(2, survey.sampleCount)
        assertEquals(mapOf(kitchen to -60.0), survey.means())
    }

    @Test
    fun `an explicit end time overrides the last sample`() {
        val accumulator = SurveyAccumulator("Kitchen", 0L, Source.DIRECT)
        accumulator.add(sample(kitchen, -60, 500L))
        assertEquals(9_000L, accumulator.finish(endedAt = 9_000L).endedAt)
    }

    @Test
    fun `a survey knows its own verdict`() {
        val survey = RoomSurvey(
            room = "Hallway",
            startedAt = 0L,
            endedAt = 1_000L,
            source = Source.SIMULATED,
            stats = listOf(
                NodeSurveyStat(hall, -66.0, 2.0, 100),
                NodeSurveyStat(kitchen, -80.0, 3.0, 90),
            ),
        )
        assertEquals(Verdict.CLEAR, survey.verdict().verdict)
        assertEquals(14.0, survey.verdict().margin!!, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
