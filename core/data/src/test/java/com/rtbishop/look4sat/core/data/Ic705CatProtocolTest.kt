package com.rtbishop.look4sat.core.data

import com.rtbishop.look4sat.core.data.framework.Ic705CatProtocol
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ic705CatProtocolTest {

    @Test
    fun encodeFrequencyBcd_145800000() {
        val bcd = Ic705CatProtocol.encodeFrequencyBcd(145800000L)
        // 145800000 = 0x08AF8380 in BCD little-endian pairs
        // Expected: [0x00, 0x00, 0x80, 0x00, 0x45] for 00 00 80 00 45 01 (but we encode to 1 Hz)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x08, 0x58, 0x41), bcd)
    }

    @Test
    fun encodeFrequencyBcd_435100000() {
        val bcd = Ic705CatProtocol.encodeFrequencyBcd(435100000L)
        // 435100000 in BCD little-endian
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x01, 0x15, 0x34), bcd)
    }

    @Test
    fun encodeFrequencyBcd_7074000() {
        val bcd = Ic705CatProtocol.encodeFrequencyBcd(7074000L)
        // 7074000 = 0007074000 in BCD
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x40, 0x07, 0x00), bcd)
    }

    @Test
    fun decodeFrequencyBcd_roundTrips() {
        val testFreqs = listOf(145800000L, 435100000L, 7074000L, 14200000L, 28500000L)
        for (freq in testFreqs) {
            val bcd = Ic705CatProtocol.encodeFrequencyBcd(freq)
            assertEquals(freq, Ic705CatProtocol.decodeFrequencyBcd(bcd))
        }
    }

    @Test
    fun buildSetFreqCommand_correctFormat() {
        val cmd = Ic705CatProtocol.buildSetFreqCommand(145800000L)
        // FE FE A4 E0 00 [5-byte BCD] FD
        assertEquals(11, cmd.size)
        assertEquals(0xFE.toByte(), cmd[0])
        assertEquals(0xFE.toByte(), cmd[1])
        assertEquals(0xA4.toByte(), cmd[2])
        assertEquals(0xE0.toByte(), cmd[3])
        assertEquals(0x00.toByte(), cmd[4])
        assertEquals(0xFD.toByte(), cmd[10])
    }

    @Test
    fun buildSetFreqToCurrentVfoCommand_correctFormat() {
        val cmd = Ic705CatProtocol.buildSetFreqToCurrentVfoCommand(145800000L)
        // FE FE A4 E0 25 00 [5-byte BCD] FD
        assertEquals(12, cmd.size)
        assertEquals(0xFE.toByte(), cmd[0])
        assertEquals(0xFE.toByte(), cmd[1])
        assertEquals(0xA4.toByte(), cmd[2])
        assertEquals(0xE0.toByte(), cmd[3])
        assertEquals(0x25.toByte(), cmd[4])
        assertEquals(0x00.toByte(), cmd[5])
        assertEquals(0xFD.toByte(), cmd[11])
    }

    @Test
    fun buildSetModeCommand_usb() {
        val cmd = Ic705CatProtocol.buildSetModeCommand("USB")
        assertNotNull(cmd)
        // FE FE A4 E0 06 [mode] [filter] FD
        assertEquals(8, cmd.size)
        assertEquals(0x06.toByte(), cmd[4])
        assertEquals(0x01.toByte(), cmd[5]) // USB mode
    }

    @Test
    fun buildSetModeCommand_fm() {
        val cmd = Ic705CatProtocol.buildSetModeCommand("FM")
        assertNotNull(cmd)
        assertEquals(0x06.toByte(), cmd[4])
        assertEquals(0x05.toByte(), cmd[5]) // FM mode
    }

    @Test
    fun buildSetModeCommand_unknownReturnsNull() {
        assertNull(Ic705CatProtocol.buildSetModeCommand("INVALID"))
    }

    @Test
    fun buildSetVfoCommand_vfoA() {
        val cmd = Ic705CatProtocol.buildSetVfoCommand("A")
        assertNotNull(cmd)
        // FE FE A4 E0 07 00 FD
        assertEquals(7, cmd.size)
        assertEquals(0x07.toByte(), cmd[4])
        assertEquals(0x00.toByte(), cmd[5])
    }

    @Test
    fun buildSetVfoCommand_vfoB() {
        val cmd = Ic705CatProtocol.buildSetVfoCommand("B")
        assertNotNull(cmd)
        assertEquals(0x07.toByte(), cmd[4])
        assertEquals(0x01.toByte(), cmd[5])
    }

    @Test
    fun buildSplitCommand_enable() {
        val cmd = Ic705CatProtocol.buildSplitCommand(true)
        // FE FE A4 E0 0F 01 FD
        assertEquals(7, cmd.size)
        assertEquals(0x0F.toByte(), cmd[4])
        assertEquals(0x01.toByte(), cmd[5])
    }

    @Test
    fun buildSplitCommand_disable() {
        val cmd = Ic705CatProtocol.buildSplitCommand(false)
        assertEquals(0x0F.toByte(), cmd[4])
        assertEquals(0x00.toByte(), cmd[5])
    }

    @Test
    fun buildReadPttCommand() {
        val cmd = Ic705CatProtocol.buildReadPttCommand()
        // FE FE A4 E0 1C 00 FD
        assertEquals(7, cmd.size)
        assertEquals(0x1C.toByte(), cmd[4])
        assertEquals(0x00.toByte(), cmd[5])
    }

    @Test
    fun encodeCtcssTone_67_0() {
        val encoded = Ic705CatProtocol.encodeCtcssTone(67.0)
        // 67.0 Hz = 670 in 0.1 Hz = 0x029E
        assertContentEquals(byteArrayOf(0x00, 0x02, 0x9E.toByte()), encoded)
    }

    @Test
    fun encodeCtcssTone_141_3() {
        val encoded = Ic705CatProtocol.encodeCtcssTone(141.3)
        // 141.3 Hz = 1413 in 0.1 Hz = 0x0585
        assertContentEquals(byteArrayOf(0x00, 0x05, 0x85.toByte()), encoded)
    }

    @Test
    fun buildSetCtcssToneCommand_correctFormat() {
        val cmd = Ic705CatProtocol.buildSetCtcssToneCommand(67.0)
        // FE FE A4 E0 1A 05 00 94 [3-byte tone] FD
        assertEquals(12, cmd.size)
        assertEquals(0x1A.toByte(), cmd[4])
        assertEquals(0x05.toByte(), cmd[5])
        assertEquals(0x00.toByte(), cmd[6])
        assertEquals(0x94.toByte(), cmd[7])
    }

    @Test
    fun isValidMessage_validMessage() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertTrue(Ic705CatProtocol.isValidMessage(msg))
    }

    @Test
    fun isValidMessage_invalidPreamble() {
        val msg = byteArrayOf(0xFE.toByte(), 0x00.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertFalse(Ic705CatProtocol.isValidMessage(msg))
    }

    @Test
    fun isValidMessage_noEom() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0x00.toByte())
        assertFalse(Ic705CatProtocol.isValidMessage(msg))
    }

    @Test
    fun isResponseFromRadio_validResponse() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertTrue(Ic705CatProtocol.isResponseFromRadio(msg))
    }

    @Test
    fun isResponseFromRadio_wrongDirection() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertFalse(Ic705CatProtocol.isResponseFromRadio(msg))
    }

    @Test
    fun isOkResponse() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertTrue(Ic705CatProtocol.isOkResponse(msg))
    }

    @Test
    fun isNgResponse() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFA.toByte(), 0xFD.toByte())
        assertTrue(Ic705CatProtocol.isNgResponse(msg))
    }

    @Test
    fun parseReadFreqResponse_validResponse() {
        // FE FE E0 A4 03 [5-byte BCD for 145800000] FD
        val bcd = Ic705CatProtocol.encodeFrequencyBcd(145800000L)
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x03.toByte()) +
                bcd + byteArrayOf(0xFD.toByte())
        val freq = Ic705CatProtocol.parseReadFreqResponse(msg)
        assertNotNull(freq)
        assertEquals(145800000L, freq)
    }

    @Test
    fun parseReadFreqResponse_tooShort() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x03.toByte(), 0xFD.toByte())
        assertNull(Ic705CatProtocol.parseReadFreqResponse(msg))
    }

    @Test
    fun parseReadModeResponse_usbMode() {
        // FE FE E0 A4 04 01 01 FD
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x01.toByte(), 0x01.toByte(), 0xFD.toByte())
        val mode = Ic705CatProtocol.parseReadModeResponse(msg)
        assertEquals("USB", mode)
    }

    @Test
    fun parseReadModeResponse_fmMode() {
        // FE FE E0 A4 04 05 01 FD
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x05.toByte(), 0x01.toByte(), 0xFD.toByte())
        val mode = Ic705CatProtocol.parseReadModeResponse(msg)
        assertEquals("FM", mode)
    }

    @Test
    fun parsePttStatusResponse_pttOff() {
        // FE FE E0 A4 1C 00 00 FD
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x1C.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFD.toByte())
        val ptt = Ic705CatProtocol.parsePttStatusResponse(msg)
        assertNotNull(ptt)
        assertFalse(ptt)
    }

    @Test
    fun parsePttStatusResponse_pttOn() {
        // FE FE E0 A4 1C 00 01 FD
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x1C.toByte(), 0x00.toByte(), 0x01.toByte(), 0xFD.toByte())
        val ptt = Ic705CatProtocol.parsePttStatusResponse(msg)
        assertNotNull(ptt)
        assertTrue(ptt)
    }

    @Test
    fun getCommandByte_validMessage() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x03.toByte(), 0xFD.toByte())
        assertEquals(0x03.toByte(), Ic705CatProtocol.getCommandByte(msg))
    }

    @Test
    fun getCommandByte_okResponse() {
        val msg = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0xFB.toByte(), 0xFD.toByte())
        assertEquals(0xFB.toByte(), Ic705CatProtocol.getCommandByte(msg))
    }
}
