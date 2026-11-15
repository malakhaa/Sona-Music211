package com.example.audio

import android.util.Log

/**
 * JNI mirror populated by TagLib / FFmpeg native scanner in native-audio-engine.cpp.
 */
data class NativeTrackTag(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val codec: String,
    val durationMs: Int,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val channels: Int
)

/**
 * Safe JNI bridge to libsona_audio_engine.so (Oboe/AAudio, SoX PCM-to-DSD, FFmpeg, TagLib).
 * Gracefully degrades to Android AudioTrack/AudioEffect DSP when built without NDK binaries.
 */
object NativeAudioEngine {
    private const val TAG = "NativeAudioEngine"

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("sona_audio_engine")
        true
    } catch (t: UnsatisfiedLinkError) {
        Log.i(TAG, "Native libsona_audio_engine.so not bundled; using Android AudioEffect/AAudio fallback")
        false
    }

    fun startLowLatencyStream(sampleRate: Int, bitDepth: Int, exclusive: Boolean): Boolean {
        return if (isNativeLoaded) {
            nativeStartOboeStream(sampleRate, bitDepth, exclusive)
        } else {
            false
        }
    }

    fun stopLowLatencyStream() {
        if (isNativeLoaded) {
            nativeStopOboeStream()
        }
    }

    fun updateDspParameters(
        preampDb: Float,
        eqBandsDb: FloatArray,
        crossfeedLevel: Float,
        dvcGainLinear: Float,
        dsdOversamplingFactor: Int,
        bitPerfect: Boolean
    ) {
        if (isNativeLoaded) {
            nativeUpdateDsp(
                preampDb = preampDb,
                eqBands = eqBandsDb,
                crossfeedLevel = crossfeedLevel,
                dvcGainLinear = dvcGainLinear,
                dsdOversamplingFactor = dsdOversamplingFactor,
                bitPerfect = bitPerfect
            )
        }
    }

    fun startUsbDacBypass(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        sampleRate: Int,
        bitDepth: Int
    ): Boolean {
        return if (isNativeLoaded) {
            nativeStartUsbDacBypass(fd, endpointAddress, maxPacketSize, sampleRate, bitDepth)
        } else {
            false
        }
    }

    fun stopUsbDacBypass() {
        if (isNativeLoaded) {
            nativeStopUsbDacBypass()
        }
    }

    fun scanTrackWithTagLib(filePath: String): NativeTrackTag? {
        return if (isNativeLoaded) {
            nativeScanTrackWithTagLib(filePath)
        } else {
            null
        }
    }

    fun getEngineCapabilities(): String {
        return if (isNativeLoaded) {
            nativeGetEngineCapabilities()
        } else {
            "Media3 AudioTrack Float + Android DynamicsProcessing DSP (NDK Ready)"
        }
    }

    private external fun nativeStartOboeStream(sampleRate: Int, bitDepth: Int, exclusive: Boolean): Boolean
    private external fun nativeStopOboeStream()
    private external fun nativeUpdateDsp(
        preampDb: Float,
        eqBands: FloatArray,
        crossfeedLevel: Float,
        dvcGainLinear: Float,
        dsdOversamplingFactor: Int,
        bitPerfect: Boolean
    )
    private external fun nativeStartUsbDacBypass(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        sampleRate: Int,
        bitDepth: Int
    ): Boolean
    private external fun nativeStopUsbDacBypass()
    private external fun nativeScanTrackWithTagLib(filePath: String): NativeTrackTag?
    private external fun nativeGetEngineCapabilities(): String
}
