package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssiSmootherTest {

    private fun sample(rssi: Int, at: Long) =
        SignalSample(NodeId("kitchen"), rssi, at, Source.SIMULATED)

    @Test
    fun `no smoothed value before the first sample`() {
        assertNull(RssiSmoother().smoothed)
    }

    @Test
    fun `the first sample is taken as the starting point`() {
        val smoother = RssiSmoother()
        assertEquals(-60.0, smoother.add(sample(-60, 0L)), ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `the median rejects a single deep fade`() {
        val smoother = RssiSmoother(alpha = 1.0, medianWindow = 5)
        listOf(-60, -60, -60, -60).forEachIndexed { index, rssi ->
            smoother.add(sample(rssi, index * 100L))
        }
        // A one-off -95 becomes the fifth value of the window; the median is still -60.
        val afterFade = smoother.add(sample(-95, 400L))
        assertEquals(-60.0, afterFade, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `the ema converges towards a step change without jumping to it`() {
        val smoother = RssiSmoother(alpha = 0.2, medianWindow = 1)
        smoother.add(sample(-60, 0L))
        val first = smoother.add(sample(-80, 100L))

        // One step of alpha 0.2 from -60 towards -80 is -64, not -80.
        assertEquals(-64.0, first, ABSOLUTE_TOLERANCE)

        repeat(40) { index -> smoother.add(sample(-80, 200L + index * 100L)) }
        assertTrue(smoother.smoothed!! < -79.0, "expected convergence, was ${smoother.smoothed}")
    }

    @Test
    fun `alpha of one follows the median exactly`() {
        val smoother = RssiSmoother(alpha = 1.0, medianWindow = 1)
        smoother.add(sample(-60, 0L))
        assertEquals(-80.0, smoother.add(sample(-80, 100L)), ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `stats report mean sigma min and max over the window`() {
        val smoother = RssiSmoother(statsWindowMillis = 10_000L)
        listOf(-60, -62, -58, -60).forEachIndexed { index, rssi ->
            smoother.add(sample(rssi, index * 1000L))
        }
        val stats = smoother.stats(3000L)!!

        assertEquals(4, stats.count)
        assertEquals(-60.0, stats.mean, ABSOLUTE_TOLERANCE)
        assertEquals(-62, stats.min)
        assertEquals(-58, stats.max)
        // Population sigma of (-60, -62, -58, -60) is sqrt(2).
        assertEquals(1.4142, stats.sigma, 0.001)
    }

    @Test
    fun `packet rate is samples per second across the window`() {
        val smoother = RssiSmoother()
        repeat(5) { index -> smoother.add(sample(-60, index * 1000L)) }
        // Five samples spanning four seconds.
        assertEquals(1.25, smoother.stats(4000L)!!.packetRate, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `a single sample has no packet rate`() {
        val smoother = RssiSmoother()
        smoother.add(sample(-60, 0L))
        assertEquals(0.0, smoother.stats(0L)!!.packetRate, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `samples older than the window are dropped`() {
        val smoother = RssiSmoother(statsWindowMillis = 1000L)
        smoother.add(sample(-60, 0L))
        smoother.add(sample(-90, 5000L))

        val stats = smoother.stats(5000L)!!
        assertEquals(1, stats.count)
        assertEquals(-90.0, stats.mean, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun `a node that stops reporting has no stats once the window empties`() {
        val smoother = RssiSmoother(statsWindowMillis = 1000L)
        smoother.add(sample(-60, 0L))
        assertNull(smoother.stats(9000L))
    }

    @Test
    fun `reset forgets everything`() {
        val smoother = RssiSmoother()
        smoother.add(sample(-60, 0L))
        smoother.reset()
        assertNull(smoother.smoothed)
        assertNull(smoother.stats(0L))
    }

    @Test
    fun `an even median window is rejected`() {
        assertFailsWith<IllegalArgumentException> { RssiSmoother(medianWindow = 4) }
    }

    @Test
    fun `an alpha outside zero to one is rejected`() {
        assertFailsWith<IllegalArgumentException> { RssiSmoother(alpha = 0.0) }
        assertFailsWith<IllegalArgumentException> { RssiSmoother(alpha = 1.1) }
    }

    private companion object {
        const val ABSOLUTE_TOLERANCE = 1e-9
    }
}
