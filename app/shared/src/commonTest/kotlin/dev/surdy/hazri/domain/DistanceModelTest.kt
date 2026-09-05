package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DistanceModelTest {

    @Test
    fun `the reference rssi is one metre by definition`() {
        assertEquals(1.0, DistanceModel.DEFAULT.distanceMetres(DistanceModel.DEFAULT_REF_RSSI), TOLERANCE)
    }

    @Test
    fun `the defaults are the firmware defaults`() {
        // include/defaults.h on ESPresense master: DEFAULT_RX_REF_RSSI (-65),
        // DEFAULT_ABSORPTION (2.7). -59 is DEFAULT_TX_REF_RSSI and is a different number.
        assertEquals(-65, DistanceModel.DEFAULT_REF_RSSI)
        assertEquals(2.7, DistanceModel.DEFAULT_ABSORPTION, TOLERANCE)
        assertEquals(-59, DistanceModel.TX_REF_RSSI)
    }

    @Test
    fun `distance and rssi are inverses of each other`() {
        val model = DistanceModel(refRssi = -65, absorption = 2.7)
        listOf(0.5, 1.0, 2.0, 4.5, 12.0).forEach { metres ->
            val rssi = model.rssiAt(metres)
            assertEquals(metres, model.distanceMetres(rssi), 1e-9)
        }
    }

    @Test
    fun `one decade of distance costs ten times absorption in dB`() {
        val model = DistanceModel(refRssi = -65, absorption = 3.0)
        assertEquals(-95.0, model.rssiAt(10.0), TOLERANCE)
        assertEquals(-125.0, model.rssiAt(100.0), TOLERANCE)
    }

    @Test
    fun `distance falls as signal rises`() {
        val model = DistanceModel.DEFAULT
        assertTrue(model.distanceMetres(-50) < model.distanceMetres(-70))
        assertTrue(model.distanceMetres(-70) < model.distanceMetres(-90))
    }

    @Test
    fun `higher absorption shortens the estimate for the same reading`() {
        val loose = DistanceModel(refRssi = -65, absorption = 2.0)
        val tight = DistanceModel(refRssi = -65, absorption = 4.0)
        assertTrue(tight.distanceMetres(-85) < loose.distanceMetres(-85))
    }

    @Test
    fun `a non-positive absorption is rejected`() {
        assertFailsWith<IllegalArgumentException> { DistanceModel(absorption = 0.0) }
        assertFailsWith<IllegalArgumentException> { DistanceModel(absorption = -1.0) }
    }

    @Test
    fun `a non-positive distance has no rssi`() {
        assertFailsWith<IllegalArgumentException> { DistanceModel.DEFAULT.rssiAt(0.0) }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
