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
    private val deviceAddress: String
) : IRadioController {

    private val tag = "IC705"
    private val sppId: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val ioMutex = Mutex()
    private val commandDelayMs = 100L
    private val responseTimeoutMs = 500L
    private val maxAckReadFailures = 3

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var ackReadFailureCount = 0

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
            ackReadFailureCount = 0
            isConnected = true
            Log.i(tag, "Connected to $deviceAddress")
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
                ackReadFailureCount = 0
                isConnected = false
                Log.i(tag, "Disconnected from $deviceAddress")
            }
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildSetFreqCommand(frequencyHz))
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ic705CivProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock { sendCommandWithAck(cmd) }
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildCtcssModeCommand(enabled))
        }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildSetCtcssToneCommand(toneHz))
        }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val freqSent = sendCommand(Ic705CivProtocol.buildReadFreqCommand())
            if (!freqSent) return@withContext null
            val freqResponse = readCivResponse() ?: return@withContext null
            val frequency = Ic705CivProtocol.parseFrequencyResponse(freqResponse) ?: return@withContext null

            delay(commandDelayMs.milliseconds)

            val modeSent = sendCommand(Ic705CivProtocol.buildReadModeCommand())
            if (!modeSent) return@withContext null
            val modeResponse = readCivResponse() ?: return@withContext null
            val mode = Ic705CivProtocol.parseModeResponse(modeResponse) ?: return@withContext null

            Pair(frequency, mode)
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        true // PTT not implemented for IC-705 yet
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        true // PTT not implemented for IC-705 yet
    }

    override suspend fun setVfo(vfo: IRadioController.Vfo): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val vfoByte = when (vfo) {
                IRadioController.Vfo.VFO_A -> Ic705CivProtocol.VFO_A
                IRadioController.Vfo.VFO_B -> Ic705CivProtocol.VFO_B
            }
            sendCommandWithAck(Ic705CivProtocol.buildSelectVfoCommand(vfoByte))
        }
    }

    override suspend fun setSplit(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildSplitCommand(enabled))
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

    /** Send command and read CI-V ACK response (0xFB = OK, 0xFA = NG). */
    private suspend fun sendCommandWithAck(bytes: ByteArray): Boolean {
        if (!sendCommand(bytes)) return false
        return withContext(Dispatchers.IO) {
            try {
                val response = readCivResponse() ?: run {
                    ackReadFailureCount += 1
                    Log.w(tag, "ACK read failure (${ackReadFailureCount}/$maxAckReadFailures)")
                    if (ackReadFailureCount >= maxAckReadFailures) {
                        Log.e(tag, "Too many ACK read errors, marking radio disconnected")
                        isConnected = false
                        return@withContext false
                    }
                    return@withContext true // Command sent, treat as best-effort
                }
                ackReadFailureCount = 0
                val isOk = Ic705CivProtocol.isAckOk(response)
                if (!isOk) {
                    Log.w(tag, "Command returned NG or invalid ACK")
                }
                isOk
            } catch (e: Exception) {
                ackReadFailureCount += 1
                Log.w(tag, "ACK read error (${ackReadFailureCount}/$maxAckReadFailures): ${e.message}")
                if (ackReadFailureCount >= maxAckReadFailures) {
                    Log.e(tag, "Too many ACK read errors, marking radio disconnected")
                    isConnected = false
                    false
                } else {
                    true
                }
            }
        }
    }

    /**
     * Read CI-V response frame.
     * CI-V frames: [0xFE 0xFE] [ToAddr] [FromAddr] [Command] [Data...] [0xFD]
     * Read until 0xFD postamble or timeout.
     */
    private suspend fun readCivResponse(): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val buffer = mutableListOf<Byte>()
                val startTime = System.currentTimeMillis()
                
                while (buffer.size < 256) { // Max frame size safety
                    if (System.currentTimeMillis() - startTime > responseTimeoutMs) {
                        Log.w(tag, "Response timeout after ${buffer.size} bytes")
                        return@withContext null
                    }

                    val available = inputStream?.available() ?: 0
                    if (available == 0) {
                        delay(10.milliseconds)
                        continue
                    }

                    val byte = inputStream?.read() ?: run {
                        isConnected = false
                        return@withContext null
                    }
                    
                    if (byte < 0) {
                        Log.i(tag, "Response stream closed by remote device")
                        isConnected = false
                        return@withContext null
                    }

                    buffer.add(byte.toByte())

                    if (byte.toByte() == Ic705CivProtocol.POSTAMBLE) {
                        return@withContext buffer.toByteArray()
                    }
                }

                Log.w(tag, "Response exceeded max frame size")
                null
            } catch (e: Exception) {
                Log.e(tag, "Read error: ${e.message}")
                isConnected = false
                null
            }
        }
    }
}
