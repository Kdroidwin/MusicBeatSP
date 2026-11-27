#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

extern "C" {
#include "dsee_config.h"
#include "dsee_kern.h"
}

namespace {
constexpr int kChannels = 2;
constexpr int kRing = 1589;
constexpr int kHistory = 42;

struct DseeEngine {
    std::vector<uint8_t> memory;
    DseeSource source{};
    DseeCfg config{};
    std::vector<float> ringLeft = std::vector<float>(kRing * 2);
    std::vector<float> ringRight = std::vector<float>(kRing * 2);
    std::vector<float> histLeft = std::vector<float>(kHistory);
    std::vector<float> histRight = std::vector<float>(kHistory);
    std::vector<int32_t> heqScratch;
    std::vector<float> frameScratch;
    int delay[7]{};
    int lag = 0;
    int historyIndex = 0;
    float heqGain = 0.f;
};

void resetState(DseeEngine* engine) {
    std::fill(engine->ringLeft.begin(), engine->ringLeft.end(), 0.f);
    std::fill(engine->ringRight.begin(), engine->ringRight.end(), 0.f);
    std::fill(engine->histLeft.begin(), engine->histLeft.end(), 0.f);
    std::fill(engine->histRight.begin(), engine->histRight.end(), 0.f);
    engine->lag = 0;
    engine->historyIndex = 0;
}

bool configure(DseeEngine* engine) {
    std::fill(engine->memory.begin(), engine->memory.end(), 0);
    if (dsee_open_source(engine->memory.data(), static_cast<int>(engine->memory.size()),
                         &engine->source, &engine->config) != 0) {
        return false;
    }
    if (engine->config.channels != kChannels || engine->config.rs_in <= 0 ||
        engine->config.rs_out <= 0 || engine->config.rs_in > 512 ||
        engine->config.rs_out > 2048 || (engine->config.rs_in & 1) != 0 ||
        (engine->config.rs_out & 1) != 0) {
        return false;
    }
    if (dsee_heq_delays(engine->config.src, engine->delay) != 0 ||
        dsee_heq_gain(engine->source.input_bits, &engine->heqGain) != 0) {
        return false;
    }
    engine->heqScratch.resize(static_cast<size_t>(engine->config.rs_in) * kChannels);
    engine->frameScratch.resize(static_cast<size_t>(engine->config.rs_out) * kChannels);
    resetState(engine);
    return true;
}

DseeEngine* engineFrom(jlong handle) {
    return reinterpret_cast<DseeEngine*>(static_cast<intptr_t>(handle));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeCreate(
    JNIEnv* env, jclass, jstring codecName, jint inputRate, jint inputBits,
    jint outputRate, jint bitRate) {
    if (codecName == nullptr || inputRate <= 0 || outputRate <= 0 ||
        (inputBits != 8 && inputBits != 16 && inputBits != 24 && inputBits != 32)) {
        return 0;
    }

    const char* codec = env->GetStringUTFChars(codecName, nullptr);
    if (codec == nullptr) return 0;

    bool codecReleased = false;
    try {
        std::unique_ptr<DseeEngine> engine(new DseeEngine());
        const int memorySize = dsee_mem_size();
        if (memorySize <= 0) {
            env->ReleaseStringUTFChars(codecName, codec);
            codecReleased = true;
            return 0;
        }
        engine->memory.resize(static_cast<size_t>(memorySize));

        DseeMediaFacts facts{};
        facts.rate = inputRate;
        facts.channels = kChannels;
        facts.bits = inputBits;
        facts.bit_rate = std::max(0, bitRate);
        facts.path_has_cbr = 0;
        std::strncpy(facts.codec_name, codec, sizeof(facts.codec_name) - 1);
        env->ReleaseStringUTFChars(codecName, codec);
        codecReleased = true;

        if (dsee_fill_source(&facts, outputRate, 32, 1, &engine->source) != 0 ||
            !configure(engine.get()) || engine->config.src != inputRate ||
            engine->config.dst != outputRate) {
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(engine.release()));
    } catch (...) {
        if (!codecReleased) env->ReleaseStringUTFChars(codecName, codec);
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeReset(JNIEnv*, jclass, jlong handle) {
    DseeEngine* engine = engineFrom(handle);
    if (engine == nullptr) return JNI_FALSE;
    try {
        if (!configure(engine)) return JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeInputHopFrames(JNIEnv*, jclass, jlong handle) {
    DseeEngine* engine = engineFrom(handle);
    return engine == nullptr ? 0 : engine->config.rs_in;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeOutputHopFrames(JNIEnv*, jclass, jlong handle) {
    DseeEngine* engine = engineFrom(handle);
    return engine == nullptr ? 0 : engine->config.rs_out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeProcess(
    JNIEnv* env, jclass, jlong handle, jintArray input, jint hops,
    jfloatArray output) {
    DseeEngine* engine = engineFrom(handle);
    if (engine == nullptr || input == nullptr || output == nullptr || hops <= 0) return JNI_FALSE;

    const int nIn = engine->config.rs_in * kChannels;
    const int nOut = engine->config.rs_out * kChannels;
    const int64_t inCount = static_cast<int64_t>(nIn) * hops;
    const int64_t outCount = static_cast<int64_t>(nOut) * hops;
    if (inCount > env->GetArrayLength(input) || outCount > env->GetArrayLength(output)) return JNI_FALSE;

    jint* pcm = env->GetIntArrayElements(input, nullptr);
    if (pcm == nullptr) return JNI_FALSE;
    jfloat* rendered = env->GetFloatArrayElements(output, nullptr);
    if (rendered == nullptr) {
        env->ReleaseIntArrayElements(input, pcm, JNI_ABORT);
        return JNI_FALSE;
    }

    const int result = dsee_process_i32_with_scratch(
        engine->memory.data(), engine->ringLeft.data(), engine->ringRight.data(),
        engine->histLeft.data(), engine->histRight.data(), &engine->lag,
        &engine->historyIndex, engine->delay, engine->heqGain, pcm, hops,
        nIn, nOut, engine->config.rs_in, engine->config.rs_out, rendered,
        engine->heqScratch.data(), engine->frameScratch.data());

    env->ReleaseFloatArrayElements(output, rendered, result == 0 ? 0 : JNI_ABORT);
    env->ReleaseIntArrayElements(input, pcm, JNI_ABORT);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_DseeHxNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete engineFrom(handle);
}
