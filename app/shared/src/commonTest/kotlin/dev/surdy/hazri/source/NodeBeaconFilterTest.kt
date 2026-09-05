package dev.surdy.hazri.source

import dev.surdy.hazri.protocol.Espresense
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bytes a survey's scan filter is made of.
 *
 * Worth a test of its own because nothing downstream would notice them being wrong: a
 * filter that matches nothing produces a scan that hears nothing, which on a walk looks
 * exactly like a house out of range. The order is the whole content of the assertion —
 * a UUID written little-endian, or the `02 15` header omitted, would compile and match
 * no node in the world.
 */
class NodeBeaconFilterTest {

    @Test
    fun `the filter is the beacon header followed by the fixed half of the node uuid`() {
        assertContentEquals(
            byteArrayOf(
                0x02, 0x15,
                0xE5.toByte(), 0xCA.toByte(), 0x1A, 0xDE.toByte(),
                0xF0.toByte(), 0x07, 0xBA.toByte(), 0x11,
            ),
            NodeBeaconFilter.DATA,
        )
    }

    @Test
    fun `the mask covers every byte of the data`() {
        assertEquals(NodeBeaconFilter.DATA.size, NodeBeaconFilter.DATA_MASK.size)
        assertTrue(NodeBeaconFilter.DATA_MASK.all { it == 0xFF.toByte() })
    }

    @Test
    fun `it is Apple's company id, as the parser reads`() {
        assertEquals(IBeaconParser.APPLE_COMPANY_ID, NodeBeaconFilter.COMPANY_ID)
        assertEquals(0x004C, NodeBeaconFilter.COMPANY_ID)
    }

    @Test
    fun `the filter matches a frame the parser calls a node beacon`() {
        // The filter and the parser have to agree, or a survey would scan for one thing and
        // identify another. Building a real frame and running both over it is the check.
        val frame = nodeBeaconFrame(major = 100, minor = 1)
        assertTrue(
            NodeBeaconFilter.DATA.indices.all { frame[it] == NodeBeaconFilter.DATA[it] },
            "the filter prefix should be the head of a real node frame",
        )
        val beacon = IBeaconParser.parse(frame)
        assertEquals(Espresense.NODE_BEACON_UUID, beacon?.uuid)
        assertEquals(100, beacon?.major)
        assertEquals(1, beacon?.minor)
    }

    private fun nodeBeaconFrame(major: Int, minor: Int): ByteArray {
        val hex = Espresense.NODE_BEACON_UUID.filter { it != '-' }
        val uuid = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(
            (major shr 8).toByte(), major.toByte(),
            (minor shr 8).toByte(), minor.toByte(),
            (-59).toByte(),
        )
    }
}
