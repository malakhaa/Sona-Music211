package com.example.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.audio.NativeAudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsbDacState(
    val isConnected: Boolean = false,
    val hasPermission: Boolean = false,
    val isBitPerfectBypassActive: Boolean = false,
    val deviceName: String = "No External DAC",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val uacSpec: String = "—",
    val endpointAddress: Int = 0,
    val maxPacketSize: Int = 0,
    val activeSampleRateHz: Int = 192000,
    val activeBitDepth: Int = 24,
    val statusMessage: String = "Connect a USB-C DAC/AMP to enable direct UAC 1.0/2.0 bypass"
)

/**
 * Manages USB Audio Class (UAC 1.0/2.0) external DAC discovery, interface claiming,
 * isochronous endpoint negotiation, and native file-descriptor handoff to libsona_audio_engine.so.
 */
class UsbDacManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbDacManager"
        const val ACTION_USB_PERMISSION = "com.example.sonamusic.USB_PERMISSION"
        private const val USB_SUBCLASS_AUDIO_STREAMING = 2
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(UsbDacState())
    val state: StateFlow<UsbDacState> = _state.asStateFlow()

    private var activeConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var activeDevice: UsbDevice? = null
    private var isRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = extractUsbDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        claimAndHandoffDevice(device)
                    } else {
                        _state.update {
                            it.copy(
                                hasPermission = false,
                                isBitPerfectBypassActive = false,
                                statusMessage = "USB DAC permission denied by user"
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanConnectedDacs(requestPermissionIfMissing = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = extractUsbDevice(intent)
                    if (detached == null || detached.deviceId == activeDevice?.deviceId) {
                        releaseUsbDac()
                    }
                }
            }
        }
    }

    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        isRegistered = true
        scanConnectedDacs(requestPermissionIfMissing = false)
    }

    fun unregister() {
        if (!isRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        releaseUsbDac()
        isRegistered = false
    }

    fun scanConnectedDacs(requestPermissionIfMissing: Boolean = true) {
        val audioDac = usbManager.deviceList.values.firstOrNull { isUsbAudioDevice(it) }
        if (audioDac == null) {
            _state.value = UsbDacState(
                isConnected = false,
                statusMessage = "No USB Audio Class DAC detected on OTG bus"
            )
            return
        }

        activeDevice = audioDac
        val dacName = audioDac.productName?.takeIf { it.isNotBlank() }
            ?: "USB DAC (${"%04X".format(audioDac.vendorId)}:${"%04X".format(audioDac.productId)})"

        if (usbManager.hasPermission(audioDac)) {
            claimAndHandoffDevice(audioDac)
        } else {
            _state.update {
                it.copy(
                    isConnected = true,
                    hasPermission = false,
                    deviceName = dacName,
                    vendorId = audioDac.vendorId,
                    productId = audioDac.productId,
                    statusMessage = "USB DAC ready — grant host access for Bit-Perfect UAC bypass"
                )
            }
            if (requestPermissionIfMissing) {
                requestPermission(audioDac)
            }
        }
    }

    fun requestPermissionForActiveDac() {
        val device = activeDevice ?: usbManager.deviceList.values.firstOrNull { isUsbAudioDevice(it) }
        if (device != null) {
            requestPermission(device)
        } else {
            _state.update {
                it.copy(statusMessage = "No UAC 1.0/2.0 DAC attached to USB-C port")
            }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    /**
     * Claims the USB Audio Streaming interface (alt-setting with an OUT isochronous endpoint)
     * and passes the raw file descriptor to the C++ native USBDEVFS_SUBMITURB engine.
     */
    fun claimAndHandoffDevice(device: UsbDevice, targetSampleRate: Int = 192000, targetBitDepth: Int = 24): Boolean {
        releaseUsbDac()

        val candidate = findBestAudioStreamingInterface(device)
        if (candidate == null) {
            _state.update {
                it.copy(
                    isConnected = true,
                    hasPermission = true,
                    isBitPerfectBypassActive = false,
                    statusMessage = "DAC attached, but no isochronous OUT streaming endpoint found"
                )
            }
            return false
        }

        val (usbInterface, isoEndpoint) = candidate
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            _state.update {
                it.copy(
                    isConnected = true,
                    hasPermission = false,
                    statusMessage = "Unable to open USB device connection"
                )
            }
            return false
        }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            _state.update {
                it.copy(
                    isConnected = true,
                    hasPermission = true,
                    isBitPerfectBypassActive = false,
                    statusMessage = "Failed to claim USB Audio interface (busy by system)"
                )
            }
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connection.setInterface(usbInterface)
        }

        activeConnection = connection
        claimedInterface = usbInterface
        activeDevice = device

        val fd = connection.fileDescriptor
        val uacSpec = if (usbInterface.interfaceProtocol == 0x20 || isoEndpoint.maxPacketSize > 512) {
            "UAC 2.0 (Hi-Res Async)"
        } else {
            "UAC 1.0 (Adaptive/Sync)"
        }

        val nativeHandoffOk = NativeAudioEngine.startUsbDacBypass(
            fd = fd,
            endpointAddress = isoEndpoint.address,
            maxPacketSize = isoEndpoint.maxPacketSize,
            sampleRate = targetSampleRate,
            bitDepth = targetBitDepth
        )

        val dacName = device.productName?.takeIf { it.isNotBlank() }
            ?: "USB DAC (${"%04X".format(device.vendorId)}:${"%04X".format(device.productId)})"

        _state.value = UsbDacState(
            isConnected = true,
            hasPermission = true,
            isBitPerfectBypassActive = true,
            deviceName = dacName,
            vendorId = device.vendorId,
            productId = device.productId,
            uacSpec = uacSpec,
            endpointAddress = isoEndpoint.address,
            maxPacketSize = isoEndpoint.maxPacketSize,
            activeSampleRateHz = targetSampleRate,
            activeBitDepth = targetBitDepth,
            statusMessage = if (nativeHandoffOk) {
                "Bit-Perfect UAC bypass active on FD $fd (EP 0x${"%02X".format(isoEndpoint.address)})"
            } else {
                "USB interface claimed (EP 0x${"%02X".format(isoEndpoint.address)}, ${isoEndpoint.maxPacketSize}B) — ready for NDK stream"
            }
        )
        Log.i(TAG, "Claimed USB DAC $dacName, FD=$fd, EP=${isoEndpoint.address}")
        return true
    }

    fun releaseUsbDac() {
        NativeAudioEngine.stopUsbDacBypass()
        runCatching {
            claimedInterface?.let { iface ->
                activeConnection?.releaseInterface(iface)
            }
            activeConnection?.close()
        }
        claimedInterface = null
        activeConnection = null
        _state.update {
            it.copy(
                isBitPerfectBypassActive = false,
                statusMessage = if (it.isConnected) "USB DAC released to Android Audio" else "Connect a USB-C DAC/AMP to enable direct UAC 1.0/2.0 bypass"
            )
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private fun findBestAudioStreamingInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        var best: Pair<UsbInterface, UsbEndpoint>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val isAudioStreaming = iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == USB_SUBCLASS_AUDIO_STREAMING
            if (!isAudioStreaming && iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue

            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    if (best == null || ep.maxPacketSize > best.second.maxPacketSize) {
                        best = iface to ep
                    }
                }
            }
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
