package com.example.hardware

import android.content.Context
import android.hardware.ConsumerIrManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BeztonCommand(
    val label: String,
    val necHexCode: Long,
    val category: String
) {
    POWER("Power", 0x807F02FDL, "SYSTEM"),
    MUTE("Mute", 0x807F827DL, "SYSTEM"),
    INPUT_CYCLE("Input (AUX/BT/USB)", 0x807F42BDL, "INPUT"),
    MODE_5_1("5.1 / 2.1 Surround", 0x807FC23DL, "MODE"),
    EQ_PRESET("Speaker EQ", 0x807F22DDL, "MODE"),

    VOL_UP("Master Vol +", 0x807FA25DL, "VOLUME"),
    VOL_DOWN("Master Vol -", 0x807F629DL, "VOLUME"),
    SUB_UP("Subwoofer +", 0x807FE21DL, "CHANNELS"),
    SUB_DOWN("Subwoofer -", 0x807F12EDL, "CHANNELS"),
    CENTER_UP("Center +", 0x807F926DL, "CHANNELS"),
    CENTER_DOWN("Center -", 0x807F52ADL, "CHANNELS"),
    REAR_UP("Surround Rear +", 0x807FD22DL, "CHANNELS"),
    REAR_DOWN("Surround Rear -", 0x807F32CDL, "CHANNELS"),
    BASS_UP("Bass +", 0x807FB24DL, "TONE"),
    BASS_DOWN("Bass -", 0x807F728DL, "TONE"),
    TREBLE_UP("Treble +", 0x807FF20DL, "TONE"),
    TREBLE_DOWN("Treble -", 0x807F0AF5L, "TONE"),

    PREV("Prev", 0x807F8A75L, "TRANSPORT"),
    PLAY_PAUSE("Play / Pause", 0x807F4AB5L, "TRANSPORT"),
    NEXT("Next", 0x807FCA35L, "TRANSPORT")
}

data class IrTransmissionStatus(
    val hasHardwareIrEmitter: Boolean = false,
    val supportedCarrierRangeText: String = "38 kHz NEC Standard",
    val lastCommandLabel: String = "Ready",
    val lastNecHex: String = "0x807F02FD",
    val lastPulseTrainMicros: List<Int> = emptyList(),
    val transmissionCount: Int = 0
)

/**
 * ConsumerIrManager controller transmitting custom 38 kHz NEC infrared frames
 * for Bezton 4.1 / 5.1 Multimedia Home Theater Speaker Systems.
 */
class BeztonIrRemote(context: Context) {

    companion object {
        const val NEC_CARRIER_HZ = 38_000
        private const val LEADER_MARK_US = 9000
        private const val LEADER_SPACE_US = 4500
        private const val BIT_MARK_US = 562
        private const val ZERO_SPACE_US = 562
        private const val ONE_SPACE_US = 1687
        private const val STOP_MARK_US = 562
    }

    private val irManager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    private val _status = MutableStateFlow(buildInitialStatus())
    val status: StateFlow<IrTransmissionStatus> = _status.asStateFlow()

    private fun buildInitialStatus(): IrTransmissionStatus {
        val hasIr = irManager?.hasIrEmitter() == true
        val freqInfo = if (hasIr) {
            val ranges = runCatching { irManager?.carrierFrequencies }.getOrNull()
            ranges?.joinToString(", ") { "${it.minFrequency / 1000}-${it.maxFrequency / 1000} kHz" }
                ?.ifBlank { "38 kHz NEC" } ?: "38 kHz NEC"
        } else {
            "No physical IR blaster (NEC Frame Generator Ready)"
        }
        return IrTransmissionStatus(
            hasHardwareIrEmitter = hasIr,
            supportedCarrierRangeText = freqInfo
        )
    }

    fun sendCommand(command: BeztonCommand): Boolean {
        return transmitNecHex(command.necHexCode, command.label)
    }

    /**
     * Encodes a 32-bit NEC hex payload (e.g. 0x807F02FD) into microsecond mark/space
     * bursts and transmits via ConsumerIrManager at 38 kHz.
     */
    fun transmitNecHex(necCode32: Long, label: String = "Custom NEC"): Boolean {
        val pattern = encodeNec32Pattern(necCode32)
        val hasEmitter = irManager?.hasIrEmitter() == true
        var transmitted = false
        if (hasEmitter) {
            transmitted = runCatching {
                irManager?.transmit(NEC_CARRIER_HZ, pattern)
                true
            }.getOrDefault(false)
        }

        _status.value = _status.value.copy(
            hasHardwareIrEmitter = hasEmitter,
            lastCommandLabel = if (hasEmitter && transmitted) {
                "TX: $label"
            } else {
                "Encoded Frame: $label"
            },
            lastNecHex = "0x%08X".format(necCode32 and 0xFFFFFFFFL),
            lastPulseTrainMicros = pattern.toList(),
            transmissionCount = _status.value.transmissionCount + 1
        )
        return transmitted
    }

    /**
     * Generates standard NEC 32-bit IR timing sequence in microseconds:
     * [9000, 4500] + 32x [562, 562 or 1687] + [562]
     */
    fun encodeNec32Pattern(code32: Long): IntArray {
        val pulses = IntArray(2 + 32 * 2 + 1)
        pulses[0] = LEADER_MARK_US
        pulses[1] = LEADER_SPACE_US

        var idx = 2
        for (bitPos in 31 downTo 0) {
            val isOne = ((code32 ushr bitPos) and 1L) == 1L
            pulses[idx++] = BIT_MARK_US
            pulses[idx++] = if (isOne) ONE_SPACE_US else ZERO_SPACE_US
        }
        pulses[idx] = STOP_MARK_US
        return pulses
    }
}
