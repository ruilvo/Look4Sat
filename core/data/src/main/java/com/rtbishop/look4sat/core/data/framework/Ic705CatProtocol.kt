/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Icom CI-V protocol encoder/decoder for IC-705.
 * 
 * CI-V message format: [FE FE] [To] [From] [Cmd] [Sub] [Data...] [FD]
 * - Preamble: FE FE
 * - To address: Radio address (0xA4 for IC-705)
 * - From address: Controller address (0xE0)
 * - Command byte(s)
 * - Data (optional, command-specific)
 * - End of message: FD
 */
object Ic705CatProtocol {

    // CI-V Protocol bytes
    const val PREAMBLE_1: Byte = 0xFE.toByte()
    const val PREAMBLE_2: Byte = 0xFE.toByte()
    const val EOM: Byte = 0xFD.toByte()
    const val RADIO_ADDR: Byte = 0xA4.toByte()  // IC-705
    const val CONTROLLER_ADDR: Byte = 0xE0.toByte()

    // Response status bytes
    const val OK: Byte = 0xFB.toByte()
    const val NG: Byte = 0xFA.toByte()

    // CI-V Commands
    const val CMD_SET_FREQ: Byte = 0x00
    const val CMD_SET_MODE: Byte = 0x06
    const val CMD_READ_FREQ: Byte = 0x03
    const val CMD_READ_MODE: Byte = 0x04
    const val CMD_SET_VFO: Byte = 0x07
    const val CMD_SPLIT: Byte = 0x0F
    const val CMD_PTT: Byte = 0x1C
    const val CMD_SET_TONE: Byte = 0x1A
    const val CMD_SET_FREQ_TO_VFO: Byte = 0x25

    // Sub-commands
    const val SUB_PTT_STATUS: Byte = 0x00
    const val SUB_TONE_SET: Byte = 0x05
    const val SUB_TONE_DATA_1: Byte = 0x00
    const val SUB_TONE_DATA_2: Byte = 0x94.toByte()
    const val SUB_SET_FREQ_TO_CURRENT_VFO: Byte = 0x00

    // VFO selection
    const val VFO_A: Byte = 0x00
    const val VFO_B: Byte = 0x01

    // Split mode
    const val SPLIT_OFF: Byte = 0x00
    const val SPLIT_ON: Byte = 0x01

    // Mode mappings (IC-705)
    val MODE_TO_BYTE: Map<String, Byte> = mapOf(
        "LSB" to 0x00,
        "USB" to 0x01,
        "AM" to 0x02,
        "CW" to 0x03,
        "RTTY" to 0x04,
        "FM" to 0x05,
        "WFM" to 0x06,
        "CW-R" to 0x07,
        "RTTY-R" to 0x08,
        "DV" to 0x17
    )

    val BYTE_TO_MODE: Map<Byte, String> = MODE_TO_BYTE.entries.associate { it.value to it.key }

    /**
     * Encode frequency in Hz to 5-byte BCD (1 Hz resolution).
     * IC-705 uses little-endian BCD pairs.
     * Example: 145,800,000 Hz → [0x00, 0x00, 0x80, 0x00, 0x45] (stored as 00008000145)
     */
    fun encodeFrequencyBcd(frequencyHz: Long): ByteArray {
        val bcd = ByteArray(5)
        val digits = String.format(Locale.US, "%010d", frequencyHz)
        // Encode in reverse order (little-endian pairs)
        for (i in 0 until 5) {
            val idx = 8 - (i * 2)
            val high = digits[idx] - '0'
            val low = digits[idx + 1] - '0'
            bcd[i] = ((low shl 4) or high).toByte()
        }
        return bcd
    }

    /**
     * Decode 5-byte BCD frequency to Hz.
     */
    fun decodeFrequencyBcd(bcd: ByteArray): Long {
        if (bcd.size < 5) return 0
        var frequencyHz = 0L
        // Decode little-endian pairs
        for (i in 4 downTo 0) {
            val b = bcd[i].toInt() and 0xFF
            val high = b and 0x0F
            val low = b shr 4
            frequencyHz = frequencyHz * 100 + high * 10 + low
        }
        return frequencyHz
    }

    /**
     * Encode CTCSS tone frequency (in Hz, e.g. 67.0) to 3-byte format.
     * Format: [0x00, high_byte, low_byte] where tone is in 0.1 Hz units
     * 67.0 Hz → 670 (0x029E) → [0x00, 0x02, 0x9E]
     */
    fun encodeCtcssTone(toneHz: Double): ByteArray {
        val tone01Hz = (toneHz * 10).roundToLong().toInt()
        return byteArrayOf(
            0x00,
            ((tone01Hz shr 8) and 0xFF).toByte(),
            (tone01Hz and 0xFF).toByte()
        )
    }

    /**
     * Build CI-V message with standard framing.
     */
    private fun buildMessage(command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        val message = ByteArray(5 + data.size + 1)
        message[0] = PREAMBLE_1
        message[1] = PREAMBLE_2
        message[2] = RADIO_ADDR
        message[3] = CONTROLLER_ADDR
        message[4] = command
        data.copyInto(message, 5)
        message[message.size - 1] = EOM
        return message
    }

    /**
     * Build command to set frequency (0x00).
     */
    fun buildSetFreqCommand(frequencyHz: Long): ByteArray {
        val bcd = encodeFrequencyBcd(frequencyHz)
        return buildMessage(CMD_SET_FREQ, bcd)
    }

    /**
     * Build command to set frequency to current VFO (0x25 0x00).
     */
    fun buildSetFreqToCurrentVfoCommand(frequencyHz: Long): ByteArray {
        val bcd = encodeFrequencyBcd(frequencyHz)
        val data = ByteArray(6)
        data[0] = SUB_SET_FREQ_TO_CURRENT_VFO
        bcd.copyInto(data, 1)
        return buildMessage(CMD_SET_FREQ_TO_VFO, data)
    }

    /**
     * Build command to set operating mode (0x06).
     */
    fun buildSetModeCommand(mode: String): ByteArray? {
        val modeByte = MODE_TO_BYTE[mode.uppercase(Locale.US)] ?: return null
        return buildMessage(CMD_SET_MODE, byteArrayOf(modeByte, 0x01))
    }

    /**
     * Build command to read operating frequency (0x03).
     */
    fun buildReadFreqCommand(): ByteArray {
        return buildMessage(CMD_READ_FREQ)
    }

    /**
     * Build command to read operating mode (0x04).
     */
    fun buildReadModeCommand(): ByteArray {
        return buildMessage(CMD_READ_MODE)
    }

    /**
     * Build command to set VFO (0x07).
     */
    fun buildSetVfoCommand(vfo: String): ByteArray? {
        val vfoByte = when (vfo.uppercase(Locale.US)) {
            "A" -> VFO_A
            "B" -> VFO_B
            else -> return null
        }
        return buildMessage(CMD_SET_VFO, byteArrayOf(vfoByte))
    }

    /**
     * Build command to enable/disable split (0x0F).
     */
    fun buildSplitCommand(enabled: Boolean): ByteArray {
        val splitByte = if (enabled) SPLIT_ON else SPLIT_OFF
        return buildMessage(CMD_SPLIT, byteArrayOf(splitByte))
    }

    /**
     * Build command to read PTT status (0x1C 0x00).
     */
    fun buildReadPttCommand(): ByteArray {
        return buildMessage(CMD_PTT, byteArrayOf(SUB_PTT_STATUS))
    }

    /**
     * Build command to set CTCSS tone (0x1A 0x05 0x00 0x94 [tone_data]).
     */
    fun buildSetCtcssToneCommand(toneHz: Double): ByteArray {
        val toneData = encodeCtcssTone(toneHz)
        val data = ByteArray(6)
        data[0] = SUB_TONE_SET
        data[1] = SUB_TONE_DATA_1
        data[2] = SUB_TONE_DATA_2
        toneData.copyInto(data, 3)
        return buildMessage(CMD_SET_TONE, data)
    }

    /**
     * Validate CI-V message structure.
     * Returns true if message has valid preamble, addresses, and EOM.
     */
    fun isValidMessage(message: ByteArray): Boolean {
        if (message.size < 6) return false
        return message[0] == PREAMBLE_1 &&
                message[1] == PREAMBLE_2 &&
                message[message.size - 1] == EOM
    }

    /**
     * Check if message is a response to our command (from radio to controller).
     */
    fun isResponseFromRadio(message: ByteArray): Boolean {
        if (message.size < 6) return false
        return message[2] == CONTROLLER_ADDR && message[3] == RADIO_ADDR
    }

    /**
     * Check if message is an OK acknowledgment.
     */
    fun isOkResponse(message: ByteArray): Boolean {
        if (message.size < 6) return false
        return isResponseFromRadio(message) && message[4] == OK
    }

    /**
     * Check if message is an NG (error) acknowledgment.
     */
    fun isNgResponse(message: ByteArray): Boolean {
        if (message.size < 6) return false
        return isResponseFromRadio(message) && message[4] == NG
    }

    /**
     * Parse frequency from read frequency response (0x03).
     * Expected format: FE FE E0 A4 03 [5-byte BCD] FD
     */
    fun parseReadFreqResponse(message: ByteArray): Long? {
        if (message.size < 11) return null
        if (!isResponseFromRadio(message)) return null
        if (message[4] != CMD_READ_FREQ) return null
        val bcd = message.copyOfRange(5, 10)
        return decodeFrequencyBcd(bcd)
    }

    /**
     * Parse mode from read mode response (0x04).
     * Expected format: FE FE E0 A4 04 [mode] [filter] FD
     */
    fun parseReadModeResponse(message: ByteArray): String? {
        if (message.size < 8) return null
        if (!isResponseFromRadio(message)) return null
        if (message[4] != CMD_READ_MODE) return null
        return BYTE_TO_MODE[message[5]]
    }

    /**
     * Parse PTT status from response (0x1C 0x00).
     * Expected format: FE FE E0 A4 1C 00 [status] FD
     * Status: 0x00 = OFF, 0x01 = ON
     */
    fun parsePttStatusResponse(message: ByteArray): Boolean? {
        if (message.size < 8) return null
        if (!isResponseFromRadio(message)) return null
        if (message[4] != CMD_PTT || message[5] != SUB_PTT_STATUS) return null
        return message[6] == 0x01.toByte()
    }

    /**
     * Get command byte from message (for response matching).
     */
    fun getCommandByte(message: ByteArray): Byte? {
        if (message.size < 6) return null
        if (!isValidMessage(message)) return null
        return message[4]
    }
}
