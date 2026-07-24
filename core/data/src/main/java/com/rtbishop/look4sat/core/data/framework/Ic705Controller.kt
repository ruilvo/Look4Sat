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
    private val bandSwitchDelayMs = 200L
    private val responseTimeoutMs = 500L
    private val responseWaitMs = 50L // Wait for radio to process command before reading
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
            
            // Ensure radio is in VFO mode (not memory mode)
            delay(bandSwitchDelayMs)
            ioMutex.withLock {
                sendCommandWithAck(Ic705CivProtocol.buildSelectOperatingModeCommand())
            }
            
            Log.i(tag, "Connected to $deviceAddress")
            true
        } catch (e: Exception) {
            Log.e(tag, "Connect failed: ${e.message}")
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
            // Select appropriate band for this frequency
            val band = Ic705CivProtocol.getBandForFrequency(frequencyHz)
            if (band != null) {
                sendCommandWithAck(Ic705CivProtocol.buildBandSelectCommand(band))
                delay(bandSwitchDelayMs)
            }
            
            // Set frequency
            sendCommandWithAck(Ic705CivProtocol.buildSetFreqCommand(frequencyHz))
        }
    }

    /**
     * Set frequency without band selection (for Doppler tracking where band is already set).
     */
    suspend fun setFrequencyWithoutBand(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
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
            delay(responseWaitMs.milliseconds)
            val freqResponse = readCivResponse() ?: return@withContext null
            val frequency = Ic705CivProtocol.parseFrequencyResponse(freqResponse) ?: return@withContext null

            delay(commandDelayMs.milliseconds)

            val modeSent = sendCommand(Ic705CivProtocol.buildReadModeCommand())
            if (!modeSent) return@withContext null
            delay(responseWaitMs.milliseconds)
            val modeResponse = readCivResponse() ?: return@withContext null
            val mode = Ic705CivProtocol.parseModeResponse(modeResponse) ?: return@withContext null

            Pair(frequency, mode)
        }
    }

    suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val sent = sendCommand(Ic705CivProtocol.buildReadPttCommand())
            if (!sent) return@withContext null
            delay(responseWaitMs.milliseconds)
            val response = readCivResponse() ?: return@withContext null
            Ic705CivProtocol.parsePttResponse(response)
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildSetPttCommand(true))
        }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ic705CivProtocol.buildSetPttCommand(false))
        }
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
                true
            } catch (e: Exception) {
                Log.e(tag, "Send error: ${e.message}")
                isConnected = false
                false
            }
        }
    }

    private suspend fun sendCommandWithAck(bytes: ByteArray): Boolean {
        if (!sendCommand(bytes)) return false
        
        delay(responseWaitMs.milliseconds)
        val response = readCivResponse() ?: run {
            ackReadFailureCount++
            if (ackReadFailureCount >= maxAckReadFailures) {
                Log.e(tag, "Too many ACK failures, disconnecting")
                isConnected = false
                return false
            }
            return true // Best-effort
        }
        
        ackReadFailureCount = 0
        val isOk = Ic705CivProtocol.isAckOk(response)
        delay(commandDelayMs.milliseconds)
        return isOk
    }

    private suspend fun readCivResponse(): ByteArray? {
        val buffer = mutableListOf<Byte>()
        val startTime = System.currentTimeMillis()
        
        while (buffer.size < 256) { // Max frame size
            if (System.currentTimeMillis() - startTime > responseTimeoutMs) {
                return null
            }

            val available = inputStream?.available() ?: 0
            if (available == 0) {
                delay(10.milliseconds)
                continue
            }

            val byte = inputStream?.read() ?: run {
                isConnected = false
                return null
            }
            
            if (byte < 0) {
                isConnected = false
                return null
            }

            buffer.add(byte.toByte())

            if (byte.toByte() == Ic705CivProtocol.POSTAMBLE) {
                return buffer.toByteArray()
            }
        }

        return null // Max frame size exceeded
    }
}
