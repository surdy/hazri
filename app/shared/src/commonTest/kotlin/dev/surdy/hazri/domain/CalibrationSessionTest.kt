package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationSessionTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")

    private fun sample(node: NodeId, rssi: Int) =
        SignalSample(node, rssi, 0L, Source.SIMULATED)

    @Test
    fun `samples for other nodes are ignored`() {
        val session = CalibrationSession(kitchen, minSamples = 1)
        assertFalse(session.add(sample(hall, -50)))
        assertEquals(0, session.sampleCount)
        assertNull(session.meanRssi())
    }

    @Test
    fun `no result until the minimum is reached`() {
        val session = CalibrationSession(kitchen, minSamples = 3)
        session.add(sample(kitchen, -59))
        session.add(sample(kitchen, -59))
        assertFalse(session.isReady)
        assertNull(session.result())

        session.add(sample(kitchen, -59))
        assertTrue(session.isReady)
    }

    @Test
    fun `at one metre the measured power is the mean`() {
        val session = CalibrationSession(kitchen, minSamples = 4)
        listOf(-58, -60, -59, -59).forEach { session.add(sample(kitchen, it)) }

        val result = session.result()!!
        assertEquals(-59, result.measuredPowerAtOneMetre)
        assertEquals(-59.0, result.meanRssi, 1e-9)
        assertEquals(4, result.sampleCount)
    }

    @Test
    fun `the ref_rssi offered for non-beacon devices is the same measurement`() {
        val session = CalibrationSession(kitchen, minSamples = 1)
        session.add(sample(kitchen, -62))
        val result = session.result()!!
        assertEquals(result.measuredPowerAtOneMetre, result.refRssiForNonBeaconDevices)
    }

    @Test
    fun `a capture taken further away is projected back to one metre`() {
        // At two metres with absorption 2.7 the path loss is 10 * 2.7 * log10(2) = 8.13 dB,
        // so a -70 dBm reading implies -61.87 at one metre, which rounds to -62.
        val session = CalibrationSession(kitchen, distanceMetres = 2.0, minSamples = 1)
        session.add(sample(kitchen, -70))
        assertEquals(-62, session.result(DistanceModel(absorption = 2.7))!!.measuredPowerAtOneMetre)
    }

    @Test
    fun `sigma reports how still the hand was`() {
        val session = CalibrationSession(kitchen, minSamples = 2)
        session.add(sample(kitchen, -57))
        session.add(sample(kitchen, -61))
        assertEquals(2.0, session.result()!!.sigma, 1e-6)
    }

    @Test
    fun `reset starts the capture again`() {
        val session = CalibrationSession(kitchen, minSamples = 1)
        session.add(sample(kitchen, -59))
        session.reset()
        assertEquals(0, session.sampleCount)
        assertNull(session.result())
    }
}
