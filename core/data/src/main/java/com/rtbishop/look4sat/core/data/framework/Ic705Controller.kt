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

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class Ic705Controller(
    private val bluetoothManager: BluetoothManager,
    private val deviceAddress: String,
    private val splitMode: Boolean = false
) : IRadioController {

    private val tag = "IC705"
    private val sppId: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val ioMutex = Mutex()
    private val commandDelayMs = 100L
    private val responseTimeoutMs = 500L

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
            val btSocket = device.createInsecureRfcommSocketToServiceRecord(sppId)
            btSocket.connect()
            socket = btSocket
            outputStream = btSocket.outputStream
            inputStream = btSocket.inputStream
            isConnected = true
            Log.i(tag, "Connected to $deviceAddress (splitMode=$splitMode)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            isConnected = false
            false
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e(tag, "Disconnect error: ${e.message}")
            } finally {
                inputStream = null
                outputStream = null
                socket = null
                isConnected = false
                Log.i(tag, "Disconnected from $deviceAddress")
            }
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithResponse(
                Ic705CatProtocol.buildSetFreqCommand(frequencyHz),
                Ic705CatProtocol.CMD_SET_FREQ
            )
        }
    }

    override suspend fun setFrequencyToCurrentVfo(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithResponse(
                Ic705CatProtocol.buildSetFreqToCurrentVfoCommand(frequencyHz),
                Ic705CatProtocol.CMD_SET_FREQ_TO_VFO
            )
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ic705CatProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock {
            sendCommandWithResponse(cmd, Ic705CatProtocol.CMD_SET_MODE)
        }
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        // IC-705 handles CTCSS differently - enabling tone automatically enables encode
        // This is a no-op for Icom, tone setting is sufficient
        true
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithResponse(
                Ic705CatProtocol.buildSetCtcssToneCommand(toneHz),
                Ic705CatProtocol.CMD_SET_TONE
            )
        }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            // Read frequency
            val freqCmd = Ic705CatProtocol.buildReadFreqCommand()
            if (!sendCommand(freqCmd)) return@withContext null
            val freqResponse = readResponseWithTimeout(Ic705CatProtocol.CMD_READ_FREQ)
            val frequency = freqResponse?.let { Ic705CatProtocol.parseReadFreqResponse(it) }
                ?: return@withContext null

            // Read mode
            val modeCmd = Ic705CatProtocol.buildReadModeCommand()
            if (!sendCommand(modeCmd)) return@withContext null
            val modeResponse = readResponseWithTimeout(Ic705CatProtocol.CMD_READ_MODE)
            val mode = modeResponse?.let { Ic705CatProtocol.parseReadModeResponse(it) }
                ?: return@withContext null

            Pair(frequency, mode)
        }
    }

    override suspend fun readPttStatus(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val cmd = Ic705CatProtocol.buildReadPttCommand()
            if (!sendCommand(cmd)) return@withContext null
            val response = readResponseWithTimeout(Ic705CatProtocol.CMD_PTT)
            response?.let { Ic705CatProtocol.parsePttStatusResponse(it) }
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        // PTT control not typically used in satellite tracking
        // IC-705 uses 0x1C 0x00 0x01 for PTT ON
        false
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        // PTT control not typically used in satellite tracking
        // IC-705 uses 0x1C 0x00 0x00 for PTT OFF
        false
    }

    suspend fun setSplit(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithResponse(
                Ic705CatProtocol.buildSplitCommand(enabled),
                Ic705CatProtocol.CMD_SPLIT
            )
        }
    }

    suspend fun setVfo(vfo: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ic705CatProtocol.buildSetVfoCommand(vfo) ?: return@withContext false
        ioMutex.withLock {
            sendCommandWithResponse(cmd, Ic705CatProtocol.CMD_SET_VFO)
        }
    }

    private suspend fun sendCommand(bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write(bytes) ?: return@withContext false
                outputStream?.flush()
                delay(commandDelayMs.milliseconds)
                true
            } catch (e: Exception) {
                Log.e(tag, "Send error: ${e.message}")
                isConnected = false
                false
            }
        }
    }

    /**
     * Send command and wait for OK/NG response or command echo.
     */
    private suspend fun sendCommandWithResponse(bytes: ByteArray, expectedCommand: Byte): Boolean {
        if (!sendCommand(bytes)) return false
        return withContext(Dispatchers.IO) {
            val response = readResponseWithTimeout(expectedCommand)
            if (response == null) {
                Log.w(tag, "No response for command 0x${expectedCommand.toString(16)}")
                return@withContext false
            }
            // Check for OK/NG or command echo
            when {
                Ic705CatProtocol.isOkResponse(response) -> true
                Ic705CatProtocol.isNgResponse(response) -> {
                    Log.w(tag, "Radio returned NG for command 0x${expectedCommand.toString(16)}")
                    false
                }
                else -> {
                    // Command echo is also acceptable
                    true
                }
            }
        }
    }

    /**
     * Read CI-V response with timeout, filtering broadcasts.
     * Returns only messages matching the expected command.
     */
    private suspend fun readResponseWithTimeout(expectedCommand: Byte): ByteArray? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < responseTimeoutMs) {
            val message = readCivMessage() ?: continue
            
            if (!Ic705CatProtocol.isValidMessage(message)) {
                Log.d(tag, "Invalid message format")
                continue
            }
            
            if (!Ic705CatProtocol.isResponseFromRadio(message)) {
                Log.d(tag, "Ignoring non-response message")
                continue
            }
            
            val cmdByte = Ic705CatProtocol.getCommandByte(message)
            
            // Accept OK/NG responses or matching command echo
            if (cmdByte == Ic705CatProtocol.OK || cmdByte == Ic705CatProtocol.NG) {
                return message
            }
            
            if (cmdByte == expectedCommand) {
                return message
            }
            
            // Otherwise it's a broadcast or different command - log and continue
            Log.d(tag, "Ignoring broadcast/unmatched: cmd=0x${cmdByte?.toString(16)}, expected=0x${expectedCommand.toString(16)}")
        }
        
        Log.w(tag, "Response timeout after ${responseTimeoutMs}ms for command 0x${expectedCommand.toString(16)}")
        return null
    }

    /**
     * Read one complete CI-V message (FE FE ... FD).
     * Returns null if stream closed or error.
     */
    private suspend fun readCivMessage(): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val buffer = mutableListOf<Byte>()
                var foundPreamble = false
                
                // Look for preamble FE FE
                while (!foundPreamble && buffer.size < 100) {
                    val b = inputStream?.read() ?: return@withContext null
                    if (b < 0) {
                        isConnected = false
                        return@withContext null
                    }
                    buffer.add(b.toByte())
                    
                    if (buffer.size >= 2) {
                        if (buffer[buffer.size - 2] == Ic705CatProtocol.PREAMBLE_1 &&
                            buffer[buffer.size - 1] == Ic705CatProtocol.PREAMBLE_2) {
                            foundPreamble = true
                            buffer.clear()
                            buffer.add(Ic705CatProtocol.PREAMBLE_1)
                            buffer.add(Ic705CatProtocol.PREAMBLE_2)
                        }
                    }
                }
                
                if (!foundPreamble) return@withContext null
                
                // Read until EOM (FD) or max length
                while (buffer.size < 256) {
                    val b = inputStream?.read() ?: return@withContext null
                    if (b < 0) {
                        isConnected = false
                        return@withContext null
                    }
                    buffer.add(b.toByte())
                    
                    if (b.toByte() == Ic705CatProtocol.EOM) {
                        return@withContext buffer.toByteArray()
                    }
                }
                
                Log.w(tag, "Message too long without EOM")
                null
            } catch (e: Exception) {
                Log.e(tag, "Read error: ${e.message}")
                isConnected = false
                null
            }
        }
    }
}
