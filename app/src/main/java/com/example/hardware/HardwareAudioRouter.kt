package com.example.hardware

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class HardwareRouteType(
    val profileKey: String,
    val label: String,
    val badge: String
) {
    USB_DAC("USB_DAC", "USB Hi-Fi DAC", "UAC DIRECT"),
    BLUETOOTH("BLUETOOTH", "Bluetooth Audio", "WIRELESS"),
    WIRED_AUX("WIRED_AUX", "Wired / 3.5mm AUX", "ANALOG LINE"),
    SPEAKER("SPEAKER", "Device Speaker", "INTERNAL")
}

data class ActiveAudioRoute(
    val routeType: HardwareRouteType = HardwareRouteType.SPEAKER,
    val deviceName: String = "Built-in Speaker",
    val supportedSampleRates: List<Int> = listOf(48000),
    val hardwareVolumePercent: Int = 70
)

/**
 * Monitors AudioManager via AudioDeviceCallback to instantly switch pre-amp, 10-band EQ,
 * Crossfeed, and DVC profiles whenever a USB DAC, Bluetooth headset, or AUX cable connects.
 */
class HardwareAudioRouter(
    context: Context,
    private val onRouteChanged: (HardwareRouteType, String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _activeRoute = MutableStateFlow(ActiveAudioRoute())
    val activeRoute: StateFlow<ActiveAudioRoute> = _activeRoute.asStateFlow()

    private var isRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            evaluateActiveOutputRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            evaluateActiveOutputRoute()
        }
    }

    fun register() {
        if (isRegistered) return
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        isRegistered = true
        evaluateActiveOutputRoute()
    }

    fun unregister() {
        if (!isRegistered) return
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        isRegistered = false
    }

    fun evaluateActiveOutputRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val usbDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val wiredDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                it.type == AudioDeviceInfo.TYPE_AUX_LINE
        }
        val btDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }

        val (selectedType, selectedInfo) = when {
            usbDevice != null -> HardwareRouteType.USB_DAC to usbDevice
            wiredDevice != null -> HardwareRouteType.WIRED_AUX to wiredDevice
            btDevice != null -> HardwareRouteType.BLUETOOTH to btDevice
            else -> HardwareRouteType.SPEAKER to outputs.firstOrNull()
        }

        val name = selectedInfo?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: selectedType.label
        val rates = selectedInfo?.sampleRates?.takeIf { it.isNotEmpty() }?.toList()?.sorted()
            ?: listOf(48000)

        val previousType = _activeRoute.value.routeType
        _activeRoute.value = ActiveAudioRoute(
            routeType = selectedType,
            deviceName = name,
            supportedSampleRates = rates,
            hardwareVolumePercent = getHardwareVolumePercent()
        )

        if (previousType != selectedType) {
            onRouteChanged(selectedType, name)
        }
    }

    fun getHardwareVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((cur.toFloat() / max.toFloat()) * 100f).roundToInt()
    }

    fun setHardwareVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        _activeRoute.value = _activeRoute.value.copy(hardwareVolumePercent = clamped)
    }
}
