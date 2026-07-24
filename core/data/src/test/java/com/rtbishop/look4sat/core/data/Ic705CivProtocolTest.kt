package com.rtbishop.look4sat.core.data

import com.rtbishop.look4sat.core.data.framework.Ic705CivProtocol
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ic705CivProtocolTest {

    @Test
    fun encodeFrequencyBcd_145800000() {
        val bcd = Ic705CivProtocol.encodeFrequencyBcd(145800000L)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x45, 0x01), bcd)
    }

    @Test
    fun encodeFrequencyBcd_435100000() {
        val bcd = Ic705CivProtocol.encodeFrequencyBcd(435100000L)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x10, 0x35, 0x04), bcd)
    }

    @Test
    fun encodeFrequencyBcd_7074000() {
        val bcd = Ic705CivProtocol.encodeFrequencyBcd(7074000L)
        assertContentEquals(byteArrayOf(0x00, 0x40, 0x07, 0x07, 0x00), bcd)
    }

    @Test
    fun encodeFrequencyBcd_1Hz() {
        val bcd = Ic705CivProtocol.encodeFrequencyBcd(1L)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00), bcd)
    }

    @Test
    fun encodeFrequencyBcd_maxFreq() {
        val bcd = Ic705CivProtocol.encodeFrequencyBcd(9999999999L)
        assertContentEquals(byteArrayOf(0x99.toByte(), 0x99.toByte(), 0x99.toByte(), 0x99.toByte(), 0x99.toByte()), bcd)
    }

    @Test
    fun decodeFrequencyBcd_roundTrips() {
        val testFreqs = listOf(145800000L, 435100000L, 7074000L, 14200000L, 28500000L, 1L, 1234567890L)
        for (freq in testFreqs) {
            val bcd = Ic705CivProtocol.encodeFrequencyBcd(freq)
            assertEquals(freq, Ic705CivProtocol.decodeFrequencyBcd(bcd), "Failed for frequency $freq")
        }
    }

    @Test
    fun buildSetFreqCommand_correctFormat() {
        val cmd = Ic705CivProtocol.buildSetFreqCommand(145800000L)
        assertEquals(11, cmd.size)
        assertEquals(Ic705CivProtocol.PREAMBLE, cmd[0])
        assertEquals(Ic705CivProtocol.PREAMBLE, cmd[1])
        assertEquals(Ic705CivProtocol.ADDR_IC705, cmd[2])
        assertEquals(Ic705CivProtocol.ADDR_CONTROLLER, cmd[3])
        assertEquals(Ic705CivProtocol.CMD_SET_FREQ, cmd[4])
        assertEquals(Ic705CivProtocol.POSTAMBLE, cmd[10])
        // BCD: [0x00, 0x00, 0x80, 0x45, 0x01]
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x45, 0x01), cmd.copyOfRange(5, 10))
    }

    @Test
    fun buildSetModeCommand_usb() {
        val cmd = Ic705CivProtocol.buildSetModeCommand("USB")
        assertNotNull(cmd)
        assertEquals(8, cmd.size)
        assertEquals(Ic705CivProtocol.CMD_SET_MODE, cmd[4])
        assertEquals(0x01.toByte(), cmd[5]) // USB mode
        assertEquals(Ic705CivProtocol.FILTER_FIL1, cmd[6]) // Default filter
    }

    @Test
    fun buildSetModeCommand_fm() {
        val cmd = Ic705CivProtocol.buildSetModeCommand("FM")
        assertNotNull(cmd)
        assertEquals(0x05.toByte(), cmd[5]) // FM mode
    }

    @Test
    fun buildSetModeCommand_lsb() {
        val cmd = Ic705CivProtocol.buildSetModeCommand("LSB")
        assertNotNull(cmd)
        assertEquals(0x00.toByte(), cmd[5]) // LSB mode
    }

    @Test
    fun buildSetModeCommand_unknownReturnsNull() {
        assertNull(Ic705CivProtocol.buildSetModeCommand("INVALID"))
    }

    @Test
    fun encodeCtcssTone_67_0() {
        val bcd = Ic705CivProtocol.encodeCtcssToneBcd(67.0)
        assertContentEquals(byteArrayOf(0x06, 0x70, 0x00), bcd)
    }

    @Test
    fun encodeCtcssTone_74_4() {
        val bcd = Ic705CivProtocol.encodeCtcssToneBcd(74.4)
        assertContentEquals(byteArrayOf(0x07, 0x44, 0x00), bcd)
    }

    @Test
    fun encodeCtcssTone_141_3() {
        val bcd = Ic705CivProtocol.encodeCtcssToneBcd(141.3)
        assertContentEquals(byteArrayOf(0x14, 0x13, 0x00), bcd)
    }

    @Test
    fun buildSetCtcssToneCommand_correctFormat() {
        val cmd = Ic705CivProtocol.buildSetCtcssToneCommand(67.0)
        assertEquals(10, cmd.size)
        assertEquals(Ic705CivProtocol.CMD_SET_TONE, cmd[4])
        assertEquals(Ic705CivProtocol.SUB_SET_TONE, cmd[5])
        assertContentEquals(byteArrayOf(0x06, 0x70, 0x00), cmd.copyOfRange(6, 9))
    }

    @Test
    fun buildCtcssModeCommand_enable() {
        val cmd = Ic705CivProtocol.buildCtcssModeCommand(true)
        assertEquals(Ic705CivProtocol.CMD_CTCSS, cmd[4])
        assertEquals(Ic705CivProtocol.SUB_CTCSS_ON, cmd[5])
    }

    @Test
    fun buildCtcssModeCommand_disable() {
        val cmd = Ic705CivProtocol.buildCtcssModeCommand(false)
        assertEquals(Ic705CivProtocol.CMD_CTCSS, cmd[4])
        assertEquals(Ic705CivProtocol.SUB_CTCSS_OFF, cmd[5])
    }

    @Test
    fun buildSelectVfoCommand_vfoA() {
        val cmd = Ic705CivProtocol.buildSelectVfoCommand(Ic705CivProtocol.VFO_A)
        assertEquals(Ic705CivProtocol.CMD_SELECT_VFO, cmd[4])
        assertEquals(Ic705CivProtocol.VFO_A, cmd[5])
    }

    @Test
    fun buildSelectVfoCommand_vfoB() {
        val cmd = Ic705CivProtocol.buildSelectVfoCommand(Ic705CivProtocol.VFO_B)
        assertEquals(Ic705CivProtocol.CMD_SELECT_VFO, cmd[4])
        assertEquals(Ic705CivProtocol.VFO_B, cmd[5])
    }

    @Test
    fun buildSplitCommand_enable() {
        val cmd = Ic705CivProtocol.buildSplitCommand(true)
        assertEquals(Ic705CivProtocol.CMD_SPLIT, cmd[4])
        assertEquals(Ic705CivProtocol.SPLIT_ON, cmd[5])
    }

    @Test
    fun buildSplitCommand_disable() {
        val cmd = Ic705CivProtocol.buildSplitCommand(false)
        assertEquals(Ic705CivProtocol.CMD_SPLIT, cmd[4])
        assertEquals(Ic705CivProtocol.SPLIT_OFF, cmd[5])
    }

    @Test
    fun buildReadFreqCommand() {
        val cmd = Ic705CivProtocol.buildReadFreqCommand()
        assertEquals(6, cmd.size)
        assertEquals(Ic705CivProtocol.CMD_READ_FREQ, cmd[4])
    }

    @Test
    fun buildReadModeCommand() {
        val cmd = Ic705CivProtocol.buildReadModeCommand()
        assertEquals(6, cmd.size)
        assertEquals(Ic705CivProtocol.CMD_READ_MODE, cmd[4])
    }

    @Test
    fun parseResponse_validAckOk() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_SET_FREQ,
            Ic705CivProtocol.ACK_OK,
            Ic705CivProtocol.POSTAMBLE
        )
        val data = Ic705CivProtocol.parseResponse(response)
        assertNotNull(data)
        assertEquals(2, data.size) // [CMD_SET_FREQ, ACK_OK]
        assertEquals(Ic705CivProtocol.CMD_SET_FREQ, data[0])
        assertEquals(Ic705CivProtocol.ACK_OK, data[1])
    }

    @Test
    fun parseResponse_validAckNg() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_SET_MODE,
            Ic705CivProtocol.ACK_NG,
            Ic705CivProtocol.POSTAMBLE
        )
        val data = Ic705CivProtocol.parseResponse(response)
        assertNotNull(data)
        assertEquals(2, data.size) // [CMD_SET_MODE, ACK_NG]
        assertEquals(Ic705CivProtocol.CMD_SET_MODE, data[0])
        assertEquals(Ic705CivProtocol.ACK_NG, data[1])
    }

    @Test
    fun parseResponse_tooShort() {
        val response = byteArrayOf(Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE)
        assertNull(Ic705CivProtocol.parseResponse(response))
    }

    @Test
    fun parseResponse_noPreamble() {
        val response = byteArrayOf(0x00, 0x00, 0xA4.toByte(), 0xE0.toByte(), 0x03, 0xFD.toByte())
        assertNull(Ic705CivProtocol.parseResponse(response))
    }

    @Test
    fun parseResponse_noPostamble() {
        val response = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(), 0x03)
        assertNull(Ic705CivProtocol.parseResponse(response))
    }

    @Test
    fun isAck_ackOk() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.ACK_OK,
            Ic705CivProtocol.POSTAMBLE
        )
        assertTrue(Ic705CivProtocol.isAck(response))
        assertTrue(Ic705CivProtocol.isAckOk(response))
    }

    @Test
    fun isAck_ackNg() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.ACK_NG,
            Ic705CivProtocol.POSTAMBLE
        )
        assertTrue(Ic705CivProtocol.isAck(response))
        assertFalse(Ic705CivProtocol.isAckOk(response))
    }

    @Test
    fun parseFrequencyResponse_valid() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_READ_FREQ,
            0x00, 0x00, 0x80.toByte(), 0x45, 0x01, // 145800000 Hz
            Ic705CivProtocol.POSTAMBLE
        )
        val freq = Ic705CivProtocol.parseFrequencyResponse(response)
        assertNotNull(freq)
        assertEquals(145800000L, freq)
    }

    @Test
    fun parseFrequencyResponse_invalid() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_READ_MODE, // Wrong command
            0x01,
            Ic705CivProtocol.POSTAMBLE
        )
        assertNull(Ic705CivProtocol.parseFrequencyResponse(response))
    }

    @Test
    fun parseModeResponse_usb() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_READ_MODE,
            0x01, // USB
            0x01, // Filter
            Ic705CivProtocol.POSTAMBLE
        )
        val mode = Ic705CivProtocol.parseModeResponse(response)
        assertNotNull(mode)
        assertEquals("USB", mode)
    }

    @Test
    fun parseModeResponse_fm() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_READ_MODE,
            0x05, // FM
            0x01, // Filter
            Ic705CivProtocol.POSTAMBLE
        )
        val mode = Ic705CivProtocol.parseModeResponse(response)
        assertNotNull(mode)
        assertEquals("FM", mode)
    }

    @Test
    fun parseModeResponse_unknownMode() {
        val response = byteArrayOf(
            Ic705CivProtocol.PREAMBLE, Ic705CivProtocol.PREAMBLE,
            Ic705CivProtocol.ADDR_IC705, Ic705CivProtocol.ADDR_CONTROLLER,
            Ic705CivProtocol.CMD_READ_MODE,
            0x99.toByte(), // Unknown mode
            0x01,
            Ic705CivProtocol.POSTAMBLE
        )
        assertNull(Ic705CivProtocol.parseModeResponse(response))
    }
}
