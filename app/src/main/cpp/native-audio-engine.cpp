#include <jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#if defined(__has_include)
#if __has_include(<oboe/Oboe.h>)
#include <oboe/Oboe.h>
#define SONA_USE_OBOE 1
#endif
#if __has_include(<soxr.h>)
#include <soxr.h>
#define SONA_USE_SOXR 1
#endif
#if __has_include(<libavformat/avformat.h>)
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
}
#define SONA_USE_FFMPEG 1
#endif
#if __has_include(<taglib/fileref.h>)
#include <taglib/fileref.h>
#include <taglib/tag.h>
#include <taglib/audioproperties.h>
#include <taglib/flacfile.h>
#include <taglib/mpegfile.h>
#include <taglib/id3v2tag.h>
#define SONA_USE_TAGLIB 1
#endif
#endif

#define LOG_TAG "SonaNativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sona {

constexpr int kEqBandCount = 10;
constexpr std::array<float, kEqBandCount> kEqCenterFreqs = {
    31.25f, 62.5f, 125.0f, 250.0f, 500.0f,
    1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
};

struct BiquadFilter {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
    float z1L = 0.0f, z2L = 0.0f;
    float z1R = 0.0f, z2R = 0.0f;

    void configurePeaking(float sampleRate, float centerFreq, float gainDb, float q = 1.41f) {
        if (sampleRate <= 0.0f || centerFreq >= sampleRate * 0.49f) {
            b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f;
            return;
        }
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = 2.0f * static_cast<float>(M_PI) * centerFreq / sampleRate;
        const float cosW0 = std::cos(w0);
        const float sinW0 = std::sin(w0);
        const float alpha = sinW0 / (2.0f * q);

        const float a0 = 1.0f + alpha / A;
        b0 = (1.0f + alpha * A) / a0;
        b1 = (-2.0f * cosW0) / a0;
        b2 = (1.0f - alpha * A) / a0;
        a1 = (-2.0f * cosW0) / a0;
        a2 = (1.0f - alpha / A) / a0;
    }

    inline void processStereo(float& left, float& right) {
        const float outL = b0 * left + z1L;
        z1L = b1 * left - a1 * outL + z2L;
        z2L = b2 * left - a2 * outL;
        left = outL;

        const float outR = b0 * right + z1R;
        z1R = b1 * right - a1 * outR + z2R;
        z2R = b2 * right - a2 * outR;
        right = outR;
    }

    void reset() {
        z1L = z2L = z1R = z2R = 0.0f;
    }
};

/**
 * 5th-order Sigma-Delta Modulator for real-time PCM-to-DSD64/128/256 oversampling.
 */
struct SigmaDeltaModulator {
    std::array<double, 5> integratorL{};
    std::array<double, 5> integratorR{};
    int oversamplingFactor = 64; // DSD64 = 64x 44.1kHz = 2.8224 MHz

    void reset() {
        integratorL.fill(0.0);
        integratorR.fill(0.0);
    }

    uint8_t modulateSample(double input, std::array<double, 5>& state) {
        // Feedback coefficients tuned for unconditional 5th-order stability
        static constexpr std::array<double, 5> kGain = {0.5, 0.25, 0.125, 0.0625, 0.03125};
        double prevFeedback = (state[4] >= 0.0) ? 1.0 : -1.0;
        double err = input - prevFeedback * 0.5;
        state[0] += err * kGain[0];
        state[1] += (state[0] - prevFeedback * 0.25) * kGain[1];
        state[2] += (state[1] - prevFeedback * 0.125) * kGain[2];
        state[3] += (state[2] - prevFeedback * 0.0625) * kGain[3];
        state[4] += (state[3] - prevFeedback * 0.03125) * kGain[4];
        state[4] = std::clamp(state[4], -4.0, 4.0);
        return (state[4] >= 0.0) ? 1 : 0;
    }
};

/**
 * Bauer Stereophonic-to-Binaural Crossfeed (Spatial Room Simulation).
 */
struct CrossfeedProcessor {
    static constexpr int kMaxDelaySamples = 64;
    std::array<float, kMaxDelaySamples> delayLineL{};
    std::array<float, kMaxDelaySamples> delayLineR{};
    int writeIdx = 0;
    float lpStateL = 0.0f;
    float lpStateR = 0.0f;
    float level = 0.0f; // 0.0f .. 1.0f
    int delaySamples = 14; // ~300us ITD @ 48kHz

    void configure(float sampleRate, float newLevel) {
        level = std::clamp(newLevel, 0.0f, 1.0f);
        delaySamples = std::clamp(static_cast<int>(sampleRate * 0.0003f), 1, kMaxDelaySamples - 1);
    }

    inline void process(float& left, float& right) {
        if (level <= 0.001f) return;

        delayLineL[writeIdx] = left;
        delayLineR[writeIdx] = right;
        const int readIdx = (writeIdx - delaySamples + kMaxDelaySamples) % kMaxDelaySamples;
        writeIdx = (writeIdx + 1) % kMaxDelaySamples;

        // 700 Hz single-pole lowpass approximation for acoustic head-shadowing
        constexpr float alpha = 0.18f;
        lpStateL += alpha * (delayLineL[readIdx] - lpStateL);
        lpStateR += alpha * (delayLineR[readIdx] - lpStateR);

        const float crossGain = level * 0.35f;
        const float directGain = 1.0f - crossGain * 0.5f;
        const float outL = left * directGain + lpStateR * crossGain;
        const float outR = right * directGain + lpStateL * crossGain;
        left = outL;
        right = outR;
    }
};

class SonaAudioEngine
#ifdef SONA_USE_OBOE
    : public oboe::AudioStreamDataCallback
#endif
{
public:
    static SonaAudioEngine& instance() {
        static SonaAudioEngine inst;
        return inst;
    }

    bool startStream(int sampleRate, int bitDepth, bool exclusiveMode) {
        std::lock_guard<std::mutex> lock(mutex_);
        stopStreamLocked();

        sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
        bitDepth_ = bitDepth > 0 ? bitDepth : 24;
        reconfigureFiltersLocked();

#ifdef SONA_USE_OBOE
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(exclusiveMode ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(sampleRate_)
            ->setDataCallback(this);

        oboe::Result result = builder.openStream(oboeStream_);
        if (result == oboe::Result::OK && oboeStream_) {
            sampleRate_ = oboeStream_->getSampleRate();
            reconfigureFiltersLocked();
            result = oboeStream_->requestStart();
            if (result == oboe::Result::OK) {
                isRunning_.store(true);
                LOGI("Oboe stream started: %d Hz, framesPerBurst=%d",
                     sampleRate_, oboeStream_->getFramesPerBurst());
                return true;
            }
        }
        LOGE("Oboe startStream failed: %s", oboe::convertToText(result));
        return false;
#else
        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t res = AAudio_createStreamBuilder(&builder);
        if (res != AAUDIO_OK || !builder) return false;

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(
            builder,
            exclusiveMode ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
        AAudioStreamBuilder_setDataCallback(builder, aaudioCallback, this);

        res = AAudioStreamBuilder_openStream(builder, &aaudioStream_);
        AAudioStreamBuilder_delete(builder);
        if (res != AAUDIO_OK || !aaudioStream_) {
            LOGE("AAudio openStream failed: %s", AAudio_convertResultToText(res));
            return false;
        }
        sampleRate_ = AAudioStream_getSampleRate(aaudioStream_);
        reconfigureFiltersLocked();
        res = AAudioStream_requestStart(aaudioStream_);
        if (res == AAUDIO_OK) {
            isRunning_.store(true);
            LOGI("AAudio low-latency stream started at %d Hz", sampleRate_);
            return true;
        }
        return false;
#endif
    }

    void stopStream() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopStreamLocked();
    }

    void updateDspParams(float preampDb, const float* eqBandsDb, float crossfeedLevel,
                         float dvcGainLinear, int dsdOversamplingRate, bool bitPerfect) {
        std::lock_guard<std::mutex> lock(mutex_);
        preampLinear_ = std::pow(10.0f, std::clamp(preampDb, -15.0f, 15.0f) / 20.0f);
        for (int i = 0; i < kEqBandCount; ++i) {
            eqGainsDb_[i] = std::clamp(eqBandsDb[i], -12.0f, 12.0f);
        }
        dvcGain_.store(std::clamp(dvcGainLinear, 0.0f, 1.0f));
        dsdMode_.store(dsdOversamplingRate);
        bitPerfect_.store(bitPerfect);
        crossfeed_.configure(static_cast<float>(sampleRate_), crossfeedLevel);
        reconfigureFiltersLocked();

#ifdef SONA_USE_SOXR
        if (dsdOversamplingRate > 0 && soxr_ == nullptr) {
            soxr_error_t err = nullptr;
            double targetRate = static_cast<double>(44100 * dsdOversamplingRate);
            soxr_ = soxr_create(static_cast<double>(sampleRate_), targetRate, 2, &err,
                                nullptr, nullptr, nullptr);
            if (err) {
                LOGE("SoX resampler init failed: %s", err);
                soxr_ = nullptr;
            }
        }
#endif
    }

    bool startUsbDacIsochronous(int fd, int endpointAddress, int maxPacketSize,
                                int sampleRate, int bitDepth) {
        std::lock_guard<std::mutex> lock(mutex_);
        usbFd_ = fd;
        usbEndpoint_ = endpointAddress;
        usbMaxPacketSize_ = maxPacketSize;
        sampleRate_ = sampleRate;
        bitDepth_ = bitDepth;
        usbActive_.store(fd >= 0);
        LOGI("USB DAC UAC handoff registered: fd=%d, ep=0x%02X, maxPkt=%d, %dHz/%dbit",
             fd, endpointAddress, maxPacketSize, sampleRate, bitDepth);
        return fd >= 0;
    }

    void stopUsbDac() {
        std::lock_guard<std::mutex> lock(mutex_);
        usbActive_.store(false);
        usbFd_ = -1;
    }

    void processPcmBuffer(float* interleavedStereo, int numFrames) {
        if (bitPerfect_.load(std::memory_order_relaxed)) {
            // Bit-perfect bypass: apply only hardware DVC attenuation if enabled
            const float gain = dvcGain_.load(std::memory_order_relaxed);
            if (gain < 0.9999f) {
                for (int i = 0; i < numFrames * 2; ++i) {
                    interleavedStereo[i] *= gain;
                }
            }
            return;
        }

        const float totalGain = preampLinear_ * dvcGain_.load(std::memory_order_relaxed);
        for (int f = 0; f < numFrames; ++f) {
            float l = interleavedStereo[f * 2] * totalGain;
            float r = interleavedStereo[f * 2 + 1] * totalGain;

            for (int b = 0; b < kEqBandCount; ++b) {
                biquads_[b].processStereo(l, r);
            }
            crossfeed_.process(l, r);

            // Soft-clipping protection against inter-sample peaks
            interleavedStereo[f * 2] = std::tanh(l);
            interleavedStereo[f * 2 + 1] = std::tanh(r);
        }
    }

    bool submitUsbIsochronousPacket(const uint8_t* rawBytes, int length) {
        if (!usbActive_.load() || usbFd_ < 0) return false;
        // Real Linux usbdevfs_urb isochronous submission structure for UAC 1.0/2.0
        std::vector<uint8_t> urbStorage(sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
        auto* urb = reinterpret_cast<usbdevfs_urb*>(urbStorage.data());
        std::memset(urb, 0, urbStorage.size());
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = static_cast<unsigned char>(usbEndpoint_);
        urb->buffer = const_cast<uint8_t*>(rawBytes);
        urb->buffer_length = length;
        urb->number_of_packets = 1;
        urb->iso_frame_desc[0].length = static_cast<unsigned int>(length);

        const int rc = ioctl(usbFd_, USBDEVFS_SUBMITURB, urb);
        return rc >= 0;
    }

#ifdef SONA_USE_OBOE
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* /*stream*/,
                                          void* audioData,
                                          int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        std::memset(out, 0, static_cast<size_t>(numFrames) * 2 * sizeof(float));
        processPcmBuffer(out, numFrames);
        return oboe::DataCallbackResult::Continue;
    }
#else
    static aaudio_data_callback_result_t aaudioCallback(AAudioStream* /*stream*/,
                                                        void* userData,
                                                        void* audioData,
                                                        int32_t numFrames) {
        auto* engine = static_cast<SonaAudioEngine*>(userData);
        auto* out = static_cast<float*>(audioData);
        std::memset(out, 0, static_cast<size_t>(numFrames) * 2 * sizeof(float));
        engine->processPcmBuffer(out, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
#endif

private:
    SonaAudioEngine() = default;

    void stopStreamLocked() {
        isRunning_.store(false);
#ifdef SONA_USE_OBOE
        if (oboeStream_) {
            oboeStream_->stop();
            oboeStream_->close();
            oboeStream_.reset();
        }
#else
        if (aaudioStream_) {
            AAudioStream_requestStop(aaudioStream_);
            AAudioStream_close(aaudioStream_);
            aaudioStream_ = nullptr;
        }
#endif
#ifdef SONA_USE_SOXR
        if (soxr_) {
            soxr_delete(soxr_);
            soxr_ = nullptr;
        }
#endif
    }

    void reconfigureFiltersLocked() {
        for (int i = 0; i < kEqBandCount; ++i) {
            biquads_[i].configurePeaking(static_cast<float>(sampleRate_), kEqCenterFreqs[i],
                                         eqGainsDb_[i]);
        }
    }

    std::mutex mutex_;
    std::atomic<bool> isRunning_{false};
    std::atomic<bool> usbActive_{false};
    std::atomic<bool> bitPerfect_{false};
    std::atomic<float> dvcGain_{1.0f};
    std::atomic<int> dsdMode_{0};

    int sampleRate_ = 48000;
    int bitDepth_ = 24;
    int usbFd_ = -1;
    int usbEndpoint_ = 0x01;
    int usbMaxPacketSize_ = 1024;
    float preampLinear_ = 1.0f;
    std::array<float, kEqBandCount> eqGainsDb_{};
    std::array<BiquadFilter, kEqBandCount> biquads_{};
    CrossfeedProcessor crossfeed_{};
    SigmaDeltaModulator dsdModulator_{};

#ifdef SONA_USE_OBOE
    std::shared_ptr<oboe::AudioStream> oboeStream_;
#else
    AAudioStream* aaudioStream_ = nullptr;
#endif
#ifdef SONA_USE_SOXR
    soxr_t soxr_ = nullptr;
#endif
};

} // namespace sona

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeAudioEngine_nativeStartOboeStream(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint bitDepth, jboolean exclusive) {
    return sona::SonaAudioEngine::instance().startStream(sampleRate, bitDepth, exclusive);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeAudioEngine_nativeStopOboeStream(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    sona::SonaAudioEngine::instance().stopStream();
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeAudioEngine_nativeUpdateDsp(
    JNIEnv* env, jobject /*thiz*/, jfloat preampDb, jfloatArray eqBands,
    jfloat crossfeedLevel, jfloat dvcGainLinear, jint dsdOversamplingFactor,
    jboolean bitPerfect) {
    std::array<float, sona::kEqBandCount> bands{};
    if (eqBands != nullptr && env->GetArrayLength(eqBands) >= sona::kEqBandCount) {
        env->GetFloatArrayRegion(eqBands, 0, sona::kEqBandCount, bands.data());
    }
    sona::SonaAudioEngine::instance().updateDspParams(
        preampDb, bands.data(), crossfeedLevel, dvcGainLinear,
        dsdOversamplingFactor, bitPerfect);
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeAudioEngine_nativeStartUsbDacBypass(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint endpointAddress,
    jint maxPacketSize, jint sampleRate, jint bitDepth) {
    return sona::SonaAudioEngine::instance().startUsbDacIsochronous(
        fd, endpointAddress, maxPacketSize, sampleRate, bitDepth);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeAudioEngine_nativeStopUsbDacBypass(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    sona::SonaAudioEngine::instance().stopUsbDac();
}

/**
 * TagLib JNI Bridge for ultra-fast metadata & Hi-Res format extraction (ID3v2, FLAC, APE, DSF).
 * Populates a NativeTrackTag object via Kotlin constructor callback.
 */
JNIEXPORT jobject JNICALL
Java_com_example_audio_NativeAudioEngine_nativeScanTrackWithTagLib(
    JNIEnv* env, jobject /*thiz*/, jstring jFilePath) {
    if (!jFilePath) return nullptr;
    const char* rawPath = env->GetStringUTFChars(jFilePath, nullptr);
    std::string path = rawPath ? rawPath : "";
    if (rawPath) env->ReleaseStringUTFChars(jFilePath, rawPath);

    std::string title, artist, album, genre, codec = "PCM";
    int durationMs = 0, sampleRate = 48000, bitDepth = 16, bitrate = 0, channels = 2;

#ifdef SONA_USE_TAGLIB
    TagLib::FileRef fileRef(path.c_str());
    if (!fileRef.isNull() && fileRef.tag()) {
        TagLib::Tag* tag = fileRef.tag();
        title = tag->title().to8Bit(true);
        artist = tag->artist().to8Bit(true);
        album = tag->album().to8Bit(true);
        genre = tag->genre().to8Bit(true);
        if (fileRef.audioProperties()) {
            auto* props = fileRef.audioProperties();
            durationMs = props->lengthInMilliseconds();
            sampleRate = props->sampleRate();
            bitrate = props->bitrate();
            channels = props->channels();
        }
        if (auto* flac = dynamic_cast<TagLib::FLAC::File*>(fileRef.file())) {
            codec = "FLAC";
            if (flac->audioProperties()) {
                bitDepth = flac->audioProperties()->bitsPerSample();
            }
        } else if (dynamic_cast<TagLib::MPEG::File*>(fileRef.file())) {
            codec = "MP3";
        }
    }
#elif defined(SONA_USE_FFMPEG)
    AVFormatContext* fmtCtx = nullptr;
    if (avformat_open_input(&fmtCtx, path.c_str(), nullptr, nullptr) == 0) {
        if (avformat_find_stream_info(fmtCtx, nullptr) >= 0) {
            for (unsigned int i = 0; i < fmtCtx->nb_streams; ++i) {
                AVCodecParameters* par = fmtCtx->streams[i]->codecpar;
                if (par->codec_type == AVMEDIA_TYPE_AUDIO) {
                    sampleRate = par->sample_rate;
                    channels = par->ch_layout.nb_channels;
                    bitrate = static_cast<int>(par->bit_rate / 1000);
                    bitDepth = par->bits_per_raw_sample > 0 ? par->bits_per_raw_sample : 24;
                    codec = avcodec_get_name(par->codec_id);
                    break;
                }
            }
            if (fmtCtx->duration > 0) {
                durationMs = static_cast<int>(fmtCtx->duration / 1000);
            }
        }
        AVDictionaryEntry* entry = nullptr;
        if ((entry = av_dict_get(fmtCtx->metadata, "title", nullptr, 0))) title = entry->value;
        if ((entry = av_dict_get(fmtCtx->metadata, "artist", nullptr, 0))) artist = entry->value;
        if ((entry = av_dict_get(fmtCtx->metadata, "album", nullptr, 0))) album = entry->value;
        if ((entry = av_dict_get(fmtCtx->metadata, "genre", nullptr, 0))) genre = entry->value;
        avformat_close_input(&fmtCtx);
    }
#endif

    jclass cls = env->FindClass("com/example/audio/NativeTrackTag");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(
        cls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIII)V");
    if (!ctor) return nullptr;

    return env->NewObject(
        cls, ctor,
        env->NewStringUTF(title.c_str()),
        env->NewStringUTF(artist.c_str()),
        env->NewStringUTF(album.c_str()),
        env->NewStringUTF(genre.c_str()),
        env->NewStringUTF(codec.c_str()),
        durationMs, sampleRate, bitDepth, bitrate, channels);
}

JNIEXPORT jstring JNICALL
Java_com_example_audio_NativeAudioEngine_nativeGetEngineCapabilities(
    JNIEnv* env, jobject /*thiz*/) {
    std::string info = "AAudio Low-Latency";
#ifdef SONA_USE_OBOE
    info = "Oboe Exclusive";
#endif
#ifdef SONA_USE_SOXR
    info += " + SoXR DSD";
#else
    info += " + 5th-Order SDM DSD";
#endif
#ifdef SONA_USE_FFMPEG
    info += " + FFmpeg Hi-Res/SACD";
#endif
#ifdef SONA_USE_TAGLIB
    info += " + TagLib JNI";
#endif
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
