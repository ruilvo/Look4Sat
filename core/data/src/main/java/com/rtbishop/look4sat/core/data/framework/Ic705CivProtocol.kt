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

object Ic705CivProtocol {

    const val PREAMBLE: Byte = 0xFE.toByte()
    const val POSTAMBLE: Byte = 0xFD.toByte()
    const val ACK_OK: Byte = 0xFB.toByte()
    const val ACK_NG: Byte = 0xFA.toByte()

    const val ADDR_IC705: Byte = 0xA4.toByte()
    const val ADDR_CONTROLLER: Byte = 0xE0.toByte()

    const val CMD_READ_FREQ: Byte = 0x03
    const val CMD_READ_MODE: Byte = 0x04
    const val CMD_SET_FREQ: Byte = 0x05
    const val CMD_SET_MODE: Byte = 0x06
    const val CMD_SELECT_VFO: Byte = 0x07
    const val CMD_SELECT_MODE: Byte = 0x08
    const val CMD_SPLIT: Byte = 0x0F
    const val CMD_BAND_SELECT: Byte = 0x1A
    const val CMD_CTCSS: Byte = 0x16
    const val CMD_SET_TONE: Byte = 0x1B

    const val VFO_A: Byte = 0x00
    const val VFO_B: Byte = 0x01
    const val VFO_MODE: Byte = 0x00 // Operating mode: VFO
    const val MEM_MODE: Byte = 0x01 // Operating mode: Memory
    
    const val SUB_BAND_SELECT: Byte = 0x00
    
    // Band selection bytes for IC-705
    const val BAND_160M: Byte = 0x01
    const val BAND_80M: Byte = 0x02
    const val BAND_40M: Byte = 0x03
    const val BAND_30M: Byte = 0x04
    const val BAND_20M: Byte = 0x05
    const val BAND_17M: Byte = 0x06
    const val BAND_15M: Byte = 0x07
    const val BAND_12M: Byte = 0x08
    const val BAND_10M: Byte = 0x09
    const val BAND_6M: Byte = 0x10
    const val BAND_2M: Byte = 0x11
    const val BAND_70CM: Byte = 0x12
    const val BAND_23CM: Byte = 0x13

    const val SPLIT_OFF: Byte = 0x00
    const val SPLIT_ON: Byte = 0x01

    const val SUB_CTCSS_OFF: Byte = 0x42
    const val SUB_CTCSS_ON: Byte = 0x43

    const val SUB_SET_TONE: Byte = 0x00

    const val FILTER_FIL1: Byte = 0x01
    const val FILTER_FIL2: Byte = 0x02
    const val FILTER_FIL3: Byte = 0x03

    val MODE_TO_BYTE: Map<String, Byte> = mapOf(
        "LSB" to 0x00,
        "USB" to 0x01,
        "AM" to 0x02,
        "CW" to 0x03,
        "FM" to 0x05,
        "CW-R" to 0x07,
        "DIG" to 0x17.toByte()
    )

    val BYTE_TO_MODE: Map<Byte, String> = MODE_TO_BYTE.entries.associate { it.value to it.key }

    /**
     * Build CI-V command frame.
     * Frame: [0xFE 0xFE] [ToAddr] [FromAddr] [Command] [Data...] [0xFD]
     */
    private fun buildFrame(command: Byte, vararg data: Byte): ByteArray {
        return byteArrayOf(
            PREAMBLE, PREAMBLE,
            ADDR_IC705,
            ADDR_CONTROLLER,
            command,
            *data,
            POSTAMBLE
        )
    }

    /**
     * Encode frequency in Hz to 5-byte BCD with 1 Hz resolution (little-endian nibbles).
     * Example: 145800000 Hz → [0x00, 0x00, 0x80, 0x45, 0x01]
     * Format: [Hz ones/tens] [KHz ones/tens] [KHz hundreds, MHz ones] [MHz tens/hundreds] [GHz...]
     */
    fun encodeFrequencyBcd(frequencyHz: Long): ByteArray {
        val digits = String.format(Locale.US, "%010d", frequencyHz)
        val bcd = ByteArray(5)
        for (i in 0 until 5) {
            val idx = 10 - (i + 1) * 2
            val low = digits[idx] - '0'
            val high = digits[idx + 1] - '0'
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
        for (i in 4 downTo 0) {
            val b = bcd[i].toInt() and 0xFF
            val high = b shr 4
            val low = b and 0x0F
            frequencyHz = frequencyHz * 100 + high * 10 + low
        }
        return frequencyHz
    }

    /**
     * Encode CTCSS tone frequency (in Hz, e.g. 67.0) to 3-byte BCD.
     * 67.0 Hz → 0670 (in 0.1 Hz) → BCD [0x06, 0x70, 0x00]
     */
    fun encodeCtcssToneBcd(toneHz: Double): ByteArray {
        val tone01Hz = (toneHz * 10).roundToLong()
        val digits = String.format(Locale.US, "%04d", tone01Hz)
        val bcd = ByteArray(3)
        for (i in 0 until 2) {
            val high = digits[i * 2] - '0'
            val low = digits[i * 2 + 1] - '0'
            bcd[i] = ((high shl 4) or low).toByte()
        }
        bcd[2] = 0x00
        return bcd
    }

    fun buildSetFreqCommand(frequencyHz: Long): ByteArray {
        val bcd = encodeFrequencyBcd(frequencyHz)
        return buildFrame(CMD_SET_FREQ, *bcd)
    }

    fun buildReadFreqCommand(): ByteArray {
        return buildFrame(CMD_READ_FREQ)
    }

    fun buildSetModeCommand(mode: String, filter: Byte = FILTER_FIL1): ByteArray? {
        val modeByte = MODE_TO_BYTE[mode.uppercase(Locale.US)] ?: return null
        return buildFrame(CMD_SET_MODE, modeByte, filter)
    }

    fun buildReadModeCommand(): ByteArray {
        return buildFrame(CMD_READ_MODE)
    }

    fun buildSelectVfoCommand(vfo: Byte): ByteArray {
        return buildFrame(CMD_SELECT_VFO, vfo)
    }

    fun buildSelectOperatingModeCommand(): ByteArray {
        return buildFrame(CMD_SELECT_MODE, VFO_MODE)
    }

    fun buildBandSelectCommand(band: Byte): ByteArray {
        return buildFrame(CMD_BAND_SELECT, SUB_BAND_SELECT, band)
    }

    /**
     * Determine the band byte for a given frequency in Hz.
     * Returns null if frequency is out of range for IC-705.
     */
    fun getBandForFrequency(frequencyHz: Long): Byte? {
        return when (frequencyHz) {
            in 1_800_000L..2_000_000L -> BAND_160M
            in 3_500_000L..4_000_000L -> BAND_80M
            in 7_000_000L..7_300_000L -> BAND_40M
            in 10_100_000L..10_150_000L -> BAND_30M
            in 14_000_000L..14_350_000L -> BAND_20M
            in 18_068_000L..18_168_000L -> BAND_17M
            in 21_000_000L..21_450_000L -> BAND_15M
            in 24_890_000L..24_990_000L -> BAND_12M
            in 28_000_000L..29_700_000L -> BAND_10M
            in 50_000_000L..54_000_000L -> BAND_6M
            in 144_000_000L..148_000_000L -> BAND_2M
            in 430_000_000L..450_000_000L -> BAND_70CM
            in 1_240_000_000L..1_300_000_000L -> BAND_23CM
            else -> null
        }
    }

    fun buildSplitCommand(enabled: Boolean): ByteArray {
        val sub = if (enabled) SPLIT_ON else SPLIT_OFF
        return buildFrame(CMD_SPLIT, sub)
    }

    fun buildCtcssModeCommand(enabled: Boolean): ByteArray {
        val sub = if (enabled) SUB_CTCSS_ON else SUB_CTCSS_OFF
        return buildFrame(CMD_CTCSS, sub)
    }

    fun buildSetCtcssToneCommand(toneHz: Double): ByteArray {
        val bcd = encodeCtcssToneBcd(toneHz)
        return buildFrame(CMD_SET_TONE, SUB_SET_TONE, *bcd)
    }

    /**
     * Parse CI-V response frame.
     * Expected format: [0xFE 0xFE] [FromAddr] [ToAddr] [Command] [Data...] [0xFD]
     * Returns data payload (between command and postamble) or null if invalid.
     */
    fun parseResponse(response: ByteArray): ByteArray? {
        if (response.size < 6) return null
        if (response[0] != PREAMBLE || response[1] != PREAMBLE) return null
        if (response[response.size - 1] != POSTAMBLE) return null

        val fromAddr = response[2]
        val toAddr = response[3]
        if (fromAddr != ADDR_IC705 || toAddr != ADDR_CONTROLLER) return null

        return response.copyOfRange(5, response.size - 1)
    }

    /**
     * Check if response is ACK (OK or NG).
     */
    fun isAck(response: ByteArray): Boolean {
        val data = parseResponse(response) ?: return false
        return data.size == 1 && (data[0] == ACK_OK || data[0] == ACK_NG)
    }

    /**
     * Check if ACK is OK.
     */
    fun isAckOk(response: ByteArray): Boolean {
        val data = parseResponse(response) ?: return false
        return data.size == 1 && data[0] == ACK_OK
    }

    /**
     * Parse frequency read response.
     * Response data: [0x03] [5-byte BCD frequency]
     */
    fun parseFrequencyResponse(response: ByteArray): Long? {
        val data = parseResponse(response) ?: return null
        if (data.size < 6 || data[0] != CMD_READ_FREQ) return null
        val bcd = data.copyOfRange(1, 6)
        return decodeFrequencyBcd(bcd)
    }

    /**
     * Parse mode read response.
     * Response data: [0x04] [mode byte] [filter byte]
     */
    fun parseModeResponse(response: ByteArray): String? {
        val data = parseResponse(response) ?: return null
        if (data.size < 3 || data[0] != CMD_READ_MODE) return null
        val modeByte = data[1]
        return BYTE_TO_MODE[modeByte]
    }
}
