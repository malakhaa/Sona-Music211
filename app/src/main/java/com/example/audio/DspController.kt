package com.example.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import com.example.data.HardwareProfileEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * Manages the active audiophile DSP chain:
 *  1) Pushes 10-band EQ, Pre-amp, Crossfeed, DVC, and DSD oversampling to C++ NDK engine.
 *  2) Applies real-time Android DynamicsProcessing (API 28+, 10-band parametric EQ + pre-gain)
 *     or fallback Equalizer + LoudnessEnhancer to ExoPlayer's audioSessionId.
 */
class DspController {

    companion object {
        private const val TAG = "DspController"
    }

    private var currentSessionId: Int = 0
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
    private var legacyPreamp: LoudnessEnhancer? = null

    private val _activeProfile = MutableStateFlow(HardwareProfileEntity.defaultFor("SPEAKER"))
    val activeProfile: StateFlow<HardwareProfileEntity> = _activeProfile.asStateFlow()

    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        releaseEffects()
        currentSessionId = audioSessionId
        initEffectsForSession(audioSessionId)
        applyProfile(_activeProfile.value)
    }

    fun applyProfile(profile: HardwareProfileEntity) {
        _activeProfile.value = profile
        val bands = profile.eqBandsList().toFloatArray()
        val dvcGainLinear = if (profile.dvcEnabled) {
            10.0f.pow(profile.dvcAttenuationDb.coerceIn(-60f, 0f) / 20.0f)
        } else {
            1.0f
        }

        // 1. Update C++ NDK Oboe/SoX/Crossfeed engine
        NativeAudioEngine.updateDspParameters(
            preampDb = profile.preampDb,
            eqBandsDb = bands,
            crossfeedLevel = profile.crossfeedLevel,
            dvcGainLinear = dvcGainLinear,
            dsdOversamplingFactor = profile.dsdOversamplingFactor,
            bitPerfect = profile.bitPerfectEnabled
        )

        // 2. Apply Android AudioEffect chain for Media3 AudioTrack pipeline
        if (currentSessionId <= 0) return

        val bypassEffects = profile.bitPerfectEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            runCatching {
                val dp = dynamicsProcessing ?: return@runCatching
                dp.enabled = !bypassEffects
                if (!bypassEffects) {
                    dp.setInputGainAllChannelsTo(profile.preampDb.coerceIn(-15f, 15f))
                    val eqConfig = DynamicsProcessing.Eq(true, true, 10)
                    HardwareProfileEntity.EQ_FREQUENCIES_HZ.forEachIndexed { index, freq ->
                        val band = DynamicsProcessing.EqBand(
                            true,
                            freq.toFloat(),
                            bands.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
                        )
                        eqConfig.setBand(index, band)
                    }
                    dp.setPreEqAllChannelsTo(eqConfig)
                }
            }.onFailure { Log.w(TAG, "DynamicsProcessing update error: ${it.message}") }
        } else {
            runCatching {
                legacyEqualizer?.let { eq ->
                    eq.enabled = !bypassEffects
                    if (!bypassEffects) {
                        val bandCount = eq.numberOfBands.toInt()
                        val minLevel = eq.bandLevelRange[0]
                        val maxLevel = eq.bandLevelRange[1]
                        for (b in 0 until bandCount) {
                            val mappedIdx = ((b.toFloat() / bandCount.coerceAtLeast(1)) * 10).toInt().coerceIn(0, 9)
                            val db = bands[mappedIdx]
                            val millibels = (db * 100f).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt())
                            eq.setBandLevel(b.toShort(), millibels.toShort())
                        }
                    }
                }
                legacyPreamp?.let { enhancer ->
                    enhancer.enabled = !bypassEffects && profile.preampDb > 0f
                    if (enhancer.enabled) {
                        enhancer.setTargetGain((profile.preampDb * 100f).toInt().coerceIn(0, 1500))
                    }
                }
            }.onFailure { Log.w(TAG, "Legacy Equalizer update error: ${it.message}") }
        }
    }

    private fun initEffectsForSession(sessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    10,
                    false,
                    0,
                    false,
                    0,
                    true
                ).build()
                dynamicsProcessing = DynamicsProcessing(0, sessionId, config).apply {
                    enabled = true
                }
            }.onFailure {
                Log.w(TAG, "DynamicsProcessing unavailable on device, falling back to Equalizer: ${it.message}")
                initLegacyEffects(sessionId)
            }
        } else {
            initLegacyEffects(sessionId)
        }
    }

    private fun initLegacyEffects(sessionId: Int) {
        runCatching {
            legacyEqualizer = Equalizer(0, sessionId).apply { enabled = true }
            legacyPreamp = LoudnessEnhancer(sessionId).apply { enabled = false }
        }.onFailure {
            Log.w(TAG, "AudioEffect init skipped: ${it.message}")
        }
    }

    fun releaseEffects() {
        runCatching { dynamicsProcessing?.release() }
        runCatching { legacyEqualizer?.release() }
        runCatching { legacyPreamp?.release() }
        dynamicsProcessing = null
        legacyEqualizer = null
        legacyPreamp = null
        currentSessionId = 0
    }
}
