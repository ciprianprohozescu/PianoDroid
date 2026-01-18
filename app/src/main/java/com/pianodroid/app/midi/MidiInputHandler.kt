package com.pianodroid.app.midi

import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.IOException

/**
 * Handles MIDI device input using Android MIDI API.
 */
@RequiresApi(Build.VERSION_CODES.M)
class MidiInputHandler(private val midiManager: MidiManager) {
    private var currentDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null
    private var onNoteOn: ((pitch: Int, velocity: Int) -> Unit)? = null
    private var onNoteOff: ((pitch: Int) -> Unit)? = null

    fun setNoteCallbacks(
        onNoteOn: (pitch: Int, velocity: Int) -> Unit,
        onNoteOff: (pitch: Int) -> Unit
    ) {
        this.onNoteOn = onNoteOn
        this.onNoteOff = onNoteOff
    }

    fun getAvailableDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.toList()
    }

    fun openDevice(deviceInfo: MidiDeviceInfo) {
        closeDevice()

        midiManager.openDevice(deviceInfo, { device ->
            if (device == null) return@openDevice

            val inputPortCount = device.inputPortCount
            if (inputPortCount > 0) {
                try {
                    val port = device.openInputPort(0)
                    if (port != null) {
                        port.connect(MidiReceiverImpl())
                        currentDevice = device
                        inputPort = port
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun autoSelectFirstDevice() {
        val devices = getAvailableDevices()
        if (devices.isNotEmpty()) {
            openDevice(devices[0])
        }
    }

    fun closeDevice() {
        try {
            inputPort?.close()
            inputPort = null
            currentDevice?.close()
            currentDevice = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class MidiReceiverImpl : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count < 1) return

            val status = msg[offset].toInt() and 0xFF
            val command = status and 0xF0

            when (command) {
                0x90 -> {  // Note On
                    if (count >= 3) {
                        val pitch = msg[offset + 1].toInt() and 0xFF
                        val velocity = msg[offset + 2].toInt() and 0xFF
                        if (velocity > 0) {
                            onNoteOn?.invoke(pitch, velocity)
                        } else {
                            onNoteOff?.invoke(pitch)
                        }
                    }
                }
                0x80 -> {  // Note Off
                    if (count >= 2) {
                        val pitch = msg[offset + 1].toInt() and 0xFF
                        onNoteOff?.invoke(pitch)
                    }
                }
            }
        }
    }

    fun cleanup() {
        closeDevice()
    }
}
