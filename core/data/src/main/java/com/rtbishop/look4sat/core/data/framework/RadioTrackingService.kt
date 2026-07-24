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
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo
) : IRadioTrackingService {

    private val tag = "RadioTracking"
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null

    override suspend fun connectRadios() {
        // Disconnect old controllers if any
        txController?.disconnect()
        rxController?.disconnect()

        // Read current addresses from settings
        val rcSettings = settingsRepo.radioControlSettings.value
        val txAddr = rcSettings.txRadioAddress
        val rxAddr = rcSettings.rxRadioAddress
        val radioModel = rcSettings.radioModel
        val splitMode = rcSettings.splitMode
        val isIcom = radioModel.startsWith("Icom")

        Log.i(tag, "Connecting model=$radioModel splitMode=$splitMode TX=$txAddr RX=$rxAddr")

        // Validate addresses
        if (splitMode && txAddr.isBlank()) {
            _state.update { it.copy(errorMessage = "No radio address configured for split mode") }
            return
        }
        if (!splitMode && txAddr.isBlank() && rxAddr.isBlank()) {
            _state.update { it.copy(errorMessage = "No radio addresses configured in Settings") }
            return
        }

        // Create controllers based on radio model
        val tx: IRadioController? = if (txAddr.isNotBlank()) {
            when {
                isIcom -> Ic705Controller(bluetoothManager, txAddr, splitMode)
                else -> Ft817Controller(bluetoothManager, txAddr)
            }
        } else null

        val rx: IRadioController? = if (!splitMode && rxAddr.isNotBlank()) {
            when {
                isIcom -> Ic705Controller(bluetoothManager, rxAddr, splitMode = false)
                else -> Ft817Controller(bluetoothManager, rxAddr)
            }
        } else null

        txController = tx
        rxController = rx

        _state.update { it.copy(errorMessage = null) }
        
        // Connect radios
        val txOk = tx?.connect() ?: false
        val rxOk = if (splitMode) {
            false // No RX radio in split mode
        } else {
            rx?.connect() ?: false
        }

        _state.update {
            it.copy(
                txConnected = txOk,
                rxConnected = rxOk,
                errorMessage = when {
                    splitMode && !txOk -> "Could not connect to radio ($txAddr)"
                    !splitMode && !txOk && !rxOk -> "Could not connect to TX and RX radios"
                    !splitMode && !txOk -> "Could not connect to TX radio ($txAddr)"
                    !splitMode && !rxOk -> "Could not connect to RX radio ($rxAddr)"
                    else -> null
                }
            )
        }
    }

    override suspend fun disconnectRadios() {
        stopTracking()
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        _state.update {
            it.copy(
                txConnected = false,
                rxConnected = false,
                isActive = false
            )
        }
    }

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        _state.update {
            it.copy(
                isActive = true,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz
            )
        }
        trackingJob?.cancel()
        trackingJob = appScope.launch {
            val rcSettings = settingsRepo.radioControlSettings.value
            val splitMode = rcSettings.splitMode
            val isIcom = rcSettings.radioModel.startsWith("Icom")
            
            // Set modes on both radios at tracking start
            val tx = txController
            val rx = rxController
            val txMode = transponder.uplinkMode
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }

            // Compute initial frequencies for split mode setup
            val txBaseFreq = txBaseFreqHz ?: when {
                transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                    (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
                transponder.uplinkLow != null -> transponder.uplinkLow!!
                else -> null
            }
            val rxBaseFreq = if (txBaseFreq != null) {
                TransponderMapper.mapUplinkToDownlink(txBaseFreq, transponder)
            } else {
                transponder.downlinkLow
            }

            // Split mode setup (Icom only)
            if (splitMode && isIcom && tx is Ic705Controller) {
                Log.i(tag, "Setting up split mode on IC-705")
                
                // Enable split
                tx.setSplit(true)
                
                // Set VFO A (main/RX) - downlink
                if (rxMode != null && rxBaseFreq != null) {
                    tx.setVfo("A")
                    delay(200) // Give radio time to switch VFO
                    tx.setFrequency(rxBaseFreq)
                    delay(200)
                    tx.setMode(rxMode)
                    Log.i(tag, "VFO A (RX): freq=$rxBaseFreq mode=$rxMode")
                }
                
                // Set VFO B (sub/TX) - uplink
                if (txMode != null && txBaseFreq != null) {
                    tx.setVfo("B")
                    delay(200) // Give radio time to switch VFO
                    tx.setFrequency(txBaseFreq)
                    delay(200)
                    tx.setMode(txMode)
                    Log.i(tag, "VFO B (TX): freq=$txBaseFreq mode=$txMode")
                    
                    // Set CTCSS if FM
                    if (txMode.uppercase() == "FM") {
                        _state.value.ctcssTone?.let { tone ->
                            tx.setCtcssTone(tone)
                            Log.i(tag, "CTCSS tone set to $tone Hz")
                        }
                    }
                }
                
                // Return to VFO A (main)
                tx.setVfo("A")
                delay(200)
                Log.i(tag, "Split mode setup complete, returned to VFO A")
            } else {
                // Dual radio mode setup (existing behavior)
                if (tx != null && tx.isConnected && txMode != null) {
                    tx.setMode(txMode)
                    Log.i(tag, "TX mode set to $txMode")
                }
                if (rx != null && rx.isConnected && rxMode != null) {
                    rx.setMode(rxMode)
                    Log.i(tag, "RX mode set to $rxMode")
                }
                // Set CTCSS if FM
                if (txMode?.uppercase() == "FM") {
                    _state.value.ctcssTone?.let { tone ->
                        tx?.setCtcssTone(tone)
                        tx?.setCtcssMode(true)
                    }
                }
            }
            
            _state.update { it.copy(txMode = txMode, rxMode = rxMode) }

            var lastSetTxFreq = 0.0
            var lastSetRxFreq = 0.0
            var tuningRadio = "" // "", "tx", or "rx" - which radio the user is tuning
            var lastReadFreq = 0L
            var stableCount = 0

            while (isActive) {
                val currentState = _state.value
                if (!currentState.isActive) break

                val satPass = currentState.currentPass ?: break
                val xpdr = currentState.selectedTransponder ?: break
                var txBaseFreq = currentState.txBaseFrequencyHz
                val stationPos = settingsRepo.stationPosition.value
                val timeNow = System.currentTimeMillis()

                val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, timeNow)
                val tx = txController
                val rx = rxController
                val hasUplink = txBaseFreq != null
                val c = com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
                val v = pos.distanceRate * 1000.0

                // Skip dial feedback in split mode (one radio, can't read while tracking)
                if (!splitMode && tuningRadio.isNotEmpty()) {
                    // --- User is tuning: keep reading, wait for stabilization ---
                    val radio = if (tuningRadio == "tx") tx else rx
                    if (radio != null && radio.isConnected) {
                        val readResult = radio.readFrequencyAndMode()
                        if (readResult != null) {
                            val (freq, _) = readResult
                            if (kotlin.math.abs(freq - lastReadFreq) <= 20) {
                                stableCount++
                            } else {
                                stableCount = 0
                                lastReadFreq = freq
                            }
                            // Stable for 2 reads → user stopped turning
                            if (stableCount >= 2) {
                                if (tuningRadio == "tx" && txBaseFreq != null) {
                                    val newBase = (freq.toDouble() * c / (c + v)).toLong()
                                    if (newBase > 0) {
                                        txBaseFreq = newBase
                                        _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                        Log.i(tag, "TX tuning done → base=$newBase")
                                    }
                                } else if (tuningRadio == "rx") {
                                    val rxNominal = (freq.toDouble() * c / (c - v)).toLong()
                                    val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                                    if (newTxBase != null && newTxBase > 0) {
                                        txBaseFreq = newTxBase
                                        _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                        Log.i(tag, "RX tuning done → txBase=$newTxBase")
                                    }
                                }
                                tuningRadio = ""
                                stableCount = 0
                                lastSetTxFreq = 0.0
                                lastSetRxFreq = 0.0
                            }
                        }
                    }
                } else if (!splitMode) {
                    // --- Normal tracking: read, detect changes, command (dual radio mode only) ---

                    // TX dial feedback
                    if (hasUplink && tx != null && tx.isConnected && lastSetTxFreq > 0.0) {
                        val readResult = tx.readFrequencyAndMode()
                        if (readResult != null) {
                            val (actualTxFreq, _) = readResult
                            if (kotlin.math.abs(actualTxFreq - lastSetTxFreq) >= 20.0) {
                                tuningRadio = "tx"
                                lastReadFreq = actualTxFreq
                                stableCount = 0
                                Log.i(tag, "TX tuning detected (read=$actualTxFreq, lastSet=$lastSetTxFreq)")
                            }
                        }
                    }

                    // RX dial feedback (only if TX not tuning)
                    if (tuningRadio.isEmpty() && rx != null && rx.isConnected && lastSetRxFreq > 0.0) {
                        val readResult = rx.readFrequencyAndMode()
                        if (readResult != null) {
                            val (actualRxFreq, _) = readResult
                            if (kotlin.math.abs(actualRxFreq - lastSetRxFreq) >= 20.0) {
                                tuningRadio = "rx"
                                lastReadFreq = actualRxFreq
                                stableCount = 0
                                Log.i(tag, "RX tuning detected (read=$actualRxFreq, lastSet=$lastSetRxFreq)")
                            }
                        }
                    }
                }

                // Compute Doppler-corrected frequencies
                val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
                val rxBaseFreq = if (txBaseFreq != null) {
                    TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
                } else {
                    xpdr.downlinkLow
                }
                val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }

                // Command radios (only when not tuning)
                if (tuningRadio.isEmpty()) {
                    if (splitMode && isIcom && tx is Ic705Controller) {
                        // Split mode: use PTT status to determine which frequency to set
                        val pttActive = tx.readPttStatus() ?: false
                        
                        if (pttActive && txRadioFreq != null) {
                            // PTT ON - radio switched to VFO B (TX), update TX frequency
                            Log.d(tag, "Split mode: PTT ON, setting TX freq=$txRadioFreq Hz")
                            tx.setFrequencyToCurrentVfo(txRadioFreq)
                            lastSetTxFreq = txRadioFreq.toDouble()
                        } else if (!pttActive && rxRadioFreq != null) {
                            // PTT OFF - radio on VFO A (RX), update RX frequency
                            Log.d(tag, "Split mode: PTT OFF, setting RX freq=$rxRadioFreq Hz")
                            tx.setFrequencyToCurrentVfo(rxRadioFreq)
                            lastSetRxFreq = rxRadioFreq.toDouble()
                        }
                    } else {
                        // Dual radio mode: update both radios
                        if (tx != null && tx.isConnected && txRadioFreq != null) {
                            tx.setFrequency(txRadioFreq)
                            lastSetTxFreq = txRadioFreq.toDouble()
                        }
                        if (rx != null && rx.isConnected && rxRadioFreq != null) {
                            rx.setFrequency(rxRadioFreq)
                            lastSetRxFreq = rxRadioFreq.toDouble()
                        }
                    }
                }

                _state.update {
                    it.copy(
                        txConnected = tx?.isConnected ?: false,
                        rxConnected = rx?.isConnected ?: false,
                        txFrequencyHz = txRadioFreq,
                        rxFrequencyHz = rxRadioFreq,
                        azimuth = Math.toDegrees(pos.azimuth),
                        elevation = Math.toDegrees(pos.elevation),
                        distance = pos.distance
                    )
                }

                delay(1000)
            }
        }
    }

    override fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isActive = false) }
    }

    override fun setTransponder(transponder: SatRadio) {
        appScope.launch {
            val tx = txController
            val rx = rxController
            transponder.uplinkMode?.let { tx?.setMode(it) }
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            rxMode?.let { rx?.setMode(it) }

            if (transponder.uplinkMode?.uppercase() == "FM") {
                _state.value.ctcssTone?.let { tone ->
                    tx?.setCtcssTone(tone)
                    tx?.setCtcssMode(true)
                }
            }
        }
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        // Show nominal frequencies immediately
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else {
            // Downlink-only transponder (beacon etc.) - use downlink directly
            transponder.downlinkLow
        }
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz = txCenter,
                txFrequencyHz = txCenter,
                rxFrequencyHz = rxNominal,
                txMode = transponder.uplinkMode,
                rxMode = transponder.downlinkMode
                    ?: transponder.uplinkMode?.let { m ->
                        TransponderMapper.mapUplinkModeToDownlinkMode(m, transponder.isInverted)
                    }
            )
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        _state.update { it.copy(txBaseFrequencyHz = frequencyHz) }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        val current = _state.value.txBaseFrequencyHz ?: return
        _state.update { it.copy(txBaseFrequencyHz = current + deltaHz) }
    }

    override fun setCtcssTone(toneHz: Double?) {
        _state.update { it.copy(ctcssTone = toneHz) }
        appScope.launch {
            val tx = txController
            if (toneHz != null) {
                tx?.setCtcssTone(toneHz)
                tx?.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false)
            }
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        appScope.launch {
            txController?.setMode(txMode)
            rxController?.setMode(rxMode)
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }
    }

}
