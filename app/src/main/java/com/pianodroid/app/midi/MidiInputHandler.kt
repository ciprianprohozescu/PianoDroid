package com.pianodroid.app.midi

import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
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
    private var outputPort: MidiOutputPort? = null
    private var onNoteOn: ((pitch: Int, velocity: Int) -> Unit)? = null
    private var onNoteOff: ((pitch: Int) -> Unit)? = null
    private var onDeviceChange: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            onDeviceChange?.invoke()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (currentDevice?.info?.id == device.id) {
                closeDevice()
                autoSelectFirstDevice()
            }
            onDeviceChange?.invoke()
        }
    }

    init {
        midiManager.registerDeviceCallback(deviceCallback, mainHandler)
    }

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

    fun getAvailableDeviceNames(): List<String> {
        return getAvailableDevices().map { device ->
            val name = device.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            name ?: "MIDI device ${device.id}"
        }
    }

    fun setDeviceChangeCallback(callback: () -> Unit) {
        onDeviceChange = callback
    }

    fun openDevice(deviceInfo: MidiDeviceInfo) {
        closeDevice()

        midiManager.openDevice(deviceInfo, { device ->
            if (device == null) return@openDevice

            val outputPortCount = device.info.outputPortCount
            if (outputPortCount > 0) {
                try {
                    val port = device.openOutputPort(0)
                    if (port != null) {
                        port.connect(MidiReceiverImpl())
                        currentDevice = device
                        outputPort = port
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
            outputPort?.close()
            outputPort = null
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
        midiManager.unregisterDeviceCallback(deviceCallback)
        closeDevice()
    }
}
