#include <jni.h>
#include <android/log.h>

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char * kTag = "PlamoLlama";

struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex generationMutex;
    std::atomic_bool cancel{false};
};

std::string utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (codepoint <= 0x7F) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring javaString(JNIEnv * env, const std::string & value) {
    std::vector<jchar> chars;
    chars.reserve(value.size());
    for (size_t i = 0; i < value.size();) {
        const auto lead = static_cast<uint8_t>(value[i++]);
        uint32_t codepoint = 0xFFFD;
        size_t continuationCount = 0;
        if (lead < 0x80) {
            codepoint = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            codepoint = lead & 0x1F;
            continuationCount = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            codepoint = lead & 0x0F;
            continuationCount = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            codepoint = lead & 0x07;
            continuationCount = 3;
        }
        bool valid = i + continuationCount <= value.size();
        for (size_t n = 0; valid && n < continuationCount; ++n) {
            const auto next = static_cast<uint8_t>(value[i++]);
            if ((next & 0xC0) != 0x80) {
                valid = false;
                --i;
                break;
            }
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        if (!valid || codepoint > 0x10FFFF || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
            codepoint = 0xFFFD;
        }
        if (codepoint <= 0xFFFF) {
            chars.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            chars.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
            chars.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        }
    }
    return env->NewString(chars.empty() ? nullptr : chars.data(), static_cast<jsize>(chars.size()));
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(64);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(),
                                         static_cast<int32_t>(buffer.size()), 0, true);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(),
                                     static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (count <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(count));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_feature_lyrics_PlamoNativeBridge_nativeCreateModel(
        JNIEnv * env, jclass, jstring modelPath, jint requestedThreads) {
    const std::string path = utf8(env, modelPath);
    if (path.empty()) return 0;

    static std::once_flag backendInit;
    std::call_once(backendInit, [] { llama_backend_init(); });
    auto session = new Session();
    auto modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.load_mode = LLAMA_LOAD_MODE_AUTO;
    session->model = llama_model_load_from_file(path.c_str(), modelParams);
    if (session->model == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not load the PLaMo GGUF model");
        delete session;
        return 0;
    }

    const int32_t threads = std::clamp(static_cast<int32_t>(requestedThreads), 1, 4);
    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = 2048;
    contextParams.n_batch = 512;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    session->context = llama_init_from_model(session->model, contextParams);
    if (session->context == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not create the PLaMo inference context");
        llama_model_free(session->model);
        delete session;
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_music_bitchord_feature_lyrics_PlamoNativeBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring promptString, jint maxTokens) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || promptString == nullptr) return nullptr;

    const std::string prompt = utf8(env, promptString);
    if (prompt.empty() || prompt.size() > 96 * 1024) return nullptr;

    std::lock_guard<std::mutex> lock(session->generationMutex);
    session->cancel.store(false, std::memory_order_relaxed);
    llama_memory_clear(llama_get_memory(session->context), true);

    std::vector<llama_token> promptTokens(std::max<size_t>(64, prompt.size() + 16));
    int32_t promptCount = llama_tokenize(
            session->vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
            promptTokens.data(), static_cast<int32_t>(promptTokens.size()), true, true);
    if (promptCount < 0) {
        promptTokens.resize(static_cast<size_t>(-promptCount));
        promptCount = llama_tokenize(
                session->vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                promptTokens.data(), static_cast<int32_t>(promptTokens.size()), true, true);
    }
    if (promptCount <= 0 || promptCount >= static_cast<int32_t>(llama_n_ctx(session->context))) {
        return nullptr;
    }
    promptTokens.resize(static_cast<size_t>(promptCount));
    if (llama_decode(session->context,
                     llama_batch_get_one(promptTokens.data(), promptCount)) != 0) {
        return nullptr;
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) return nullptr;
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    const int32_t limit = std::clamp(static_cast<int32_t>(maxTokens), 1, 512);
    std::string output;
    for (int32_t i = 0; i < limit; ++i) {
        if (session->cancel.load(std::memory_order_relaxed)) break;
        const llama_token token = llama_sampler_sample(sampler, session->context, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        llama_sampler_accept(sampler, token);
        output += tokenPiece(session->vocab, token);

        // PFML3 terminates a translation with a new element marker. Some
        // tokenizers emit the marker as visible text rather than EOG.
        const size_t marker = output.find("<|plamo:tag|>");
        if (marker != std::string::npos) {
            output.resize(marker);
            break;
        }
        const size_t bos = output.find("<|plamo:bos|>");
        if (bos != std::string::npos) {
            output.resize(bos);
            break;
        }

        llama_token next = token;
        if (llama_decode(session->context, llama_batch_get_one(&next, 1)) != 0) break;
    }
    llama_sampler_free(sampler);

    return javaString(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_feature_lyrics_PlamoNativeBridge_nativeCancel(
        JNIEnv *, jclass, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->cancel.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_feature_lyrics_PlamoNativeBridge_nativeReleaseModel(
        JNIEnv *, jclass, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->cancel.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(session->generationMutex);
    if (session->context != nullptr) llama_free(session->context);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}
